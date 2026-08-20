package com.example.jadxmcpserver.core;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.RootNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The name a field had when the APK was loaded, kept for as long as it stays
 * loaded. The field half of {@link PinnedMethodNames}, and it exists because a
 * measurement that said it was unnecessary was taken on the wrong sample.
 *
 * <h2>Why this class exists on the second attempt</h2>
 *
 * The round that pinned method names measured field aliases too, over a
 * 3,000-class warm-up of a large production APK, saw 0 of 861,374 move, and wrote that into
 * {@link PinnedMethodNames} as the reason not to pin them. The measurement was
 * honest and the conclusion was wrong, because of WHICH 3,000 classes: a prefix
 * of {@code RootNode.getClasses()}, which on that APK contains almost no enums.
 *
 * {@code EnumVisitor} is the only pass in jadx that calls
 * {@code FieldInfo.setAlias}, and it runs during decompilation. It recovers the
 * real constant name out of the {@code <clinit>} and renames the field to it.
 * So all the field drift there is lives in enums, and a sample that misses
 * enums measures zero however large it is.
 *
 * Re-measured with every enum in the APK decompiled first, then 12,000 further
 * top-level classes:
 *
 * <pre>
 *   a large production APK            1,958 of 861,374 fields, 2,517 enum classes
 *                        e.g. C2833e$b.f2385a -> NONE, f2386b -> MOBILE
 *   DivaApplication.apk      0 of   9,643, and it declares exactly 1 enum
 * </pre>
 *
 * 0.23% is EIGHT TIMES the method drift rate that justified pinning methods,
 * and it lands on the most useful names in the APK -- an enum constant called
 * {@code NONE} or {@code WIFI} is exactly the kind of value a caller records
 * mid-trace. A recorded name that rots is not a wrong answer, it is a missing
 * one: the later phase gets "Field not found" for a field that is right there.
 *
 * <h2>Why FieldNode is a safe key</h2>
 *
 * {@code FieldInfo.hashCode()} is built from the RAW name, the type and the
 * declaring class, and {@code equals} compares the same three;
 * {@code FieldNode} delegates both to it. None of them reads the alias, and
 * {@code setAlias} writes only the alias field, so an entry stays findable
 * after the very drift this class records. Same property {@link
 * PinnedMethodNames} and {@link DexReverseIndex} already rely on for methods.
 *
 * <h2>What is still deliberately NOT pinned</h2>
 *
 * Class aliases. Those were re-measured on the same aggressive warm-up and
 * still do not move. They have a different problem -- one alias name denoting
 * two distinct classes -- which pinning would not have fixed; see
 * {@code JadxAnalyzerCore.ambiguousClassNames}.
 */
public final class PinnedFieldNames {

    /** Field to the alias it had at load; see the class comment on the key. */
    private final Map<FieldNode, String> aliasAtLoad;

    private final long buildNanos;

    private PinnedFieldNames(Map<FieldNode, String> aliasAtLoad, long buildNanos) {
        this.aliasAtLoad = aliasAtLoad;
        this.buildNanos = buildNanos;
    }

    /**
     * Snapshot every field alias in the tree.
     *
     * Reads {@code ClassNode.getFields()} and {@code FieldInfo.getAlias()},
     * neither of which decompiles anything. Must be called while the tree is
     * pristine, which in practice means from loadApk() before anything answers
     * a query -- a map built lazily on first use would pin whatever the tree
     * had already drifted to.
     */
    public static PinnedFieldNames pin(RootNode root) {
        long start = System.nanoTime();
        Map<FieldNode, String> map = new HashMap<>(1 << 16);
        for (ClassNode cls : root.getClasses()) {
            for (FieldNode fld : cls.getFields()) {
                map.put(fld, fld.getFieldInfo().getAlias());
            }
        }
        return new PinnedFieldNames(map, System.nanoTime() - start);
    }

    /**
     * The pinned alias, e.g. {@code f2385a} rather than the {@code NONE} that
     * decompiling the enum turns it into.
     *
     * Falls back to the live alias for a field the snapshot never saw, which
     * degrades to exactly the old behaviour rather than to a null.
     */
    public String aliasOf(FieldNode fld) {
        String pinned = aliasAtLoad.get(fld);
        return pinned != null ? pinned : fld.getFieldInfo().getAlias();
    }

    /**
     * Was this field in the dex when the APK was loaded?
     *
     * Decompiling can ADD FieldNodes: EnumVisitor synthesises constants for an
     * enum whose {@code <clinit>} it has just read, so
     * {@code ClassNode.getFields()} grows. Measured on a large production APK, decompiling
     * every enum: NetworkConnectionInfo$MobileSubtype went from 24 fields to 26,
     * the two new ones aliased to names two existing constants already had, so
     * the listing gained a duplicate.
     *
     * Those fields are jadx's model of the enum, not the dex's field table, and
     * this server reports the dex. A field the snapshot never saw did not exist
     * at load, and the whole point of pinning at load is that the answer does
     * not depend on what has run since.
     */
    public boolean wasDeclaredAtLoad(FieldNode fld) {
        return aliasAtLoad.containsKey(fld);
    }

    /** True if this field's live alias has moved off its pinned one. */
    public boolean hasDrifted(FieldNode fld) {
        String pinned = aliasAtLoad.get(fld);
        return pinned != null && !pinned.equals(fld.getFieldInfo().getAlias());
    }

    public int size() {
        return aliasAtLoad.size();
    }

    /** For the load report, so the cost is visible rather than folded in. */
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("pinnedFieldNames", aliasAtLoad.size());
        s.put("buildMs", buildNanos / 1_000_000);
        return s;
    }
}

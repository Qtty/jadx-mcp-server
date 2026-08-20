package com.example.jadxmcpserver.core;

import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The name a method had when the APK was loaded, kept for as long as it stays
 * loaded.
 *
 * <h2>Why a method needs a name pinned at all</h2>
 *
 * jadx's alias for a method is not fixed. {@code ClassModifier} removes a
 * synthetic bridge while its class decompiles and hands the freed name to the
 * method behind it, so {@code onCanceled2} becomes {@code onCanceled} and
 * {@code invoke2} becomes {@code invoke}. Nothing about the dex changed; the
 * decompiler simply has less to disambiguate against once the bridge is gone.
 *
 * Measured, decompiling classes and re-reading every alias:
 *
 * <pre>
 *   DivaApplication.apk   18 of  14,809 methods, all 1,791 classes decompiled
 *   a large production APK             230 of 872,119 methods, only 3,000 of 141,461 done
 * </pre>
 *
 * 0.03% sounds ignorable and is not, because of who pays. the analysis client
 * records a method name in one phase and asks about it in a later one, against
 * a long-lived daemon another session has warmed in between. A name that moved
 * is not a wrong answer, it is a missing one: the later phase gets "method not
 * found" for a method that is right there, and a recorded finding stops
 * resolving. The rate is the chance any one recorded name rots; the workflow
 * records thousands.
 *
 * <h2>Why pinning is the right fix rather than leniency</h2>
 *
 * Accepting every historical spelling on input is strictly easier, and it does
 * not solve this. What has to survive is what gets RECORDED, and a workspace
 * records whatever the server reported at the time. Leniency makes old
 * recordings keep working; it does nothing to stop two sessions recording two
 * different names for one method. Pinning fixes the recording, and input
 * leniency is kept anyway for the recordings already on disk -- see
 * {@code JadxAnalyzerCore.resolveMethods}, which accepts the pinned name, the
 * current one and the raw dex name.
 *
 * The alternative of identifying methods by raw dex name plus signature is
 * stable too, and it was rejected for a specific reason: it stops matching
 * what an agent READS in get_class_source output, which is the whole reason
 * these tools became alias-aware in the first place.
 *
 * <h2>Why pinning at LOAD makes a name portable, not merely stable</h2>
 *
 * The alias-stability gate (AliasStabilityTest) established that jadx's
 * assignment is deterministic while the tree is pristine: 141,461 class,
 * 872,119 method and 860,163 field names identical across two loads in one
 * JVM, across two JVMs, and across jadx 1.5.5 and 1.5.6 -- zero changed. So a
 * name captured from a pristine tree is not just stable inside one daemon; the
 * next daemon, and the next jadx version, compute the same one. That is what
 * makes a name recorded last week still resolve today, and it is a property
 * only the load-time snapshot has. A map built lazily on first use would pin
 * whatever the tree had already drifted to.
 *
 * <h2>Cost</h2>
 *
 * Measured on a large production APK: 872,119 entries, <b>42.4 MB and 0.106 s</b>. For
 * comparison the dex reverse index this server already builds is 88 MB and
 * 3.1 s. Nothing is copied -- the values are the very String objects
 * {@link MethodInfo} already holds -- so the cost is the map structure alone.
 *
 * <h2>Why MethodNode is a safe key</h2>
 *
 * {@code MethodInfo.hashCode()} returns a {@code private final int} computed at
 * construction, and {@code equals} compares {@code shortId} (the RAW name plus
 * descriptor) and the declaring class. Neither reads the alias, and
 * {@code setAlias} writes only the alias field. So an entry stays findable
 * after the very drift this class exists to record. {@link DexReverseIndex}
 * already relies on the same property.
 *
 * <h2>What is deliberately NOT pinned</h2>
 *
 * Class aliases, and only class aliases. Re-measured with every enum in
 * a large production APK decompiled and 12,000 further top-level classes after that: 0 of
 * 141,461 moved, as did 0 of 1,791 on DivaApplication.apk with the whole APK
 * decompiled. Pinning them would cost memory to fix nothing.
 *
 * Class names do have a problem, and it is a different one that pinning would
 * not have touched: {@code getAliasFullName()} joins an inner class to its
 * outer one with the same '.' that separates a package from a class, so 27
 * names on a large production APK each denote two distinct dex classes. That is fixed by
 * reporting the '$'-separated spelling for exactly those names; see
 * {@code JadxAnalyzerCore.ambiguousClassNames}.
 *
 * <h2>Correction: fields ARE pinned, and this comment used to say otherwise</h2>
 *
 * The first version of this class stated that field aliases had been measured
 * over the same 3,000-class warm-up and had not moved, and gave that as the
 * reason not to pin them. The measurement was real and the conclusion was
 * wrong. {@code EnumVisitor} renames enum constant fields during decompilation
 * and it is the only pass that renames a field at all, so all the drift there
 * is lives in enums -- and a prefix of {@code getClasses()} on that APK
 * contains almost none. Warming every enum first instead finds 1,958 of 861,374
 * fields drifting, eight times the method rate that justified this class.
 *
 * The lesson is about the sample, not about fields: a warm-up drawn from tree
 * order tests whatever happens to sit at the front of the tree. See
 * {@link PinnedFieldNames}, which now does for fields what this does for
 * methods.
 */
public final class PinnedMethodNames {

    /**
     * Method to the alias it had at load. Not an IdentityHashMap: MethodNode
     * hashes on immutable state (see the class comment), and a plain HashMap is
     * both smaller and faster here.
     */
    private final Map<MethodNode, String> aliasAtLoad;

    private final long buildNanos;

    private PinnedMethodNames(Map<MethodNode, String> aliasAtLoad, long buildNanos) {
        this.aliasAtLoad = aliasAtLoad;
        this.buildNanos = buildNanos;
    }

    /**
     * Snapshot every method alias in the tree.
     *
     * Reads {@code ClassNode.getMethods()} and {@code MethodInfo.getAlias()},
     * neither of which decompiles anything -- the same dex-tree-only discipline
     * searchMethodByName follows. Must be called while the tree is pristine,
     * which in practice means from loadApk() before anything answers a query.
     */
    public static PinnedMethodNames pin(RootNode root) {
        long start = System.nanoTime();
        Map<MethodNode, String> map = new HashMap<>(1 << 16);
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                map.put(mth, mth.getMethodInfo().getAlias());
            }
        }
        return new PinnedMethodNames(map, System.nanoTime() - start);
    }

    /**
     * The pinned bare alias, e.g. {@code onCanceled2}.
     *
     * Falls back to the live alias for a method the snapshot never saw. That is
     * not expected -- no measurement has ever produced a method that appeared
     * after load -- but a name is always better than a null, and the fallback
     * degrades to exactly the old behaviour.
     */
    public String aliasOf(MethodNode mth) {
        String pinned = aliasAtLoad.get(mth);
        return pinned != null ? pinned : mth.getMethodInfo().getAlias();
    }

    /**
     * The pinned name qualified by its declaring class, e.g.
     * {@code android.support.v4.content.CursorLoader.onCanceled2}.
     *
     * The class half is read live because class aliases were measured not to
     * drift; see the class comment.
     */
    public String fullNameOf(MethodNode mth) {
        return mth.getMethodInfo().getDeclClass().getAliasFullName() + "." + aliasOf(mth);
    }

    /**
     * The pinned name with its argument and return types, the form every xref
     * and callee entry reports as "signature", e.g.
     * {@code onCanceled2(Landroid/database/Cursor;)V}.
     *
     * Built by swapping the pinned alias in front of the descriptor rather than
     * by calling {@code makeSignature(true, true)}, which would use the live
     * alias and drift with it. The descriptor is the part of {@code getShortId()}
     * after the raw name, and descriptors contain no method name, so the split
     * is exact.
     */
    public String signatureOf(MethodNode mth) {
        MethodInfo info = mth.getMethodInfo();
        return aliasOf(mth) + descriptorOf(info);
    }

    /**
     * The {@code (args)ret} tail of a shortId -- everything the name is not.
     */
    static String descriptorOf(MethodInfo info) {
        String shortId = info.getShortId();
        int paren = shortId.indexOf('(');
        return paren < 0 ? shortId : shortId.substring(paren);
    }

    /** True if this method's live alias has moved off its pinned one. */
    public boolean hasDrifted(MethodNode mth) {
        String pinned = aliasAtLoad.get(mth);
        return pinned != null && !pinned.equals(mth.getMethodInfo().getAlias());
    }

    public int size() {
        return aliasAtLoad.size();
    }

    /** For the load report, so the cost is visible rather than folded in. */
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("pinnedMethodNames", aliasAtLoad.size());
        s.put("buildMs", buildNanos / 1_000_000);
        return s;
    }
}

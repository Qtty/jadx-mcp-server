package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY, and it needs the LARGE APK.
 *
 * Decompiling an enum makes {@code EnumVisitor} synthesise FieldNodes for the
 * constants it recovers out of the {@code <clinit>}, so
 * {@code ClassNode.getFields()} grows and the extras alias to names existing
 * constants already carry. get_fields_of_class has filtered those out since
 * commit 339b54d; the RESOLVER behind get_xrefs_to_field did not, so a name the
 * server itself emitted became {@code @ambiguous} once the class decompiled --
 * with two candidates printing the identical type and the identical name, so
 * the notice's own advice could not be followed.
 *
 * The corresponding CI test lives in FieldIdentityTest, but it is an invariant
 * assertion there rather than a repro: DivaApplication.apk declares one enum and
 * NO class's field list grows on it. This is the repro, and it needs an APK with
 * thousands of enums:
 *
 * <pre>
 *   a large production APK   2,517 enums, 2 classes grow
 *               NetworkConnectionInfo$MobileSubtype  24 -&gt; 26 fields
 *               NetworkConnectionInfo$NetworkType    22 -&gt; 24 fields
 *               19,750 emitted entries fed back: 4 unactionable @ambiguous
 *               notices before the fix, 0 after
 * </pre>
 *
 * Run: java -cp target/classes:target/test-classes:$(deps) \
 *          com.example.jadxmcpserver.core.EnumFieldPopulationProbe &lt;apk&gt;
 */
public final class EnumFieldPopulationProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        RootNode root = ClassSourceStabilityProbe.root(core);

        Map<ClassNode, Integer> before = new IdentityHashMap<>();
        List<ClassNode> enums = new ArrayList<>();
        for (ClassNode c : root.getClasses()) {
            before.put(c, c.getFields().size());
            if (c.getAccessFlags().isEnum()) {
                enums.add(c);
            }
        }
        System.out.println("enum classes: " + enums.size());

        for (ClassNode c : enums) {
            try {
                core.getClassSource(c.getTopParentClass().getClassInfo().getAliasFullName());
            } catch (Throwable ignored) {
                // a class that will not decompile still counts as warmed
            }
        }

        int grown = 0, added = 0;
        for (ClassNode c : root.getClasses()) {
            int now = c.getFields().size();
            if (now != before.get(c)) {
                grown++;
                added += now - before.get(c);
                System.out.println("  grew: " + c.getClassInfo().getAliasFullName()
                        + " " + before.get(c) + " -> " + now);
            }
        }
        System.out.println("classes whose field list grew: " + grown
                + " (+" + added + " FieldNodes)");

        int checked = 0, unactionable = 0;
        for (ClassNode c : enums) {
            String cn = c.getClassInfo().getAliasFullName();
            for (String entry : core.getFieldsOfClass(cn)) {
                if (entry.startsWith("@")) {
                    continue;
                }
                checked++;
                try {
                    for (Map<String, String> ref : core.getXrefsToField(cn, entry, 5)) {
                        String notice = ref.get(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
                        if (notice != null) {
                            unactionable++;
                            if (unactionable <= 5) {
                                System.out.println("  AMBIGUOUS " + cn + " | " + entry);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    System.out.println("  REJECTED " + cn + " | " + entry
                            + " :: " + e.getMessage());
                }
            }
        }
        System.out.printf("enum field entries emitted and fed back: %d, @ambiguous: %d%n",
                checked, unactionable);
    }
}

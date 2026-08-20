package com.example.jadxmcpserver.core;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Does the dex-derived reverse index answer what jadx answers COLD?
 *
 * The point of DexReverseIndex is to be stable, and a stable wrong answer is
 * worse than an unstable one. So the bar is not "it does not move" but "it does
 * not move AND it agrees with the pristine usage graph everywhere". This is the
 * xref counterpart of CalleeDexEquivalenceProbe, which held getCallees to the
 * same bar and found 0 mismatches over 872,119 methods.
 *
 * Nothing is decompiled before the comparison: jadx's graph is only truthful
 * while it is untouched, which is the whole bug.
 *
 * Run by hand, not a test.
 */
public final class XrefDexEquivalenceProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s   apk=%s%n", (System.nanoTime() - t0) / 1e9, apk);

        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.api.JadxDecompiler jadx = (jadx.api.JadxDecompiler) f.get(core);
        RootNode root = jadx.getRoot();

        long t1 = System.nanoTime();
        DexReverseIndex index = DexReverseIndex.build(root);
        System.out.printf("build: %.2f s  %s%n", (System.nanoTime() - t1) / 1e9, index.stats());

        Cmp mth = new Cmp("MethodNode.getUseIn");
        Cmp fld = new Cmp("FieldNode.getUseIn");
        Cmp clsMth = new Cmp("ClassNode.getUseInMth");
        Cmp cls = new Cmp("ClassNode.getUseIn");

        for (ClassNode c : root.getClasses()) {
            clsMth.compare(c.getClassInfo().getFullName(),
                    mthIds(c.getUseInMth()), mthIds(index.methodUsersOf(c)));
            cls.compare(c.getClassInfo().getFullName(),
                    clsIds(c.getUseIn()), clsIds(index.classUsersOf(c)));
            for (MethodNode m : c.getMethods()) {
                mth.compare(m.getMethodInfo().getFullId(),
                        mthIds(m.getUseIn()), mthIds(index.callersOf(m)));
            }
            for (FieldNode fn : c.getFields()) {
                fld.compare(fn.getFieldInfo().getFullId(),
                        mthIds(fn.getUseIn()), mthIds(index.usersOf(fn)));
            }
        }

        mth.report();
        fld.report();
        clsMth.report();
        cls.report();

        // The only expected divergence: ProcessAnonymous.markAnonymousClass runs
        // during load() and safeRemove()s an anonymous class from its outer
        // class's useIn, because the anonymous class will be printed inline.
        // The dex still says the reference is there. Classify every extra.
        int anonExtras = 0;
        int otherExtras = 0;
        List<String> others = new ArrayList<>();
        for (ClassNode c : root.getClasses()) {
            Set<String> fromJadx = clsIds(c.getUseIn());
            for (ClassNode user : index.classUsersOf(c)) {
                if (fromJadx.contains(user.getClassInfo().getFullName())) {
                    continue;
                }
                if (user.contains(AType.ANONYMOUS_CLASS)) {
                    anonExtras++;
                } else {
                    otherExtras++;
                    if (others.size() < 10) {
                        others.add(c.getClassInfo().getFullName() + " <- "
                                + user.getClassInfo().getFullName());
                    }
                }
            }
        }
        System.out.println("ClassNode.getUseIn extras: anonymousClass=" + anonExtras
                + " other=" + otherExtras);
        for (String o : others) {
            System.out.println("        other extra: " + o);
        }
    }

    private static Set<String> mthIds(List<MethodNode> l) {
        Set<String> ids = new HashSet<>();
        for (MethodNode m : l) {
            ids.add(m.getMethodInfo().getFullId());
        }
        return ids;
    }

    private static Set<String> clsIds(List<ClassNode> l) {
        Set<String> ids = new HashSet<>();
        for (ClassNode c : l) {
            ids.add(c.getClassInfo().getFullName());
        }
        return ids;
    }

    private static final class Cmp {
        final String label;
        int keys;
        int mismatches;
        long jadxOnly;
        long indexOnly;
        final List<String> samples = new ArrayList<>();

        Cmp(String label) {
            this.label = label;
        }

        void compare(String key, Set<String> fromJadx, Set<String> fromIndex) {
            keys++;
            if (fromJadx.equals(fromIndex)) {
                return;
            }
            mismatches++;
            List<String> missing = new ArrayList<>();
            List<String> extra = new ArrayList<>();
            for (String s : fromJadx) {
                if (!fromIndex.contains(s)) {
                    missing.add(s);
                }
            }
            for (String s : fromIndex) {
                if (!fromJadx.contains(s)) {
                    extra.add(s);
                }
            }
            jadxOnly += missing.size();
            indexOnly += extra.size();
            if (samples.size() < 8) {
                samples.add(key + "\n            jadx-only : "
                        + (missing.isEmpty() ? "-" : missing.get(0))
                        + "\n            index-only: " + (extra.isEmpty() ? "-" : extra.get(0)));
            }
        }

        void report() {
            System.out.printf("%-24s keys=%d mismatches=%d jadxOnlyEdges=%d indexOnlyEdges=%d %s%n",
                    label, keys, mismatches, jadxOnly, indexOnly,
                    mismatches == 0 ? "IDENTICAL" : "DIFFERS");
            for (String s : samples) {
                System.out.println("        " + s);
            }
        }
    }
}

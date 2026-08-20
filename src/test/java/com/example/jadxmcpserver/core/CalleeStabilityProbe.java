package com.example.jadxmcpserver.core;

import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does decompiling a class change what get_callees answers for it?
 *
 * Run by hand, not a test. Sweeps every method's (used, unresolvedUsed) counts
 * on a pristine tree, decompiles every class, sweeps again and diffs.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.CalleeStabilityProbe
 *        -Dexec.args="<apk> [maxClassesToDecompile]"
 */
public final class CalleeStabilityProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int maxDecompile = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s%n", (System.nanoTime() - t0) / 1e9);

        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.api.JadxDecompiler jadx = (jadx.api.JadxDecompiler) f.get(core);

        List<ClassNode> classes = jadx.getRoot().getClasses();
        System.out.printf("classes: %d%n", classes.size());

        Map<String, String> coldAliases = aliases(classes);

        long t1 = System.nanoTime();
        Map<String, int[]> cold = sweep(classes);
        System.out.printf("cold sweep: %d methods, %.0f ms%n",
                cold.size(), (System.nanoTime() - t1) / 1e6);

        int n = 0;
        long t2 = System.nanoTime();
        for (JavaClass jc : jadx.getClasses()) {
            if (n >= maxDecompile) {
                break;
            }
            try {
                jc.getCode();
            } catch (Throwable t) {
                // a class that fails to decompile still counts as "touched"
            }
            n++;
        }
        System.out.printf("decompiled %d top-level classes: %.1f s%n",
                n, (System.nanoTime() - t2) / 1e9);

        Map<String, int[]> warm = sweep(classes);

        report("callees   (getUsed)", cold, warm, 0);
        report("external  (getUnresolvedUsed)", cold, warm, 1);
        report("xrefs-to  (getUseIn)", cold, warm, 2);

        // The names those edges are reported under are a contract too: an agent
        // feeds a callee's className/methodName straight back into the next call.
        Map<String, String> warmAliases = aliases(classes);
        List<String> renamed = new ArrayList<>();
        for (Map.Entry<String, String> e : coldAliases.entrySet()) {
            String w = warmAliases.get(e.getKey());
            if (w != null && !w.equals(e.getValue())) {
                renamed.add(e.getValue() + "  ->  " + w);
            }
        }
        System.out.printf("%n### aliases : %d of %d method names changed%n",
                renamed.size(), coldAliases.size());
        renamed.stream().limit(20).forEach(s -> System.out.println("  ~ " + s));
    }

    private static void report(String label, Map<String, int[]> cold, Map<String, int[]> warm, int idx) {
        List<String> lost = new ArrayList<>();
        List<String> gained = new ArrayList<>();
        int lostEdges = 0;
        for (Map.Entry<String, int[]> e : cold.entrySet()) {
            int c = e.getValue()[idx];
            int[] w = warm.get(e.getKey());
            int wv = w == null ? 0 : w[idx];
            if (c > wv) {
                lost.add(e.getKey() + "  cold=" + c + "  warm=" + wv);
                lostEdges += c - wv;
            } else if (c < wv) {
                gained.add(e.getKey() + "  cold=" + c + "  warm=" + wv);
            }
        }
        System.out.printf("%n### %s : %d methods shrank (%d edges lost), %d grew%n",
                label, lost.size(), lostEdges, gained.size());
        lost.stream().limit(15).forEach(s -> System.out.println("  - " + s));
        gained.stream().limit(15).forEach(s -> System.out.println("  + " + s));
    }

    /** Raw dex identity -> the alias the tools would report it under. */
    private static Map<String, String> aliases(List<ClassNode> classes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ClassNode cls : classes) {
            for (MethodNode mth : cls.getMethods()) {
                out.put(rawKey(cls, mth),
                        cls.getClassInfo().getAliasFullName() + "." + mth.getMethodInfo().getAlias());
            }
        }
        return out;
    }

    /** methodKey -> {used, unresolvedUsed, useIn}. Dex-tree only, no decompilation. */
    private static Map<String, int[]> sweep(List<ClassNode> classes) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (ClassNode cls : classes) {
            for (MethodNode mth : cls.getMethods()) {
                out.put(rawKey(cls, mth),
                        new int[] { mth.getUsed().size(), mth.getUnresolvedUsed().size(),
                                mth.getUseIn().size() });
            }
        }
        return out;
    }

    /** Keyed on raw dex names, which deobfuscation cannot move under us. */
    private static String rawKey(ClassNode cls, MethodNode mth) {
        return cls.getClassInfo().getFullName() + "#" + mth.getMethodInfo().getShortId();
    }
}

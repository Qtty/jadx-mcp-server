package com.example.jadxmcpserver.core;

import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ground truth, not self-consistency.
 *
 * XrefDexEquivalenceProbe shows the index agrees with jadx's pristine graph, and
 * XrefStabilityTest shows it stops moving. Neither would notice if both were
 * wrong in the same way, and a stable wrong answer is worse than an unstable
 * one -- so this one reads the smali, which is the dex spelled out, and rebuilds
 * the caller set from the {@code invoke-*} lines directly.
 *
 * The comparison is exact in both directions:
 *   index-only  a caller the index invented -- the smali has no such invoke
 *   smali-only  a caller the index dropped -- a false negative, the expensive
 *               direction for a tracer
 *
 * Not run for the whole APK: dumping smali costs a class disassembly each, and
 * the point is a checkable sample, not another sweep.
 *
 * Usage: java -cp target/classes:target/test-classes:$CP \
 *        com.example.jadxmcpserver.core.XrefSmaliGroundTruthProbe [apk] [sample]
 */
public final class XrefSmaliGroundTruthProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int sample = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.api.JadxDecompiler decompiler = (jadx.api.JadxDecompiler) f.get(core);
        RootNode root = decompiler.getRoot();
        DexReverseIndex index = DexReverseIndex.build(root);

        // The whole APK's invoke lines, keyed by the target the dex names.
        // Built once so the sample can be as wide as we like.
        System.out.println("reading smali of " + root.getClasses().size() + " classes ...");
        long t0 = System.currentTimeMillis();
        Map<String, Set<String>> callersFromSmali = new HashMap<>();
        int classesRead = 0;
        for (JavaClass jc : decompiler.getClasses()) {
            String smali;
            try {
                smali = jc.getSmali();
            } catch (Exception e) {
                continue;
            }
            if (smali == null) {
                continue;
            }
            classesRead++;
            collectInvokes(smali, callersFromSmali);
        }
        System.out.println("read " + classesRead + " top-level classes in "
                + (System.currentTimeMillis() - t0) + " ms, "
                + callersFromSmali.size() + " distinct invoke targets");

        // Sample targets that actually have callers, spread across the APK.
        List<MethodNode> targets = new ArrayList<>();
        List<ClassNode> classes = root.getClasses();
        int step = Math.max(1, classes.size() / (sample * 4));
        for (int i = 0; i < classes.size() && targets.size() < sample; i += step) {
            for (MethodNode m : classes.get(i).getMethods()) {
                if (!index.callersOf(m).isEmpty()) {
                    targets.add(m);
                    break;
                }
            }
        }
        System.out.println("comparing " + targets.size() + " target methods\n");

        int agree = 0;
        long indexOnly = 0;
        long smaliOnly = 0;
        for (MethodNode target : targets) {
            String key = smaliKey(target);
            Set<String> fromSmali = callersFromSmali.getOrDefault(key, Set.of());
            Set<String> fromIndex = new LinkedHashSet<>();
            for (MethodNode caller : index.callersOf(target)) {
                fromIndex.add(caller.getParentClass().getClassInfo().getRawName());
            }
            // The smali key is class-granular: a .method block names its owner,
            // and mapping an invoke back to the exact enclosing method means
            // re-parsing block boundaries. Class granularity is enough to catch
            // an invented or a dropped caller, which is the question.
            Set<String> missing = new LinkedHashSet<>(fromIndex);
            missing.removeAll(fromSmali);
            Set<String> extra = new LinkedHashSet<>(fromSmali);
            extra.removeAll(fromIndex);
            if (missing.isEmpty() && extra.isEmpty()) {
                agree++;
                continue;
            }
            indexOnly += missing.size();
            smaliOnly += extra.size();
            System.out.println("DIFF " + key);
            System.out.println("   index-only (no such invoke in smali): " + missing);
            System.out.println("   smali-only (caller the index dropped): " + extra);
        }

        System.out.println("\ntargets=" + targets.size() + " agree=" + agree
                + " indexOnlyCallerClasses=" + indexOnly
                + " smaliOnlyCallerClasses=" + smaliOnly
                + (agree == targets.size() ? "   GROUND TRUTH MATCHES" : "   MISMATCH"));
    }

    /** {@code Lcom/x/Y;->m(I)V} as the smali writes it. */
    private static String smaliKey(MethodNode mth) {
        return "L" + mth.getParentClass().getClassInfo().getRawName().replace('.', '/')
                + ";->" + mth.getMethodInfo().getShortId();
    }

    /**
     * Every {@code invoke-*} in a class dump, filed under its target, with the
     * enclosing CLASS recorded as the caller.
     *
     * A class dump carries its inner classes, so the ".class" line is tracked as
     * it moves rather than read once.
     */
    private static void collectInvokes(String smali, Map<String, Set<String>> out) {
        String currentClass = null;
        for (String raw : smali.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(".class ")) {
                // The descriptor is the last token: ".class public final Lcom/x/Y;".
                // Not lastIndexOf('L') -- that lands inside "...LinearLayout".
                String token = line.substring(line.lastIndexOf(' ') + 1);
                if (token.startsWith("L") && token.endsWith(";")) {
                    currentClass = token.substring(1, token.length() - 1).replace('/', '.');
                }
                continue;
            }
            if (!line.startsWith("invoke-") || currentClass == null) {
                continue;
            }
            int arrow = line.indexOf("}, ");
            if (arrow < 0) {
                continue;
            }
            String target = line.substring(arrow + 3).trim();
            int comment = target.indexOf(" //");
            if (comment > 0) {
                target = target.substring(0, comment).trim();
            }
            out.computeIfAbsent(target, k -> new HashSet<>()).add(currentClass);
        }
    }
}

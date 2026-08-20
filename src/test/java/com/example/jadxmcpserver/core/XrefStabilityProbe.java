package com.example.jadxmcpserver.core;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much does decompilation move the REVERSE edges -- the ones the three
 * get_xrefs_to_* tools read?
 *
 * The forward direction was measured by CalleeStabilityProbe and fixed in
 * 0783597. This one asks the same question of MethodNode.getUseIn(),
 * FieldNode.getUseIn(), ClassNode.getUseIn() and ClassNode.getUseInMth(), which
 * is what the xref tools and findSinkCallSites' in-APK override resolution read.
 *
 * Run by hand, not a test.
 *
 * Usage: java -cp target/classes:target/test-classes:$CP \
 *          com.example.jadxmcpserver.core.XrefStabilityProbe <apk> [maxDecompile]
 */
public final class XrefStabilityProbe {

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
        System.out.printf("load: %.1f s   apk=%s%n", (System.nanoTime() - t0) / 1e9, apk);

        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.api.JadxDecompiler jadx = (jadx.api.JadxDecompiler) f.get(core);
        RootNode root = jadx.getRoot();
        List<ClassNode> classes = root.getClasses();
        System.out.println("classes=" + classes.size());

        Snapshot cold = snapshot(classes);
        System.out.println("cold: " + cold);

        int n = Math.min(maxDecompile, classes.size());
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try {
                classes.get(i).decompile();
            } catch (Throwable ignored) {
                // a class that fails to decompile still counts as "the daemon touched it"
            }
        }
        System.out.printf("decompiled %d classes in %.1f s%n", n, (System.nanoTime() - t1) / 1e9);

        Snapshot warm = snapshot(classes);
        System.out.println("warm: " + warm);

        diff("MethodNode.getUseIn  (get_xrefs_to_method)", cold.mthUseIn, warm.mthUseIn);
        diff("FieldNode.getUseIn   (get_xrefs_to_field)", cold.fldUseIn, warm.fldUseIn);
        diff("ClassNode.getUseInMth(get_xrefs_to_class)", cold.clsUseInMth, warm.clsUseInMth);
        diff("ClassNode.getUseIn   (get_xrefs_to_class)", cold.clsUseIn, warm.clsUseIn);
    }

    private static final class Snapshot {
        final Map<String, Set<String>> mthUseIn = new HashMap<>();
        final Map<String, Set<String>> fldUseIn = new HashMap<>();
        final Map<String, Set<String>> clsUseInMth = new HashMap<>();
        final Map<String, Set<String>> clsUseIn = new HashMap<>();

        @Override
        public String toString() {
            return "mthEdges=" + edges(mthUseIn) + " fldEdges=" + edges(fldUseIn)
                    + " clsMthEdges=" + edges(clsUseInMth) + " clsEdges=" + edges(clsUseIn);
        }

        private static long edges(Map<String, Set<String>> m) {
            long total = 0;
            for (Set<String> s : m.values()) {
                total += s.size();
            }
            return total;
        }
    }

    private static Snapshot snapshot(List<ClassNode> classes) {
        Snapshot s = new Snapshot();
        for (ClassNode cls : classes) {
            String clsKey = cls.getClassInfo().getFullName();
            s.clsUseInMth.put(clsKey, mthNames(cls.getUseInMth()));
            Set<String> users = new HashSet<>();
            for (ClassNode c : cls.getUseIn()) {
                users.add(c.getClassInfo().getFullName());
            }
            s.clsUseIn.put(clsKey, users);
            for (MethodNode mth : cls.getMethods()) {
                s.mthUseIn.put(mth.getMethodInfo().getFullId(), mthNames(mth.getUseIn()));
            }
            for (FieldNode fld : cls.getFields()) {
                s.fldUseIn.put(fld.getFieldInfo().getFullId(), mthNames(fld.getUseIn()));
            }
        }
        return s;
    }

    private static Set<String> mthNames(List<MethodNode> mths) {
        Set<String> names = new HashSet<>();
        for (MethodNode m : mths) {
            names.add(m.getMethodInfo().getFullId());
        }
        return names;
    }

    private static void diff(String label, Map<String, Set<String>> cold,
            Map<String, Set<String>> warm) {
        int movedKeys = 0;
        long lost = 0;
        long gained = 0;
        int emptied = 0;
        List<String> worst = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : cold.entrySet()) {
            Set<String> before = e.getValue();
            Set<String> after = warm.getOrDefault(e.getKey(), Set.of());
            if (before.equals(after)) {
                continue;
            }
            movedKeys++;
            long l = before.stream().filter(x -> !after.contains(x)).count();
            long g = after.stream().filter(x -> !before.contains(x)).count();
            lost += l;
            gained += g;
            if (!before.isEmpty() && after.isEmpty()) {
                emptied++;
            }
            if (worst.size() < 5 && l > 0) {
                worst.add(e.getKey() + " (" + before.size() + " -> " + after.size() + ")");
            }
        }
        System.out.printf("%-45s moved=%d lost=%d gained=%d emptied=%d%n",
                label, movedKeys, lost, gained, emptied);
        for (String w : worst) {
            System.out.println("        e.g. " + w);
        }
    }
}

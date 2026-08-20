package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEASUREMENT ONLY. Two questions the pin design turns on:
 *
 *   1. what does a pinned alias map COST -- the brief says measure it, not
 *      assume it
 *   2. do FIELD aliases collide too? getXrefsToField resolves a field by alias
 *      and takes the FIRST match, so a collision there is not a silent union
 *      like the method case, it is a silent WRONG PICK -- which is exactly the
 *      reported consequence ("a field read attributed to a method whose
 *      bytecode never touches that field")
 *
 *   java -cp ... com.example.jadxmcpserver.core.PinCostProbe <apk> <warmClasses>
 */
public class PinCostProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int warmCount = args.length > 1 ? Integer.parseInt(args[1]) : 3000;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        System.out.println("\n=== PinCostProbe: " + apk + " ===");

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        RootNode root = ((JadxDecompiler) f.get(core)).getRoot();

        List<ClassNode> all = new ArrayList<>(root.getClasses());
        System.out.println("classes: " + all.size());

        // ---------------- 1. cost of pinning ----------------
        long before = usedHeap();
        Map<MethodNode, String> pinned = new HashMap<>(1 << 20);
        for (ClassNode cls : all) {
            for (MethodNode m : cls.getMethods()) {
                pinned.put(m, m.getMethodInfo().getAlias());
            }
        }
        long after = usedHeap();
        System.out.println("pinned method aliases : " + pinned.size());
        System.out.println("HashMap<MethodNode,String> cost : "
                + fmt(after - before) + "  (" + ((after - before) / Math.max(1, pinned.size()))
                + " B/method)");

        // Alternative: one String[] per class, keyed by class. Same information,
        // no per-entry Node object.
        long before2 = usedHeap();
        Map<ClassNode, String[]> perClass = new HashMap<>(1 << 18);
        for (ClassNode cls : all) {
            List<MethodNode> ms = cls.getMethods();
            String[] arr = new String[ms.size()];
            for (int i = 0; i < ms.size(); i++) {
                arr[i] = ms.get(i).getMethodInfo().getAlias();
            }
            perClass.put(cls, arr);
        }
        long after2 = usedHeap();
        System.out.println("Map<ClassNode,String[]>    cost : " + fmt(after2 - before2));

        long t0 = System.nanoTime();
        Map<MethodNode, String> timed = new HashMap<>(1 << 20);
        for (ClassNode cls : all) {
            for (MethodNode m : cls.getMethods()) {
                timed.put(m, m.getMethodInfo().getAlias());
            }
        }
        System.out.println("build time            : "
                + String.format("%.3f s", (System.nanoTime() - t0) / 1e9));

        // ---------------- 2. field alias collisions ----------------
        fieldCollisions(root, "COLD");
        methodPinBijection(all, pinned, "COLD");

        int warmed = 0;
        for (ClassNode cls : all) {
            if (warmed >= warmCount) {
                break;
            }
            try {
                cls.decompile();
            } catch (Throwable ignored) {
                // still counts as warmed
            }
            warmed++;
        }
        System.out.println("\n>>> decompiled " + warmed + " classes <<<");
        fieldCollisions(root, "WARM");
        methodPinBijection(all, pinned, "WARM");

        // Does the PINNED map still name every method uniquely per class after
        // warming? That is the property the whole design rests on.
        int drifted = 0;
        for (ClassNode cls : all) {
            for (MethodNode m : cls.getMethods()) {
                String p = pinned.get(m);
                if (p != null && !p.equals(m.getMethodInfo().getAlias())) {
                    drifted++;
                }
            }
        }
        System.out.println("methods whose live alias drifted off the pin : " + drifted);
    }

    /** Collisions among PINNED names -- the property the fix depends on. */
    static void methodPinBijection(List<ClassNode> all, Map<MethodNode, String> pinned,
                                   String phase) {
        int collisionNames = 0;
        int overloadNames = 0;
        List<String> examples = new ArrayList<>();
        for (ClassNode cls : all) {
            Map<String, List<MethodNode>> byPin = new LinkedHashMap<>();
            for (MethodNode m : cls.getMethods()) {
                String p = pinned.get(m);
                if (p != null) {
                    byPin.computeIfAbsent(p, k -> new ArrayList<>()).add(m);
                }
            }
            for (Map.Entry<String, List<MethodNode>> e : byPin.entrySet()) {
                if (e.getValue().size() < 2) {
                    continue;
                }
                Set<String> raw = new LinkedHashSet<>();
                for (MethodNode m : e.getValue()) {
                    raw.add(m.getMethodInfo().getName());
                }
                if (raw.size() > 1) {
                    collisionNames++;
                    if (examples.size() < 6) {
                        examples.add(cls.getClassInfo().getAliasFullName() + "." + e.getKey()
                                + " raw=" + raw);
                    }
                } else {
                    overloadNames++;
                }
            }
        }
        System.out.println("PINNED-name collisions (" + phase + ") : " + collisionNames
                + "   pinned-name overloads: " + overloadNames);
        for (String e : examples) {
            System.out.println("    " + e);
        }
    }

    static void fieldCollisions(RootNode root, String phase) {
        int collidingNames = 0;
        int collidingClasses = 0;
        int overloadedNames = 0;
        List<String> examples = new ArrayList<>();

        for (ClassNode cls : root.getClasses()) {
            Map<String, List<FieldNode>> byAlias = new LinkedHashMap<>();
            for (FieldNode fld : cls.getFields()) {
                byAlias.computeIfAbsent(fld.getFieldInfo().getAlias(), k -> new ArrayList<>())
                        .add(fld);
            }
            boolean any = false;
            for (Map.Entry<String, List<FieldNode>> e : byAlias.entrySet()) {
                if (e.getValue().size() > 1) {
                    any = true;
                    collidingNames++;
                    Set<String> raw = new LinkedHashSet<>();
                    for (FieldNode fld : e.getValue()) {
                        raw.add(fld.getFieldInfo().getName() + ":" + fld.getType());
                    }
                    if (raw.size() > 1) {
                        overloadedNames++;
                    }
                    if (examples.size() < 10) {
                        examples.add(cls.getClassInfo().getAliasFullName() + "." + e.getKey()
                                + "  raw=" + raw);
                    }
                }
            }
            if (any) {
                collidingClasses++;
            }
        }
        System.out.println("FIELD alias collisions (" + phase + ") : names=" + collidingNames
                + " classes=" + collidingClasses + " distinctRaw=" + overloadedNames);
        for (String e : examples) {
            System.out.println("    " + e);
        }
    }

    static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return r.totalMemory() - r.freeMemory();
    }

    static String fmt(long bytes) {
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}

package com.example.jadxmcpserver.core;

import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Two questions the get_callees fix has to answer with numbers, both run by hand:
 *
 *   1. Does the dex-derived answer equal jadx's PRISTINE usage graph? The fix is
 *      only allowed to remove the order-dependence, not to change what a cold
 *      daemon replies. Compared per method over the whole APK, before anything
 *      is decompiled.
 *   2. What does it cost? The old implementation was two field reads. This one
 *      decodes a method's instructions on every call, so the per-call number is
 *      the thing that decides whether a sink tracer can still call it in a loop.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.CalleeDexEquivalenceProbe
 *        -Dexec.args="<apk> [maxMethodsToCompare]"
 */
public final class CalleeDexEquivalenceProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int maxMethods = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

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

        java.lang.reflect.Method scan = JadxAnalyzerCore.class.getDeclaredMethod(
                "scanCallees", MethodNode.class, List.class, List.class, boolean[].class);
        scan.setAccessible(true);

        int compared = 0;
        int mismatched = 0;
        int failed = 0;
        long insnNanos = 0;
        List<String> examples = new ArrayList<>();

        outer:
        for (ClassNode cls : jadx.getRoot().getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                if (compared >= maxMethods) {
                    break outer;
                }
                // Pristine usage graph, read first: getUsed() mutates on read,
                // but nothing has been decompiled so there is nothing to drop.
                TreeSet<String> graphInternal = new TreeSet<>();
                for (MethodNode c : mth.getUsed()) {
                    graphInternal.add(key(c));
                }
                TreeSet<String> graphExternal = new TreeSet<>();
                for (MethodInfo c : mth.getUnresolvedUsed()) {
                    graphExternal.add(key(c));
                }
                boolean graphSelf = mth.callsSelf();

                List<Map<String, String>> internal = new ArrayList<>();
                List<Map<String, String>> external = new ArrayList<>();
                boolean[] self = { false };
                long t = System.nanoTime();
                try {
                    scan.invoke(core, mth, internal, external, self);
                } catch (Exception e) {
                    failed++;
                    if (examples.size() < 20) {
                        examples.add("THREW  " + key(mth) + "  " + e.getCause());
                    }
                    compared++;
                    continue;
                }
                insnNanos += System.nanoTime() - t;

                TreeSet<String> dexInternal = new TreeSet<>();
                internal.forEach(e -> dexInternal.add(e.get("className") + "#" + e.get("signature")));
                TreeSet<String> dexExternal = new TreeSet<>();
                external.forEach(e -> dexExternal.add(e.get("className") + "#" + e.get("signature")));

                if (!dexInternal.equals(graphInternal) || !dexExternal.equals(graphExternal)
                        || self[0] != graphSelf) {
                    mismatched++;
                    if (examples.size() < 20) {
                        examples.add(diff(mth, graphInternal, dexInternal, graphExternal, dexExternal,
                                graphSelf, self[0]));
                    }
                }
                compared++;
            }
        }

        System.out.printf("%ncompared %d methods: %d mismatched, %d threw%n",
                compared, mismatched, failed);
        System.out.printf("dex scan: %.1f ms total, %.4f ms per method%n",
                insnNanos / 1e6, insnNanos / 1e6 / Math.max(1, compared));
        examples.forEach(System.out::println);
    }

    private static String diff(MethodNode mth, TreeSet<String> gi, TreeSet<String> di,
            TreeSet<String> ge, TreeSet<String> de, boolean gs, boolean ds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", key(mth));
        TreeSet<String> a = new TreeSet<>(gi);
        a.removeAll(di);
        TreeSet<String> b = new TreeSet<>(di);
        b.removeAll(gi);
        TreeSet<String> c = new TreeSet<>(ge);
        c.removeAll(de);
        TreeSet<String> d = new TreeSet<>(de);
        d.removeAll(ge);
        if (!a.isEmpty()) {
            m.put("graph-only callees", a);
        }
        if (!b.isEmpty()) {
            m.put("dex-only callees", b);
        }
        if (!c.isEmpty()) {
            m.put("graph-only external", c);
        }
        if (!d.isEmpty()) {
            m.put("dex-only external", d);
        }
        if (gs != ds) {
            m.put("callsSelf graph/dex", gs + "/" + ds);
        }
        return m.toString();
    }

    private static String key(MethodNode mth) {
        return mth.getParentClass().getClassInfo().getAliasFullName()
                + "#" + mth.getMethodInfo().getShortId();
    }

    private static String key(MethodInfo info) {
        return info.getDeclClass().getAliasFullName() + "#" + info.getShortId();
    }
}

package com.example.jadxmcpserver.core;

import jadx.core.clsp.ClspGraph;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/**
 * What closing the in-APK blind spot costs, and what it buys.
 *
 * Not a test -- a main() run by hand, same spirit as SinkSweepProbe. The sweep's
 * whole appeal is that it is a metadata scan an agent can afford to run cold, so
 * "resolve the in-APK implementations too" had to be shown not to turn a tenth
 * of a second into seconds.
 *
 * Column A replicates the loop as it stood before the change -- the unresolved
 * half only, same matcher set, built by reflecting the tool's own DEFAULT_SINKS
 * so it cannot drift from what ships. The two numbers are therefore the same
 * walk with and without the closure, in one JVM, rather than a comparison across
 * builds.
 *
 * Usage: mvn exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.SinkSweepCostProbe
 *        -Dexec.args="&lt;apk&gt;"
 */
public final class SinkSweepCostProbe {

    /** The sink's declaring class, and the tag it was asked for under. */
    private static final class Matcher {
        final String key;
        final String declClass;

        Matcher(String key, String declClass) {
            this.key = key;
            this.declClass = declClass;
        }
    }

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        jadx.api.JadxDecompiler decompiler = (jadx.api.JadxDecompiler) field(core, "jadx");
        List<ClassNode> classes = decompiler.getRoot().getClasses();
        ClspGraph clsp = decompiler.getRoot().getClsp();

        @SuppressWarnings("unchecked")
        Map<String, List<String>> catalogue =
                (Map<String, List<String>>) staticField("DEFAULT_SINKS");
        Map<String, List<Matcher>> byMethodName = new HashMap<>();
        for (Map.Entry<String, List<String>> e : catalogue.entrySet()) {
            for (String api : e.getValue()) {
                int dot = api.lastIndexOf('.');
                byMethodName.computeIfAbsent(api.substring(dot + 1), k -> new ArrayList<>())
                        .add(new Matcher(e.getKey(), api.substring(0, dot)));
            }
        }
        System.out.printf("%s: %d classes, %d tags, %d distinct sink method names%n",
                apk, classes.size(), catalogue.size(), byMethodName.size());

        int runs = 5;

        // --- A: the pre-change loop, unresolved half only --------------------
        long bestA = Long.MAX_VALUE;
        Set<String> foundA = new TreeSet<>();
        for (int i = 0; i < runs; i++) {
            Set<String> hits = new TreeSet<>();
            long t = System.nanoTime();
            for (ClassNode cls : classes) {
                for (MethodNode mth : cls.getMethods()) {
                    for (MethodInfo callee : mth.getUnresolvedUsed()) {
                        List<Matcher> matchers = byMethodName.get(callee.getName());
                        if (matchers == null) {
                            continue;
                        }
                        for (Matcher m : matchers) {
                            String recv = callee.getDeclClass().getFullName();
                            if (recv.equals(m.declClass) || clsp.isImplements(recv, m.declClass)) {
                                hits.add(m.key + " " + mth.getMethodInfo().getFullId());
                            }
                        }
                    }
                }
            }
            bestA = Math.min(bestA, (System.nanoTime() - t) / 1_000_000);
            foundA = hits;
        }
        System.out.printf("A. unresolved half only (pre-change): %4d ms   %d call sites%n",
                bestA, foundA.size());

        // --- B: what ships now ------------------------------------------------
        long bestB = Long.MAX_VALUE;
        Map<String, Object> sweep = null;
        for (int i = 0; i < runs; i++) {
            long t = System.nanoTime();
            sweep = core.findSinkCallSites(null, 0);
            bestB = Math.min(bestB, (System.nanoTime() - t) / 1_000_000);
        }
        int totalB = 0;
        for (Map.Entry<String, Object> e : sweep.entrySet()) {
            if (!e.getKey().startsWith("@")) {
                totalB += ((List<?>) e.getValue()).size();
            }
        }
        System.out.printf("B. with in-APK resolution (ships):    %4d ms   %d call sites%n",
                bestB, totalB);
        System.out.printf("   delta: %+d ms, %+d call sites (%.0f%% more surface)%n",
                bestB - bestA, totalB - foundA.size(),
                foundA.isEmpty() ? 0.0 : 100.0 * (totalB - foundA.size()) / foundA.size());

        // The aggregate hides the shape: a handful of tags carry most of the
        // catalogue's call sites, so a tag that doubles barely moves the total.
        Map<String, Integer> perTagA = new TreeMap<>();
        for (String hit : foundA) {
            perTagA.merge(hit.substring(0, hit.indexOf(' ')), 1, Integer::sum);
        }
        System.out.printf("%n%-32s %8s %8s %8s%n", "tag", "before", "after", "delta");
        for (String tag : catalogue.keySet()) {
            int before = perTagA.getOrDefault(tag, 0);
            int after = ((List<?>) sweep.get(tag)).size();
            if (before != after) {
                System.out.printf("%-32s %8d %8d %+8d%n", tag, before, after, after - before);
            }
        }

        Object resolved = sweep.get("@resolved_in_apk");
        if (resolved instanceof Map) {
            System.out.println("\nin-APK types the sweep resolved through:");
            ((Map<?, ?>) resolved).forEach((k, v) -> System.out.printf("  %-28s %s%n", k, v));
        }

        core.close();
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = JadxAnalyzerCore.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object staticField(String name) throws Exception {
        Field f = JadxAnalyzerCore.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
}

package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * find_sink_call_sites reads getUnresolvedUsed(), the half of the usage graph
 * that decompilation was believed not to touch. This checks that by name rather
 * than by size: full sweep, decompile, full sweep, set-diff per sink key.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.SinkSweepStabilityProbe
 *        -Dexec.args="<apk> <classesToDecompile>"
 */
public final class SinkSweepStabilityProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int decompileCount = args.length > 1 ? Integer.parseInt(args[1]) : 400;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        Set<String> cold = flatten(core.findSinkCallSites(null, 0));
        System.out.printf("cold: %d call sites%n", cold.size());

        List<String> classes = core.getAllClasses();
        int n = Math.min(decompileCount, classes.size());
        for (int i = 0; i < n; i++) {
            try {
                core.getClassSource(classes.get(i));
            } catch (Throwable t) {
                // still counts as touched
            }
        }
        System.out.printf("decompiled %d of %d classes%n", n, classes.size());

        Set<String> warm = flatten(core.findSinkCallSites(null, 0));
        System.out.printf("warm: %d call sites%n", warm.size());

        List<String> lost = new ArrayList<>(cold);
        lost.removeAll(warm);
        List<String> gained = new ArrayList<>(warm);
        gained.removeAll(cold);

        System.out.printf("%nlost %d, gained %d%n", lost.size(), gained.size());
        lost.stream().limit(40).forEach(s -> System.out.println("  - " + s));
        gained.stream().limit(40).forEach(s -> System.out.println("  + " + s));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> flatten(Map<String, Object> sweep) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : sweep.entrySet()) {
            if (!(e.getValue() instanceof List)) {
                continue;
            }
            for (Object o : (List<Object>) e.getValue()) {
                Map<String, String> site = (Map<String, String>) o;
                out.add(e.getKey() + "  " + site.get("className") + "." + site.get("methodName") + "#" + site.get("signature"));
            }
        }
        return out;
    }
}

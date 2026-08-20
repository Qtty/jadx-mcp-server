package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The verifier's own scenario, at daemon scale, across three tools at once:
 * ask, decompile a lot, ask again, diff.
 *
 * get_callees is the one this was written for. get_xrefs_to_method and
 * find_sink_call_sites are here because they read neighbouring parts of the same
 * usage graph and the claim that they are unaffected is a claim, not a
 * measurement.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.ToolStabilityProbe
 *        -Dexec.args="<apk> <classesToDecompile> [class#method ...]"
 */
public final class ToolStabilityProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int decompileCount = args.length > 1 ? Integer.parseInt(args[1]) : 400;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s%n", (System.nanoTime() - t0) / 1e9);

        String[] probes = args.length > 2
                ? java.util.Arrays.copyOfRange(args, 2, args.length)
                : new String[] { "jakhar.aseem.diva.LogActivity#checkout" };

        Map<String, String> coldCallees = new LinkedHashMap<>();
        Map<String, String> coldXrefs = new LinkedHashMap<>();
        for (String probe : probes) {
            coldCallees.put(probe, callees(core, probe));
            coldXrefs.put(probe, xrefs(core, probe));
        }
        String coldSinks = String.valueOf(core.findSinkCallSites(null, 0));
        System.out.printf("cold sink sweep: %d chars%n", coldSinks.length());

        List<String> classes = core.getAllClasses();
        int n = Math.min(decompileCount, classes.size());
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try {
                core.getClassSource(classes.get(i));
            } catch (Throwable t) {
                // a class that fails to decompile still counts as touched
            }
        }
        System.out.printf("decompiled %d of %d classes: %.1f s%n",
                n, classes.size(), (System.nanoTime() - t1) / 1e9);

        int bad = 0;
        for (String probe : probes) {
            bad += compare("get_callees        " + probe, coldCallees.get(probe), callees(core, probe));
            bad += compare("get_xrefs_to_method " + probe, coldXrefs.get(probe), xrefs(core, probe));
        }
        bad += compare("find_sink_call_sites", coldSinks, String.valueOf(core.findSinkCallSites(null, 0)));
        System.out.printf("%n%d of %d answers changed%n", bad, probes.length * 2 + 1);
    }

    private static int compare(String label, String cold, String warm) {
        boolean same = cold.equals(warm);
        System.out.printf("%-70s %s%n", label, same ? "STABLE" : "CHANGED");
        if (!same) {
            System.out.println("   cold: " + abbreviate(cold));
            System.out.println("   warm: " + abbreviate(warm));
        }
        return same ? 0 : 1;
    }

    private static String abbreviate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "... (" + s.length() + " chars)";
    }

    private static String callees(JadxAnalyzerCore core, String probe) {
        String[] p = probe.split("#", 2);
        Map<String, Object> r = core.getCallees(p[0], p[1]);
        return sizes(r) + " " + r;
    }

    @SuppressWarnings("unchecked")
    private static String sizes(Map<String, Object> r) {
        return "(" + ((List<?>) r.get("callees")).size()
                + "," + ((List<?>) r.get("external")).size()
                + ",self=" + r.get("callsSelf") + ")";
    }

    private static String xrefs(JadxAnalyzerCore core, String probe) {
        String[] p = probe.split("#", 2);
        List<Map<String, String>> r = core.getXrefsToMethod(p[0], p[1]);
        return "(" + r.size() + ") " + r;
    }
}

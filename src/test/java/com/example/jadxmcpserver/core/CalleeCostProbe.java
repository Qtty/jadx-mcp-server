package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Measurement harness for get_callees, against a real APK.
 *
 * Not a test — a main() run by hand, in the same spirit as UsageIndexCostProbe.
 * The claim being checked is the one that decides whether this tool can be
 * called in a loop by a sink tracer: docs/research/2026-jadx-1.5.6-assessment.md
 * measured getUsed() at 0.0006 ms per call and warned that a whole-APK sweep of
 * it is seconds, so a per-method lookup has to stay a lookup.
 *
 * The number this prints is the core call, not the HTTP round trip. The daemon
 * adds JSON encoding and a loopback hop on top; measure that separately against
 * a running daemon if that is what you care about.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.CalleeCostProbe
 *        -Dexec.args="<apk> <class> <method>"
 */
public final class CalleeCostProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        String cls = args.length > 1 ? args[1] : "jakhar.aseem.diva.LogActivity";
        String mth = args.length > 2 ? args[2] : "checkout";

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s%n", (System.nanoTime() - t0) / 1e9);

        Map<String, Object> first = core.getCallees(cls, mth);
        long t1 = System.nanoTime();
        Map<String, Object> cold = core.getCallees(cls, mth);
        System.out.printf("cold-ish call: %.4f ms%n", (System.nanoTime() - t1) / 1e6);

        int runs = 2000;
        long t2 = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            core.getCallees(cls, mth);
        }
        double perCall = (System.nanoTime() - t2) / 1e6 / runs;

        System.out.printf("%s#%s -> %d internal, %d external, callsSelf=%s%n",
                cls, mth, size(first, "callees"), size(first, "external"), first.get("callsSelf"));
        System.out.printf("%d calls: %.4f ms each%n", runs, perCall);
        if (cold.isEmpty()) {
            System.out.println("(empty result -- pick a method with a body)");
        }
    }

    @SuppressWarnings("unchecked")
    private static int size(Map<String, Object> result, String key) {
        return ((List<Map<String, String>>) result.get(key)).size();
    }
}

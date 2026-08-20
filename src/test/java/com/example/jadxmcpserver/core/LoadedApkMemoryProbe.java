package com.example.jadxmcpserver.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What a warm APK actually costs, and what alternating between two costs.
 *
 * AnalyzerRegistry's capacity-1 default carries the claim "a large APK costs
 * roughly 26 GB resident". That number decides whether a second slot is
 * affordable, so it is measured here rather than assumed: retained heap after a
 * forced GC (what a second APK would have to fit alongside) and process RSS
 * (what the box sees), taken before load, after one APK, and after two.
 *
 * Phase B then times the pattern the capacity-1 default produces when two analyses
 * share a daemon: A, B, A, B at capacity 1, where every access is a miss.
 *
 * Usage: java -Xmx24g -cp target/classes:$(cat /tmp/jadx-cp.txt) \
 *        com.example.jadxmcpserver.core.LoadedApkMemoryProbe <apk1> <apk2>
 */
public final class LoadedApkMemoryProbe {

    public static void main(String[] args) throws Exception {
        String apk1 = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        String apk2 = args.length > 1 ? args[1] : null;
        for (String a : new String[]{apk1, apk2}) {
            if (a != null && !new File(a).exists()) {
                System.out.println("SKIP: no such apk: " + a);
                return;
            }
        }

        System.out.println("maxMemory = " + mb(Runtime.getRuntime().maxMemory()) + " MB");
        report("baseline");

        AnalyzerRegistry registry = new AnalyzerRegistry(4);
        long t0 = System.currentTimeMillis();
        JadxAnalyzerCore c1 = registry.get(apk1);
        long load1 = System.currentTimeMillis() - t0;
        System.out.println("\nload " + apk1 + ": " + load1 + " ms, "
                + c1.getAllClasses().size() + " classes");
        report("after apk1");

        // Phase A2: what an analysis adds on top of the load. jadx caches decompiled
        // code per class, so a long session grows the warm APK's footprint; that
        // growth is the headroom a capacity formula has to leave unspent.
        List<String> names = c1.getAllClasses();
        int step = Math.max(1, names.size() / 300);
        int decompiled = 0;
        long chars = 0;
        t0 = System.currentTimeMillis();
        for (int i = 0; i < names.size() && decompiled < 300; i += step) {
            String src = c1.getClassSource(names.get(i));
            if (src != null) {
                chars += src.length();
                decompiled++;
            }
        }
        System.out.println("\ndecompiled " + decompiled + " classes ("
                + (System.currentTimeMillis() - t0) + " ms, " + chars / 1024 + " KB of source)");
        report("after 300 decompiles");

        if (apk2 != null) {
            t0 = System.currentTimeMillis();
            JadxAnalyzerCore c2 = registry.get(apk2);
            long load2 = System.currentTimeMillis() - t0;
            System.out.println("\nload " + apk2 + ": " + load2 + " ms, "
                    + c2.getAllClasses().size() + " classes");
            report("after apk2 (both resident)");
        }
        registry.closeAll();
        report("after closeAll");

        if (apk2 == null) {
            return;
        }

        // Phase B: the thrash pattern. Capacity 1, alternating APKs, so every
        // get() is a miss and pays a full reload.
        System.out.println("\n--- phase B: alternating access at capacity 1 ---");
        AnalyzerRegistry thrash = new AnalyzerRegistry(1);
        String[] order = {apk1, apk2, apk1, apk2};
        long total = 0;
        for (String apk : order) {
            long t = System.currentTimeMillis();
            thrash.get(apk);
            long ms = System.currentTimeMillis() - t;
            total += ms;
            System.out.println("  get(" + shortName(apk) + "): " + ms + " ms");
        }
        System.out.println("  four alternating accesses: " + total + " ms");
        thrash.closeAll();
        report("after phase B");
    }

    private static void report(String label) {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        System.gc();
        long retained = rt.totalMemory() - rt.freeMemory();
        System.out.println(String.format("%-26s retained %6d MB   rss %6d MB",
                label, mb(retained), rss()));
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /** VmRSS in MB, or -1 where /proc is not available. */
    private static long rss() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/self/status"),
                    StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) / 1024;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return -1;
    }

    private static String shortName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(0, i).substring(path.lastIndexOf('/', i - 1) + 1)
                + "/" + path.substring(i + 1);
    }
}

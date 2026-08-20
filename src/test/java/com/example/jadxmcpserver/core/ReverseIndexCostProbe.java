package com.example.jadxmcpserver.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the dex reverse index costs: time to build and heap to keep.
 *
 * Both numbers are decisions, not trivia. The time decides eager-at-load versus
 * lazy-on-first-use; the heap decides whether AnalyzerRegistry's
 * {@code PER_APK_BUDGET_MB} still describes a warm APK slot, because that
 * constant is what {@code defaultCapacity()} divides the machine's heap by.
 *
 * Measured the way LoadedApkMemoryProbe measures: retained heap after a forced
 * GC, which is what a second warm APK would have to fit alongside, plus process
 * RSS, which is what the box sees.
 *
 * Usage: java -Xmx24g -cp target/classes:target/test-classes:$CP \
 *        com.example.jadxmcpserver.core.ReverseIndexCostProbe <apk>
 */
public final class ReverseIndexCostProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        System.out.println("maxMemory = " + mb(Runtime.getRuntime().maxMemory()) + " MB");
        report("baseline");

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.currentTimeMillis();
        core.loadApk();
        long loadMs = System.currentTimeMillis() - t0;
        System.out.println("\nload " + apk + ": " + loadMs + " ms");
        long afterLoad = report("after load");

        // The first xref call is what pays for the index when it is lazy, so
        // time the tool, not the builder: that is the latency a caller sees.
        List<String> classes = core.getAllClasses();
        String probeClass = classes.get(classes.size() / 2);
        t0 = System.currentTimeMillis();
        core.getXrefsToClass(probeClass);
        long firstCallMs = System.currentTimeMillis() - t0;
        Map<String, Object> stats = core.reverseIndexStats();
        System.out.println("\nfirst get_xrefs_to_class (builds the index): " + firstCallMs + " ms");
        System.out.println("index stats: " + stats);
        long afterIndex = report("after index");
        System.out.println("\nindex retained = " + (afterIndex - afterLoad) + " MB");

        t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            core.getXrefsToClass(classes.get((i * 7919) % classes.size()));
        }
        System.out.printf("warm get_xrefs_to_class: %.3f ms/call%n",
                (System.nanoTime() - t0) / 1e6 / 200);

        // The shape of the adjacency, which is what decides whether compaction
        // wants a Set per key or a fast path.
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.core.dex.nodes.RootNode root =
                ((jadx.api.JadxDecompiler) f.get(core)).getRoot();
        DexReverseIndex index = DexReverseIndex.build(root);
        long keys = 0;
        long single = 0;
        long widest = 0;
        for (jadx.core.dex.nodes.ClassNode c : root.getClasses()) {
            for (int n : new int[] { index.methodUsersOf(c).size(),
                    index.classUsersOf(c).size() }) {
                if (n > 0) {
                    keys++;
                    if (n == 1) {
                        single++;
                    }
                    widest = Math.max(widest, n);
                }
            }
            for (jadx.core.dex.nodes.MethodNode m : c.getMethods()) {
                int n = index.callersOf(m).size();
                if (n > 0) {
                    keys++;
                    if (n == 1) {
                        single++;
                    }
                    widest = Math.max(widest, n);
                }
            }
            for (jadx.core.dex.nodes.FieldNode fn : c.getFields()) {
                int n = index.usersOf(fn).size();
                if (n > 0) {
                    keys++;
                    if (n == 1) {
                        single++;
                    }
                    widest = Math.max(widest, n);
                }
            }
        }
        System.out.printf("adjacency: %d non-empty keys, %d with exactly one user (%.0f%%),"
                + " widest %d%n", keys, single, 100.0 * single / keys, widest);
    }

    private static long report(String label) {
        System.gc();
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long retained = mb(rt.totalMemory() - rt.freeMemory());
        System.out.printf("%-22s retained %5d MB   rss %6d MB%n", label, retained, rssMb());
        return retained;
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static long rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"),
                    StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) {
            // /proc is Linux-only; the retained figure is the one that matters
        }
        return -1;
    }
}

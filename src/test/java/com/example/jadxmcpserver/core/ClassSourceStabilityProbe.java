package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. Does get_class_source answer the same thing twice?
 *
 * Two processes, so that "cold" is genuinely cold and each warm-up can be
 * attributed to ONE operation rather than to whatever the previous phase of a
 * single long-lived process happened to leave behind:
 *
 * <pre>
 *   run 1:  cold  &lt;apk&gt; &lt;outdir&gt; &lt;warmCount&gt; &lt;probeStride&gt;
 *           pick the probe set, dump each class's cold source to outdir
 *   run 2:  &lt;mode&gt; &lt;apk&gt; &lt;outdir&gt;
 *           reload, run the named warm-up, re-read the SAME probe set,
 *           diff against the dump
 * </pre>
 *
 * Warm-up modes, one cause each:
 * <ul>
 *   <li>{@code source}  decompile the first N top-level classes</li>
 *   <li>{@code enums}   decompile every enum's owner -- the only pass that
 *       renames a field runs there, and a tree prefix contains almost none</li>
 *   <li>{@code index}   build DexReverseIndex, i.e. read every method's
 *       bytecode without decompiling anything</li>
 *   <li>{@code methods} / {@code fields}  the member listings</li>
 *   <li>{@code all}     the acceptance scenario: N top-level classes AND every
 *       enum</li>
 *   <li>{@code none}    no warm-up, probes in the same order -- the CONTROL,
 *       which has to come back identical or the harness is measuring itself</li>
 *   <li>{@code rev}     no warm-up, probes in the OPPOSITE order. Order alone
 *       is a history, and on a large production APK it finds ten times what the warm-up
 *       scenario does</li>
 * </ul>
 *
 * Four modes answer a different question. {@code single} reads one class as the
 * very first thing the process does, which is the only true ground truth --
 * even the cold dump is a history, because probe 2,000 was read after 1,999
 * others. {@code reload} / {@code reload-warm} ask whether a deep reload is a
 * fixed point and whether it is the SAME fixed point from two histories.
 * {@code diag} prints the target's ProcessState and throws-attribute census
 * either side of a warm-up. {@code cost} measures what a whole-APK warm-up
 * costs in seconds and retained heap, which is the price of the only complete
 * fix.
 *
 * The findings this produced are recorded on
 * {@link JadxAnalyzerCore#getClassSource(String, int)}.
 *
 * The probe set is written to disk by run 1 and read back by run 2, so the two
 * runs cannot disagree about which classes were asked -- an earlier round in
 * this repo produced a passing-but-meaningless result by re-deriving the sample
 * on each side.
 */
public final class ClassSourceStabilityProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: <mode> <apk> <outdir> [warmCount] [probeStride]");
            return;
        }
        String mode = args[0];
        String apk = args[1];
        Path out = Path.of(args[2]);
        boolean single = mode.equals("single") || mode.equals("diag");
        int warmCount = !single && args.length > 3 ? Integer.parseInt(args[3]) : 3000;
        int stride = !single && args.length > 4 ? Integer.parseInt(args[4]) : 7;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s   %s%n", (System.nanoTime() - t0) / 1e9, apk);
        RootNode root = root(core);
        List<ClassNode> all = new ArrayList<>(root.getClasses());
        int inner = 0, enums = 0;
        for (ClassNode c : all) {
            if (c.isInner()) inner++;
            if (c.getAccessFlags().isEnum()) enums++;
        }
        System.out.printf("classes: %d (inner %d, enum %d)%n", all.size(), inner, enums);

        if (mode.equals("cold")) {
            dumpCold(core, all, out, warmCount, stride);
            return;
        }
        if (mode.equals("reload") || mode.equals("reload-warm")) {
            if (mode.equals("reload-warm")) {
                warmUp(core, all, "all", warmCount);
            }
            reloadFixedPoint(core, all, Files.readAllLines(out.resolve("reloadlist.txt")));
            return;
        }
        if (mode.equals("cost")) {
            // What the only known COMPLETE fix costs: a canonical global state
            // means decompiling the whole APK in a fixed order before answering
            // anything. Measured on a prefix, with the heap it leaves behind.
            warmUp(core, all, "source", warmCount);
            Runtime rt = Runtime.getRuntime();
            System.gc();
            System.out.printf("heap after %d classes: %d MB used, %d MB max%n",
                    warmCount, (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
            return;
        }
        if (mode.equals("diag")) {
            String target = args[3];
            ClassNode top = null;
            for (ClassNode c : all) {
                if (c.getClassInfo().getAliasFullName().equals(target)) { top = c.getTopParentClass(); }
            }
            System.out.println("target top = " + top.getClassInfo().getAliasFullName());
            report("before warm-up", top);
            warmUp(core, all, "all", warmCount);
            report("after warm-up ", top);
            String src = safeSource(core, target);
            System.out.printf("source %s len=%d%n", sha(src), src.length());
            report("after read    ", top);
            return;
        }
        if (mode.equals("single")) {
            // Ground truth: this class is the FIRST thing the process ever asks
            // for, so no other class's passes can have touched its methods.
            for (int i = 3; i < args.length; i++) {
                String name = args[i];
                String src = safeSource(core, name);
                System.out.printf("single %-70s %s len=%d%n", name,
                        src == null ? "null" : sha(src), src == null ? -1 : src.length());
            }
            return;
        }
        // "none" and "rev" run no warm-up at all: they are two COLD histories,
        // differing only in the order the probes are asked. The cold dump is
        // itself a history -- probe 2000 was read after 1999 others -- so a
        // cold-vs-warm diff alone understates the drift.
        if (!mode.equals("none") && !mode.equals("rev")) {
            warmUp(core, all, mode, warmCount);
        }
        compare(core, out, mode.equals("rev"));
    }

    // ---------------------------------------------------------------- cold

    private static void dumpCold(JadxAnalyzerCore core, List<ClassNode> all,
                                 Path out, int warmCount, int stride) throws IOException {
        Files.createDirectories(out);
        // Probes come from INSIDE the range a warm-up will cover: the first
        // warmCount TOP-LEVEL classes and every inner class they own. Sampling
        // beyond it measures a class nothing touched, which is the shape of an
        // earlier meaningless pass.
        java.util.Set<String> warmSet = new java.util.HashSet<>(warmTargets(all, warmCount));
        int limit = warmSet.size();

        List<String> probes = new ArrayList<>();
        int i = 0;
        for (ClassNode c : all) {
            String name = c.getClassInfo().getAliasFullName();
            String top = c.getTopParentClass().getClassInfo().getAliasFullName();
            if (!warmSet.contains(top)) {
                continue;
            }
            // every enum, plus a stride sample of everything else
            if (c.getAccessFlags().isEnum() || (i++ % stride == 0)) {
                probes.add(name);
            }
        }
        System.out.printf("probe set: %d classes drawn from %d warmable top-level%n",
                probes.size(), limit);

        Files.write(out.resolve("probes.txt"), probes);
        Path bodies = out.resolve("cold");
        Files.createDirectories(bodies);
        long t = System.nanoTime();
        int written = 0;
        for (String name : probes) {
            String src = safeSource(core, name);
            Files.writeString(bodies.resolve(fileName(name)), src == null ? "\0NULL" : src,
                    StandardCharsets.UTF_8);
            written++;
        }
        System.out.printf("cold read of %d classes: %.1f s%n",
                written, (System.nanoTime() - t) / 1e9);
    }

    /**
     * Is a DEEP RELOAD a fixed point, and is that fixed point the same one from
     * a different history?
     *
     * {@code AFlag.CLASS_DEEP_RELOAD} makes ProcessClass throw the class's
     * loaded state away -- blocks, instructions and the attributes other
     * classes' passes latched onto its methods -- and run the pre-decompile
     * stage again before the passes. If the text after one reload equals the
     * text after two, the reload converges; if the converged text is the same
     * cold and warm, it converges to the SAME place, which is the property
     * get_class_source needs.
     */
    private static void reloadFixedPoint(JadxAnalyzerCore core, List<ClassNode> all,
                                         List<String> names) {
        Map<String, ClassNode> byName = new LinkedHashMap<>();
        for (ClassNode c : all) {
            byName.put(c.getClassInfo().getAliasFullName(), c);
        }
        for (String name : names) {
            ClassNode cls = byName.get(name);
            if (cls == null) { System.out.println("MISSING " + name); continue; }
            ClassNode top = cls.getTopParentClass();
            StringBuilder line = new StringBuilder();
            long[] ms = new long[3];
            for (int pass = 0; pass < 3; pass++) {
                if (pass > 0) {
                    top.unloadFromCache();
                    top.add(jadx.core.dex.attributes.AFlag.CLASS_DEEP_RELOAD);
                }
                long t = System.nanoTime();
                String src = String.valueOf(top.getCode().getCodeStr());
                ms[pass] = (System.nanoTime() - t) / 1_000_000;
                line.append(' ').append(sha(src)).append('/').append(src.length());
            }
            System.out.printf("reload %-70s%s  ms=%d,%d,%d%n", name, line, ms[0], ms[1], ms[2]);
        }
    }

    /** State of the throws latch over a class and everything nested in it. */
    private static void report(String label, ClassNode top) {
        int[] counts = new int[4]; // methods, with-attr, visited, non-empty
        walk(top, counts);
        System.out.printf("%s state=%-24s methods=%d attr=%d visited=%d nonEmptyThrows=%d%n",
                label, top.getState(), counts[0], counts[1], counts[2], counts[3]);
    }

    private static void walk(ClassNode cls, int[] counts) {
        for (jadx.core.dex.nodes.MethodNode m : cls.getMethods()) {
            counts[0]++;
            jadx.core.dex.attributes.nodes.MethodThrowsAttr a =
                    m.get(jadx.core.dex.attributes.AType.METHOD_THROWS);
            if (a != null) {
                counts[1]++;
                if (a.isVisited()) { counts[2]++; }
                if (!a.getList().isEmpty()) { counts[3]++; }
            }
        }
        for (ClassNode inner : cls.getInnerClasses()) {
            walk(inner, counts);
        }
    }

    // ---------------------------------------------------------------- warm

    /**
     * The top-level classes a full warm-up covers: a prefix of the tree, PLUS
     * every class that owns an enum. jadx renames enum constants only when the
     * enum decompiles, and enums are not evenly spread through the tree -- a
     * prefix of {@code RootNode.getClasses()} on a large production APK contains almost none
     * -- so a prefix-only warm-up measures a sample with the drift removed.
     */
    private static List<String> warmTargets(List<ClassNode> all, int warmCount) {
        List<String> topLevel = new ArrayList<>();
        for (ClassNode c : all) {
            if (!c.isInner()) {
                topLevel.add(c.getClassInfo().getAliasFullName());
            }
        }
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>(
                topLevel.subList(0, Math.min(warmCount, topLevel.size())));
        for (ClassNode c : all) {
            if (c.getAccessFlags().isEnum()) {
                targets.add(c.getTopParentClass().getClassInfo().getAliasFullName());
            }
        }
        return new ArrayList<>(targets);
    }

    private static void warmUp(JadxAnalyzerCore core, List<ClassNode> all,
                               String mode, int warmCount) {
        List<String> topLevel = new ArrayList<>();
        for (ClassNode c : all) {
            if (!c.isInner()) {
                topLevel.add(c.getClassInfo().getAliasFullName());
            }
        }
        int limit = Math.min(warmCount, topLevel.size());
        long t = System.nanoTime();
        int n = 0;
        switch (mode) {
            case "source":
                for (int i = 0; i < limit; i++) { touch(core, topLevel.get(i)); n++; }
                break;
            case "enums":
                for (ClassNode c : all) {
                    if (c.getAccessFlags().isEnum()) {
                        touch(core, c.getTopParentClass().getClassInfo().getAliasFullName());
                        n++;
                    }
                }
                break;
            case "index":
                // The same scan getXrefsTo* pays for on first use: every method
                // in the APK read through MethodNode.getCodeReader().
                n = DexReverseIndex.build(all.get(0).root()).stats().size();
                break;
            case "methods":
                for (int i = 0; i < limit; i++) {
                    try { core.getMethodsOfClass(topLevel.get(i)); } catch (Throwable ignored) { }
                    n++;
                }
                break;
            case "fields":
                for (int i = 0; i < limit; i++) {
                    try { core.getFieldsOfClass(topLevel.get(i)); } catch (Throwable ignored) { }
                    n++;
                }
                break;
            case "all":
                for (String name : warmTargets(all, warmCount)) { touch(core, name); n++; }
                break;
            default:
                throw new IllegalArgumentException("unknown mode: " + mode);
        }
        System.out.printf("warm-up '%s': %d operations, %.1f s%n",
                mode, n, (System.nanoTime() - t) / 1e9);
    }

    private static void touch(JadxAnalyzerCore core, String name) {
        try {
            core.getClassSource(name);
        } catch (Throwable ignored) {
            // a class that fails to decompile still counts as touched
        }
    }

    // ------------------------------------------------------------- compare

    private static void compare(JadxAnalyzerCore core, Path out, boolean reverse) throws Exception {
        List<String> probes = new ArrayList<>(Files.readAllLines(out.resolve("probes.txt")));
        if (reverse) {
            java.util.Collections.reverse(probes);
        }
        Path bodies = out.resolve("cold");

        int same = 0, wsOnly = 0, differ = 0, missing = 0;
        long lenColdTotal = 0, lenWarmTotal = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        long t = System.nanoTime();
        for (String name : probes) {
            String cold = Files.readString(bodies.resolve(fileName(name)), StandardCharsets.UTF_8);
            if (cold.equals("\0NULL")) { cold = null; }
            String warm = safeSource(core, name);
            if (cold == null || warm == null) {
                if (!java.util.Objects.equals(cold, warm)) { missing++; }
                continue;
            }
            lenColdTotal += cold.length();
            lenWarmTotal += warm.length();
            if (cold.equals(warm)) { same++; continue; }
            differ++;
            changed.add(name);
            if (squash(cold).equals(squash(warm))) {
                wsOnly++;
                kinds.merge("whitespace-only", 1, Integer::sum);
            } else {
                kinds.merge(classify(cold, warm), 1, Integer::sum);
            }
            if (samples.size() < 60) {
                samples.add(sample(name, cold, warm));
            }
        }
        System.out.printf("warm re-read of %d classes: %.1f s%n",
                probes.size(), (System.nanoTime() - t) / 1e9);
        System.out.printf("%nprobes %d | identical %d | DIFFER %d (whitespace-only %d) | null-mismatch %d%n",
                probes.size(), same, differ, wsOnly, missing);
        System.out.printf("total chars: cold %d  warm %d  delta %+d%n",
                lenColdTotal, lenWarmTotal, lenWarmTotal - lenColdTotal);
        System.out.println("kinds: " + kinds);
        Files.write(out.resolve("changed.txt"), changed);
        System.out.println("\n---- samples ----");
        for (String s : samples) {
            System.out.println(s);
        }
    }

    /** Coarse shape of a difference, read off line multisets. */
    private static String classify(String cold, String warm) {
        List<String> c = trimmedLines(cold);
        List<String> w = trimmedLines(warm);
        Map<String, Integer> bag = new LinkedHashMap<>();
        for (String s : c) { bag.merge(s, 1, Integer::sum); }
        for (String s : w) { bag.merge(s, -1, Integer::sum); }
        int onlyCold = 0, onlyWarm = 0;
        for (int v : bag.values()) {
            if (v > 0) { onlyCold += v; } else if (v < 0) { onlyWarm += -v; }
        }
        if (onlyCold == 0 && onlyWarm == 0) { return "reordered-lines"; }
        if (onlyCold == 0) { return "warm-added-only"; }
        if (onlyWarm == 0) { return "warm-dropped-only"; }
        return "rewritten(" + bucket(onlyCold) + "/" + bucket(onlyWarm) + ")";
    }

    private static String bucket(int n) {
        if (n <= 2) { return String.valueOf(n); }
        if (n <= 10) { return "3-10"; }
        if (n <= 100) { return "11-100"; }
        return ">100";
    }

    private static String sample(String name, String cold, String warm) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(name)
          .append("  cold ").append(cold.length())
          .append(" chars, warm ").append(warm.length()).append('\n');
        List<String> c = Arrays.asList(cold.split("\n", -1));
        List<String> w = Arrays.asList(warm.split("\n", -1));
        int shown = 0;
        int n = Math.max(c.size(), w.size());
        for (int i = 0; i < n && shown < 10; i++) {
            String cl = i < c.size() ? c.get(i) : "<eof>";
            String wl = i < w.size() ? w.get(i) : "<eof>";
            if (!cl.equals(wl)) {
                sb.append("  L").append(i + 1).append(" cold: ").append(abbrev(cl)).append('\n');
                sb.append("  L").append(i + 1).append(" warm: ").append(abbrev(wl)).append('\n');
                shown++;
            }
        }
        return sb.toString();
    }

    private static String abbrev(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }

    private static List<String> trimmedLines(String s) {
        List<String> out = new ArrayList<>();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) { out.add(t); }
        }
        return out;
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // --------------------------------------------------------------- utils

    private static String safeSource(JadxAnalyzerCore core, String name) {
        try {
            return core.getClassSource(name);
        } catch (Throwable t) {
            return "\0ERROR " + t;
        }
    }

    static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { sb.append(String.format("%02x", h[i])); }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String fileName(String className) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(className.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) { sb.append(String.format("%02x", h[i])); }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    static RootNode root(JadxAnalyzerCore core) throws Exception {
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        return ((JadxDecompiler) f.get(core)).getRoot();
    }
}

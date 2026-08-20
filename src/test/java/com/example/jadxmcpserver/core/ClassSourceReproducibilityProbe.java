package com.example.jadxmcpserver.core;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MEASUREMENT ONLY. Three questions {@link ClassSourceStabilityProbe} cannot
 * answer, because it compares a warm process against a cold one inside a fixed
 * warm-up shape.
 *
 * <ol>
 *   <li><b>Is the answer a function of the request history at all?</b> Every
 *       figure on {@link JadxAnalyzerCore#getClassSource(String, int)} was a
 *       cold-vs-warm diff, which can only show that a DIFFERENT history gives a
 *       different text. Run the SAME history in two fresh processes and diff
 *       those, and you find out whether replaying your requests gets your text
 *       back. It does not.</li>
 *   <li><b>How much of the published residue is the warm-up SHAPE?</b> "The
 *       first 3,000 of {@code RootNode.getClasses()}" is an arbitrary choice.
 *       {@code spread} draws 3,000 as an even stride across the sorted name
 *       list and {@code random} draws them with a fixed seed -- equally
 *       arbitrary, and they do not agree.</li>
 *   <li><b>Are the differences cosmetic?</b> {@code cmp} classifies every diff
 *       by whether the set of calls sitting INSIDE a try/catch changed, which is
 *       the thing a sink trace reads when it decides whether tainted data
 *       reaches a sink.</li>
 * </ol>
 *
 * <pre>
 *   dump &lt;apk&gt; &lt;outdir&gt; &lt;label&gt; &lt;warmMode&gt; &lt;warmCount&gt; &lt;shape&gt; [stride]
 *   cmp  &lt;outdir&gt; &lt;labelA&gt; &lt;labelB&gt;
 * </pre>
 *
 * Every {@code dump} into one outdir reads the SAME probe list in the SAME
 * order -- run 1 writes {@code probes.txt}, every later run reads it -- so two
 * labels differ only by the warm-up named on the command line. {@code cmp}
 * needs no APK and no jadx: it diffs two directories of text.
 *
 * warmMode: {@code none} (no warm-up at all), {@code source} (decompile the
 * shape's N classes), {@code enums} (every enum's owner), {@code all} (both),
 * {@code index} (build DexReverseIndex -- reads bytecode, decompiles nothing).
 * Suffix any of them with {@code :rev} to read the probes back-to-front.
 */
public final class ClassSourceReproducibilityProbe {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: dump <apk> <outdir> <label> <warmMode> <warmCount> <shape> [stride]");
            System.out.println("       cmp  <outdir> <labelA> <labelB>");
            return;
        }
        if (args[0].equals("cmp")) {
            cmp(Path.of(args[1]), args[2], args[3]);
            return;
        }
        dump(args);
    }

    // ----------------------------------------------------------------- dump

    private static void dump(String[] args) throws Exception {
        String apk = args[1];
        Path out = Path.of(args[2]);
        String label = args[3];
        String warmMode = args[4];
        int warmCount = Integer.parseInt(args[5]);
        String shape = args[6];
        int stride = args.length > 7 ? Integer.parseInt(args[7]) : 7;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        boolean reverse = warmMode.endsWith(":rev");
        String warm = reverse ? warmMode.substring(0, warmMode.length() - 4) : warmMode;

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s   %s%n", (System.nanoTime() - t0) / 1e9, apk);
        RootNode root = ClassSourceStabilityProbe.root(core);
        List<ClassNode> all = new ArrayList<>(root.getClasses());

        Files.createDirectories(out);
        Path probeFile = out.resolve("probes.txt");
        List<String> probes;
        if (Files.exists(probeFile)) {
            probes = Files.readAllLines(probeFile);
            System.out.printf("probe set: %d classes, read from %s%n", probes.size(), probeFile);
        } else {
            List<String> warmSet = warmTargets(all, warmCount, shape);
            probes = probeSet(all, warmSet, stride);
            Files.write(probeFile, probes);
            Files.writeString(out.resolve("shape.txt"), shape + " " + warmCount + " stride=" + stride);
            System.out.printf("probe set: %d classes drawn from %d warmable top-level (shape %s)%n",
                    probes.size(), warmSet.size(), shape);
        }

        if (!warm.equals("none")) {
            warmUp(core, all, warm, warmCount, shape);
        }

        List<String> order = new ArrayList<>(probes);
        if (reverse) {
            Collections.reverse(order);
        }
        Path bodies = out.resolve(label);
        Files.createDirectories(bodies);
        long t = System.nanoTime();
        for (String name : order) {
            String src = safeSource(core, name);
            Files.writeString(bodies.resolve(fileName(name)), src == null ? "\0NULL" : src,
                    StandardCharsets.UTF_8);
        }
        System.out.printf("read %d classes into %s (%s%s): %.1f s%n",
                order.size(), label, warm, reverse ? ", reversed" : "",
                (System.nanoTime() - t) / 1e9);
        core.close();
    }

    /** Top-level classes to warm, chosen by SHAPE rather than by tree order. */
    private static List<String> warmTargets(List<ClassNode> all, int warmCount, String shape) {
        List<String> topLevel = new ArrayList<>();
        for (ClassNode c : all) {
            if (!c.isInner()) {
                topLevel.add(c.getClassInfo().getAliasFullName());
            }
        }
        int n = Math.min(warmCount, topLevel.size());
        List<String> chosen = new ArrayList<>();
        switch (shape) {
            case "prefix":
                chosen.addAll(topLevel.subList(0, n));
                break;
            case "spread": {
                List<String> sorted = new ArrayList<>(topLevel);
                Collections.sort(sorted);
                for (int i = 0; i < n; i++) {
                    chosen.add(sorted.get((int) ((long) i * sorted.size() / n)));
                }
                break;
            }
            case "random": {
                List<String> shuffled = new ArrayList<>(topLevel);
                Collections.sort(shuffled);
                Collections.shuffle(shuffled, new Random(20260808L));
                chosen.addAll(shuffled.subList(0, n));
                break;
            }
            default:
                throw new IllegalArgumentException("unknown shape: " + shape);
        }
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>(chosen);
        for (ClassNode c : all) {
            if (c.getAccessFlags().isEnum()) {
                targets.add(c.getTopParentClass().getClassInfo().getAliasFullName());
            }
        }
        return new ArrayList<>(targets);
    }

    /** Probes drawn from inside the warmed range: every enum, plus a stride sample. */
    private static List<String> probeSet(List<ClassNode> all, List<String> warmSet, int stride) {
        java.util.Set<String> warm = new java.util.HashSet<>(warmSet);
        List<String> probes = new ArrayList<>();
        int i = 0;
        for (ClassNode c : all) {
            String top = c.getTopParentClass().getClassInfo().getAliasFullName();
            if (!warm.contains(top)) {
                continue;
            }
            if (c.getAccessFlags().isEnum() || (i++ % stride == 0)) {
                probes.add(c.getClassInfo().getAliasFullName());
            }
        }
        return probes;
    }

    private static void warmUp(JadxAnalyzerCore core, List<ClassNode> all,
                               String mode, int warmCount, String shape) {
        long t = System.nanoTime();
        int n = 0;
        switch (mode) {
            case "source":
                for (String name : shapeOnly(all, warmCount, shape)) { touch(core, name); n++; }
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
                n = DexReverseIndex.build(all.get(0).root()).stats().size();
                break;
            case "meta":
                // Decompiles NOTHING. Reads each class's access flags, its alias
                // name and its top-level parent -- the metadata a name-listing
                // tool reads. If this moves get_class_source, then "only
                // decompiling other classes moves it" is false.
                for (ClassNode c : all) {
                    if (c.isInner() || c.getAccessFlags().isEnum()) { n++; }
                    c.getClassInfo().getAliasFullName();
                    c.getTopParentClass();
                }
                break;
            case "names":
                // The same thing through the real tool an agent would call.
                n = core.getAllClasses(null, 0).size();
                break;
            case "rootnames": {
                // getAllClasses minus the ambiguous-name branch and the size
                // guard: the live class list, each alias name, sorted.
                List<String> tmp = new ArrayList<>();
                for (ClassNode c : all.get(0).root().getClasses()) {
                    tmp.add(c.getClassInfo().getAliasFullName());
                }
                Collections.sort(tmp);
                n = tmp.size();
                break;
            }
            case "rawnames": {
                // The other half: the unique spelling getAllClasses builds for a
                // name that denotes several classes.
                for (ClassNode c : all) {
                    c.getClassInfo().makeAliasRawFullName();
                    n++;
                }
                break;
            }
            case "gc":
                // Touches neither the tree nor the heap in any jadx-visible way.
                for (int i = 0; i < 3; i++) { System.gc(); n++; }
                break;
            case "alloc":
                // The ALLOCATION getAllClasses does, without any jadx call:
                // 141,461 strings, sorted, plus an 8 MB serialisation of them.
                List<String> junk = new ArrayList<>();
                for (int i = 0; i < 141461; i++) {
                    junk.add("com.example.filler.ClassNumber" + i + "Padding");
                }
                Collections.sort(junk);
                n = String.join(",", junk).length();
                break;
            case "all":
                for (String name : warmTargets(all, warmCount, shape)) { touch(core, name); n++; }
                break;
            default:
                throw new IllegalArgumentException("unknown warm mode: " + mode);
        }
        System.out.printf("warm-up '%s' shape '%s': %d operations, %.1f s%n",
                mode, shape, n, (System.nanoTime() - t) / 1e9);
    }

    /** warmTargets without the enum union -- the shape's N classes and nothing else. */
    private static List<String> shapeOnly(List<ClassNode> all, int warmCount, String shape) {
        List<String> withEnums = warmTargets(all, warmCount, shape);
        List<String> topLevel = new ArrayList<>();
        for (ClassNode c : all) {
            if (!c.isInner()) { topLevel.add(c.getClassInfo().getAliasFullName()); }
        }
        int n = Math.min(warmCount, topLevel.size());
        return new ArrayList<>(withEnums.subList(0, Math.min(n, withEnums.size())));
    }

    private static void touch(JadxAnalyzerCore core, String name) {
        try {
            core.getClassSource(name);
        } catch (Throwable ignored) {
            // a class that fails to decompile still counts as touched
        }
    }

    private static String safeSource(JadxAnalyzerCore core, String name) {
        try {
            return core.getClassSource(name);
        } catch (Throwable t) {
            return "\0ERROR " + t;
        }
    }

    // ------------------------------------------------------------------ cmp

    private static void cmp(Path out, String labelA, String labelB) throws Exception {
        List<String> probes = Files.readAllLines(out.resolve("probes.txt"));
        Path a = out.resolve(labelA);
        Path b = out.resolve(labelB);

        int same = 0, differ = 0, missing = 0;
        int tryChanged = 0, callSetChanged = 0, tryBlockCountChanged = 0;
        long tokensIn = 0, tokensOut = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        List<String> tryExamples = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        for (String name : probes) {
            String ta = read(a, name);
            String tb = read(b, name);
            if (ta == null || tb == null) { missing++; continue; }
            if (ta.equals(tb)) { same++; continue; }
            differ++;
            changed.add(name);

            Map<String, Integer> ca = inTryCalls(ta);
            Map<String, Integer> cb = inTryCalls(tb);
            Map<String, Integer> alla = allCalls(ta);
            Map<String, Integer> allb = allCalls(tb);
            int blocksA = tryBlocks(ta);
            int blocksB = tryBlocks(tb);
            if (blocksA != blocksB) { tryBlockCountChanged++; }
            if (!alla.equals(allb)) { callSetChanged++; }
            if (!ca.equals(cb)) {
                tryChanged++;
                int[] delta = delta(ca, cb);
                tokensOut += delta[0];
                tokensIn += delta[1];
                kinds.merge(delta[0] + "->" + delta[1], 1, Integer::sum);
                if (tryExamples.size() < 25) {
                    tryExamples.add(describe(name, ta, tb, ca, cb, blocksA, blocksB));
                }
            }
        }

        System.out.printf("%n=== %s vs %s ===%n", labelA, labelB);
        System.out.printf("probes %d | identical %d | DIFFER %d | missing %d%n",
                probes.size(), same, differ, missing);
        System.out.printf("of the %d that differ: %d change the set of calls inside a try/catch,"
                        + " %d change the number of try blocks, %d change the set of calls at all%n",
                differ, tryChanged, tryBlockCountChanged, callSetChanged);
        System.out.printf("call tokens that left a try region: %d; that entered one: %d%n",
                tokensOut, tokensIn);
        System.out.println("in-try deltas (left->entered): " + kinds);
        Files.write(out.resolve("changed-" + labelA + "-" + labelB + ".txt"), changed);
        System.out.println("\n---- try/catch examples ----");
        for (String s : tryExamples) {
            System.out.println(s);
        }
    }

    private static int[] delta(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> bag = new LinkedHashMap<>(a);
        for (Map.Entry<String, Integer> e : b.entrySet()) {
            bag.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        int onlyA = 0, onlyB = 0;
        for (int v : bag.values()) {
            if (v > 0) { onlyA += v; } else if (v < 0) { onlyB += -v; }
        }
        return new int[] { onlyA, onlyB };
    }

    private static String describe(String name, String ta, String tb,
                                   Map<String, Integer> ca, Map<String, Integer> cb,
                                   int blocksA, int blocksB) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(name)
          .append("  chars ").append(ta.length()).append(" vs ").append(tb.length())
          .append("  tryBlocks ").append(blocksA).append(" vs ").append(blocksB).append('\n');
        Map<String, Integer> bag = new TreeMap<>(ca);
        for (Map.Entry<String, Integer> e : cb.entrySet()) {
            bag.merge(e.getKey(), -e.getValue(), Integer::sum);
        }
        int shown = 0;
        for (Map.Entry<String, Integer> e : bag.entrySet()) {
            if (e.getValue() == 0 || shown++ >= 8) { continue; }
            sb.append(e.getValue() > 0 ? "  only in " + "A" : "  only in " + "B")
              .append(" (x").append(Math.abs(e.getValue())).append("): ")
              .append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    private static String read(Path dir, String name) throws IOException {
        Path p = dir.resolve(fileName(name));
        if (!Files.exists(p)) { return null; }
        String s = Files.readString(p, StandardCharsets.UTF_8);
        return s.equals("\0NULL") ? null : s;
    }

    // ------------------------------------------------------- try/catch scan

    private static final Pattern CALL =
            Pattern.compile("\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(|\\bnew\\s+([A-Za-z0-9_.$]+)\\s*\\(");

    /**
     * Multiset of call targets that sit inside the PROTECTED region of a
     * try/catch -- the try body itself, not the handler.
     *
     * A brace-depth scan over the decompiled text, with string and character
     * literals and line comments removed first so their braces do not count.
     * Approximate by construction; it is used to compare two texts of the SAME
     * class, so an approximation that is wrong the same way on both sides still
     * detects a change.
     */
    static Map<String, Integer> inTryCalls(String src) {
        Map<String, Integer> bag = new LinkedHashMap<>();
        Deque<Integer> tryAt = new ArrayDeque<>();
        int depth = 0;
        for (String raw : src.split("\n")) {
            String line = strip(raw);
            String trimmed = line.trim();
            boolean handler = trimmed.startsWith("} catch") || trimmed.startsWith("} finally")
                    || trimmed.startsWith("}catch") || trimmed.startsWith("}finally");
            if (handler && !tryAt.isEmpty()) {
                tryAt.pop();
            }
            if (!tryAt.isEmpty()) {
                count(bag, line);
            }
            int before = depth;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '{') { depth++; } else if (c == '}') { depth--; }
            }
            if (trimmed.startsWith("try {") || trimmed.equals("try")) {
                tryAt.push(before);
            }
            while (!tryAt.isEmpty() && depth <= tryAt.peek()) {
                tryAt.pop();
            }
        }
        return bag;
    }

    /** Every call in the text, in or out of a try. */
    static Map<String, Integer> allCalls(String src) {
        Map<String, Integer> bag = new LinkedHashMap<>();
        for (String raw : src.split("\n")) {
            count(bag, strip(raw));
        }
        return bag;
    }

    static int tryBlocks(String src) {
        int n = 0;
        for (String raw : src.split("\n")) {
            String t = strip(raw).trim();
            if (t.startsWith("try {") || t.equals("try")) { n++; }
        }
        return n;
    }

    private static void count(Map<String, Integer> bag, String line) {
        Matcher m = CALL.matcher(line);
        while (m.find()) {
            String tok = m.group(1) != null ? m.group(1) : "new " + m.group(2);
            bag.merge(tok, 1, Integer::sum);
        }
    }

    /** Remove string literals, char literals and line comments. */
    private static String strip(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inStr = false, inChr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; } else if (c == '"') { inStr = false; }
                continue;
            }
            if (inChr) {
                if (c == '\\') { i++; } else if (c == '\'') { inChr = false; }
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChr = true; continue; }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') { break; }
            sb.append(c);
        }
        return sb.toString();
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
}

package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.DocClaims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A piece of prose whose every measurement-shaped number is accounted for.
 *
 * <h2>What "accounted for" means</h2>
 *
 * Two blocks describe the numbers a text states. Both are fenced, both are
 * machinery rather than documentation:
 *
 * <pre>
 *   ```claims
 *   id | apk | call | unit | expected | cite
 *   ```
 *
 *   ```unverified
 *   value | why it carries no guarantee
 *   ```
 * </pre>
 *
 * A {@code claims} line ties a figure to a {@link DocClaims} measurement that
 * re-runs it. An {@code unverified} line says out loud that a figure is NOT
 * re-run and why -- a timing, a historical figure describing code that no
 * longer exists, a sample whose population cannot be pinned. Between them they
 * have to cover every figure in the prose: {@link #unregisteredFigures()} is
 * the check that closes the loop, and without it a new number can be written
 * into the text and never be checked by anything, which is how the consumer
 * reference drifted for six rounds while a table of "verified" figures sat two
 * screens above.
 *
 * <h2>What counts as a figure</h2>
 *
 * Not every digit -- "1 of 25 differing classes", "phase 2", "the top 250" are
 * structure, not measurement, and demanding a registration for each would make
 * the mechanism unusable and therefore ignored. What is scanned is the shape a
 * measurement takes in this file: a thousands-separated number, a percentage, a
 * byte/second size with its unit, or a bare integer of four digits or more.
 * That is the shape every figure that has ever gone stale here was written in.
 * ISO dates are masked out first, being neither.
 */
final class CheckedFigures {

    /** One line of the {@code claims} block. */
    static final class Claim {
        final String id;
        final String apk;
        final String call;
        final String unit;
        final String expected;
        final String cite;
        final int line;

        Claim(String[] f, int line) {
            this.id = f[0].trim();
            this.apk = f[1].trim();
            this.call = f[2].trim();
            this.unit = f[3].trim();
            this.expected = f[4].trim();
            this.cite = f[5].trim();
            this.line = line;
        }
    }

    /** One line of the {@code unverified} block. */
    static final class Declared {
        final String value;
        final String why;
        final int line;

        Declared(String value, String why, int line) {
            this.value = value.trim();
            this.why = why.trim();
            this.line = line;
        }
    }

    // ------------------------------------------------------- figure shapes

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * In priority order; an earlier match wins the characters it covers, so
     * "411,474 B" is one separated number and not a stray "474 B".
     */
    private static final Pattern[] SHAPES = {
            Pattern.compile("(?<![\\d,.])\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?![\\d,])"),
            Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)?[ ]?%"),
            Pattern.compile("(?<![\\w,.])\\d+(?:\\.\\d+)?[ ]?(?:MiB|GiB|KiB|MB|GB|KB|kB|s)\\b"),
            Pattern.compile("(?<![\\w,.])\\d{4,}(?![\\w,.])"),
    };

    /** A figure and where it was written. */
    static final class Figure {
        final String text;
        final int line;
        final String context;

        Figure(String text, int line, String context) {
            this.text = text;
            this.line = line;
            this.context = context;
        }

        /** Commas and the space before a unit are formatting, not value. */
        String canonical() {
            return text.replace(",", "").replace(" ", "");
        }
    }

    static List<Figure> figuresIn(String text) {
        String masked = DATE.matcher(text).replaceAll(m -> "#".repeat(m.group().length()));
        boolean[] taken = new boolean[masked.length()];
        Map<Integer, Figure> found = new LinkedHashMap<>();
        for (Pattern shape : SHAPES) {
            Matcher m = shape.matcher(masked);
            while (m.find()) {
                boolean overlaps = false;
                for (int i = m.start(); i < m.end(); i++) {
                    overlaps |= taken[i];
                }
                if (overlaps) {
                    continue;
                }
                for (int i = m.start(); i < m.end(); i++) {
                    taken[i] = true;
                }
                found.put(m.start(), new Figure(text.substring(m.start(), m.end()),
                        lineOf(text, m.start()), contextAt(text, m.start(), m.end())));
            }
        }
        List<Integer> starts = new ArrayList<>(found.keySet());
        starts.sort(null);
        List<Figure> out = new ArrayList<>();
        for (int at : starts) {
            out.add(found.get(at));
        }
        return out;
    }

    private static int lineOf(String text, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String contextAt(String text, int from, int to) {
        int a = Math.max(0, from - 45);
        int b = Math.min(text.length(), to + 20);
        return text.substring(a, b).replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------- parsing

    final String label;
    final String blocksFile;
    final List<Claim> claims = new ArrayList<>();
    final List<Declared> declared = new ArrayList<>();
    final String prose;

    private CheckedFigures(String label, String blocksFile, String blocksText, String proseText) {
        this.label = label;
        this.blocksFile = blocksFile;
        this.prose = proseText;
        String[] lines = blocksText.split("\n", -1);
        String block = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```claims")) {
                block = "claims";
                continue;
            }
            if (line.startsWith("```unverified")) {
                block = "unverified";
                continue;
            }
            if (block != null && line.startsWith("```")) {
                block = null;
                continue;
            }
            if (block == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (block.equals("claims")) {
                String[] f = line.split("\\|", 6);
                if (f.length != 6) {
                    throw new AssertionError(blocksFile + " claims line " + (i + 1)
                            + " has " + f.length + " fields, want 6: " + line);
                }
                claims.add(new Claim(f, i + 1));
            } else {
                String[] f = line.split("\\|", 2);
                if (f.length != 2) {
                    throw new AssertionError(blocksFile + " unverified line " + (i + 1)
                            + " has " + f.length + " fields, want 2: " + line);
                }
                declared.add(new Declared(f[0], f[1], i + 1));
            }
        }
    }

    /**
     * @param blocksText the file carrying the two fenced blocks
     * @param proseText  the text a reader (or a model) actually reads. For the
     *                   markdown they are the same file minus its blocks; for
     *                   the daemon's tool descriptions they are two different
     *                   files entirely.
     */
    static CheckedFigures of(String label, String blocksFile, String blocksText, String proseText) {
        return new CheckedFigures(label, blocksFile, blocksText, proseText);
    }

    /** A file's prose: itself, with the fenced blocks blanked line for line. */
    static String proseOf(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```claims") || line.startsWith("```unverified")) {
                inBlock = true;
                sb.append('\n');
                continue;
            }
            if (inBlock) {
                if (line.startsWith("```")) {
                    inBlock = false;
                }
                sb.append('\n');
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------- the checks

    /** Ids stated twice, which would let one copy rot unnoticed. */
    List<String> duplicateIds() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> dupes = new ArrayList<>();
        for (Claim c : claims) {
            if (!seen.add(c.id)) {
                dupes.add(c.id + " (" + blocksFile + ":" + c.line + ")");
            }
        }
        return dupes;
    }

    /** Claimed here with nothing in {@link DocClaims} that re-runs it. */
    List<String> unmeasurable() {
        List<String> out = new ArrayList<>();
        for (Claim c : claims) {
            if (DocClaims.byId(c.id) == null) {
                out.add(c.id + " (" + blocksFile + ":" + c.line + ")");
            }
        }
        return out;
    }

    /** The block describes a different call, APK or unit than was measured. */
    List<String> misdescribed() {
        List<String> out = new ArrayList<>();
        for (Claim c : claims) {
            DocClaims.Claim probe = DocClaims.byId(c.id);
            if (probe == null) {
                continue;
            }
            if (!probe.apk.equals(c.apk)) {
                out.add(c.id + ".apk: " + c.apk + " != " + probe.apk);
            }
            if (!probe.call.equals(c.call)) {
                out.add(c.id + ".call: " + c.call + " != " + probe.call);
            }
            if (!probe.unit.equals(c.unit)) {
                out.add(c.id + ".unit: " + c.unit + " != " + probe.unit);
            }
        }
        return out;
    }

    /**
     * The number in the block is the number in the sentence.
     *
     * Every cite has to occur in the prose exactly once and to carry its value
     * verbatim. A checked table beside unchecked prose is the failure this
     * whole mechanism replaces.
     */
    List<String> citationProblems() {
        String flat = normalise(prose);
        List<String> out = new ArrayList<>();
        for (Claim c : claims) {
            String cite = normalise(c.cite);
            int n = occurrences(flat, cite);
            if (n != 1) {
                out.add(c.id + ": cite occurs " + n + " times in " + label
                        + ", want exactly 1: " + c.cite);
            }
            // Grouping is formatting: a marker prints "48628640" and the
            // sentence quoting it writes "48,628,640". The value has to be
            // there; the commas do not.
            String value = normalise(c.expected);
            if (!cite.contains(value)
                    && !cite.replace(",", "").contains(value.replace(",", ""))) {
                out.add(c.id + ": cite does not carry the value " + c.expected + ": " + c.cite);
            }
        }
        return out;
    }

    /**
     * A figure in the prose that neither block accounts for.
     *
     * This is the check that stops the doc growing a number nobody re-runs.
     * Either register it in {@link DocClaims} and cite it from the claims
     * block, or state in the unverified block why it carries no guarantee.
     */
    List<String> unregisteredFigures() {
        Set<String> accounted = accountedValues();
        Map<String, Figure> unregistered = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Figure f : figuresIn(prose)) {
            if (accounted.contains(f.canonical())) {
                continue;
            }
            unregistered.putIfAbsent(f.text, f);
            counts.merge(f.text, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Figure> e : unregistered.entrySet()) {
            Figure f = e.getValue();
            out.add(f.text + " (" + label + " line " + f.line
                    + (counts.get(f.text) > 1 ? ", " + counts.get(f.text) + " occurrences" : "")
                    + "): ..." + f.context + "... -- register it in DocClaims and cite it from"
                    + " the claims block of " + blocksFile
                    + ", or say in that file's unverified block why it carries no guarantee");
        }
        return out;
    }

    /** Declared unverified, but the prose no longer says it. */
    List<String> deadDeclarations() {
        Set<String> inProse = new LinkedHashSet<>();
        for (Figure f : figuresIn(prose)) {
            inProse.add(f.canonical());
        }
        List<String> out = new ArrayList<>();
        for (Declared d : declared) {
            if (d.why.isEmpty()) {
                out.add(d.value + " (" + blocksFile + ":" + d.line
                        + ") is declared unverified with no reason");
            }
            if (!inProse.contains(canonical(d.value))) {
                out.add(d.value + " (" + blocksFile + ":" + d.line
                        + ") is declared unverified but " + label + " no longer states it");
            }
        }
        return out;
    }

    /** Declared unverified while a claim measures the same value. */
    List<String> declaredYetMeasured() {
        Set<String> claimed = claimedValues();
        List<String> out = new ArrayList<>();
        for (Declared d : declared) {
            if (claimed.contains(canonical(d.value))) {
                out.add(d.value + " (" + blocksFile + ":" + d.line
                        + ") is declared unverified, but the claims block measures it");
            }
        }
        return out;
    }

    /** How many figures of the prose each block accounts for. */
    int[] census() {
        Set<String> claimed = claimedValues();
        Set<String> declaredSet = new LinkedHashSet<>();
        for (Declared d : declared) {
            declaredSet.add(canonical(d.value));
        }
        int measured = 0;
        int stated = 0;
        int neither = 0;
        for (Figure f : figuresIn(prose)) {
            if (claimed.contains(f.canonical())) {
                measured++;
            } else if (declaredSet.contains(f.canonical())) {
                stated++;
            } else {
                neither++;
            }
        }
        return new int[]{measured, stated, neither};
    }

    private Set<String> claimedValues() {
        Set<String> out = new LinkedHashSet<>();
        for (Claim c : claims) {
            for (Figure f : figuresIn(c.expected)) {
                out.add(f.canonical());
            }
        }
        return out;
    }

    private Set<String> accountedValues() {
        Set<String> out = claimedValues();
        for (Declared d : declared) {
            out.add(canonical(d.value));
        }
        return out;
    }

    private static String canonical(String s) {
        return s.trim().replace(",", "").replace(" ", "");
    }

    static String normalise(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static int occurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return -1;
        }
        int n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }
}

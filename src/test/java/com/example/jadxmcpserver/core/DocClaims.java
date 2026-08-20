package com.example.jadxmcpserver.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every figure a tool description states about this server, as a call this
 * process can re-run.
 *
 * <h2>Why this exists</h2>
 *
 * A {@code @mcp.tool} docstring is the text a model is handed when it decides
 * whether to call a tool. A number in it that nothing re-runs is a number that
 * drifts: this registry ties each stated figure to a measurement, so a stale
 * one fails a test instead of misleading a caller.
 *
 * <h2>The contract</h2>
 *
 * {@code src/test/resources/tool-docstring-claims.md} carries a fenced
 * {@code claims} block, one line per figure:
 *
 * <pre>
 *   id | apk | call | unit | expected | cite
 * </pre>
 *
 * {@code id}, {@code apk}, {@code call} and {@code unit} must match a claim
 * registered here, exactly. {@code expected} is the value this class measures.
 * {@code cite} is a fragment of the docstring prose that must occur exactly once
 * and carry {@code expected} verbatim.
 * {@link com.example.jadxmcpserver.ToolDocstringTest} checks all four agree.
 *
 * <h2>What is here</h2>
 *
 * Figures that are a property of the source tree -- the default caps, the tool
 * count -- so the check runs anywhere without an APK. A figure that is a
 * property of one particular application is deliberately not stated in a tool
 * description, so nothing here depends on a specific APK being present.
 */
public final class DocClaims {

    /** The committed fixture, used by callers that want a small real APK. */
    public static final String SMALL_APK = "misc/DivaApplication.apk";

    /**
     * A larger APK, if one is provided. Not committed to this repository, so
     * absent by default; point at one with {@code -DlargeApk=...}. No claim
     * here needs it -- it exists only so a caller can ask whether one is
     * present without special-casing.
     */
    public static final String LARGE_APK = System.getProperty("largeApk", "");

    /** Produces one figure, in the doc's own formatting. */
    public interface Measure {
        String value() throws Exception;
    }

    public static final class Claim {
        public final String id;
        public final String apk;
        public final String call;
        public final String unit;
        public final Measure measure;

        Claim(String id, String apk, String call, String unit, Measure measure) {
            this.id = id;
            this.apk = apk;
            this.call = call;
            this.unit = unit;
            this.measure = measure;
        }
    }

    private static final Map<String, Claim> CLAIMS = new LinkedHashMap<>();

    private DocClaims() {
    }

    public static boolean largeApkPresent() {
        return !LARGE_APK.isEmpty() && new File(LARGE_APK).exists();
    }

    /** No APK is retained here; kept so callers can close in a uniform way. */
    public static synchronized void closeAll() {
    }

    public static Collection<Claim> all() {
        return CLAIMS.values();
    }

    public static Claim byId(String id) {
        return CLAIMS.get(id);
    }

    private static void claim(String id, String apk, String call, String unit, Measure m) {
        if (CLAIMS.put(id, new Claim(id, apk, call, unit, memo(m))) != null) {
            throw new IllegalStateException("duplicate claim id: " + id);
        }
    }

    /** A figure that is a property of the source tree, needing no APK. */
    private static void code(String id, String call, String unit, Measure m) {
        claim(id, "code", call, unit, m);
    }

    private static Measure memo(Measure m) {
        String[] cached = new String[1];
        return () -> {
            if (cached[0] == null) {
                cached[0] = m.value();
            }
            return cached[0];
        };
    }

    // ------------------------------------------------------------ the claims

    static {
        code("tool.count", "grep -c '@mcp.tool' mcp_server/jadx_mcp.py", "tools",
                () -> n(countIn(Path.of("mcp_server/jadx_mcp.py"), "@mcp.tool")));
        code("cap.entries.default", "JadxAnalyzerCore.DEFAULT_RESULT_LIMIT", "entries",
                () -> String.valueOf(JadxAnalyzerCore.DEFAULT_RESULT_LIMIT));
        code("cap.bytes.default", "JadxAnalyzerCore.DEFAULT_MAX_BYTES", "bytes",
                () -> n(JadxAnalyzerCore.DEFAULT_MAX_BYTES));
        // The second byte budget, and deliberately its own claim rather than a
        // restatement of the first: the two guard different things.
        // DEFAULT_MAX_BYTES asks "is this response absurd"; this one asks "is
        // this response worth what the caller pays to carry it on every later
        // turn".
        code("cap.bytes.source.default", "JadxAnalyzerCore.DEFAULT_SOFT_BYTES", "bytes",
                () -> n(JadxAnalyzerCore.DEFAULT_SOFT_BYTES));
        code("cap.callsites.default", "JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT",
                "entries per sink",
                () -> n(JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT));
    }

    // ------------------------------------------------------------- measuring

    static String n(long v) {
        return String.format("%,d", v);
    }

    static long countIn(Path file, String needle) throws Exception {
        long n = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }
}

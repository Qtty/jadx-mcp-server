package com.example.jadxmcpserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three guards this round is asked about, measured rather than argued.
 *
 * A probe, not a test: it asserts nothing and prints. Run it by name.
 */
class GuardAsymmetryProbe {

    static final String BIG_APK = "misc/DivaApplication.apk";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static long wireBytes(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void measure() throws Exception {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        JadxAnalyzerCore core = new JadxAnalyzerCore(BIG_APK);
        core.loadApk();
        try {
            int d = JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT;

            // --- 1. findSinkCallSites at the DEFAULT limit ---
            Map<String, Object> def = core.findSinkCallSites(null, d);
            System.out.println("SINK default catalogue, limit=" + d + ": "
                    + wireBytes(def) + " bytes, keys=" + def.size()
                    + ", oversized=" + def.containsKey(
                            JadxAnalyzerCore.OVERSIZED_MARKER_PREFIX));

            // A caller-chosen sink LIST with the limit left alone. Bare method
            // names are legal tokens the docstring documents.
            List<String> bare = new ArrayList<>();
            for (String n : new String[]{
                    "<init>", "toString", "equals", "hashCode", "run", "get", "set",
                    "onCreate", "onClick", "getName", "getValue", "setValue", "size",
                    "add", "remove", "close", "start", "stop", "write", "read",
                    "next", "hasNext", "invoke", "call", "apply", "accept", "test",
                    "compare", "clone", "finalize", "onDestroy", "onResume",
                    "onPause", "onStart", "onStop", "values", "valueOf", "of",
                    "from", "to", "parse", "format", "encode", "decode", "load",
                    "save", "put", "getInstance", "newInstance", "create",
                    "build", "clear", "isEmpty", "contains", "indexOf", "length",
                    "charAt", "substring", "split", "trim", "replace", "concat",
                    "append", "flush", "open", "connect", "send", "receive",
                    "init", "reset", "update", "cancel", "notify", "wait",
                    "getId", "getType", "getKey", "getData", "getText"}) {
                bare.add(n);
            }
            for (int n : new int[]{20, 40, 60, 80}) {
                List<String> subset = bare.subList(0, Math.min(n, bare.size()));
                Map<String, Object> r = core.findSinkCallSites(subset, d);
                System.out.println("SINK " + n + " bare names, limit=" + d + ": "
                        + wireBytes(r) + " bytes, oversized=" + r.containsKey(
                                JadxAnalyzerCore.OVERSIZED_MARKER_PREFIX)
                        + ", truncatedSinks=" + (r.containsKey(
                                JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)
                                ? ((List<?>) r.get(
                                        JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)).size()
                                : 0));
            }

            // --- 2. the three tools with a count cap and no byte guard ---
            List<Map<String, Object>> exported = core.getExportedComponentsAsMap();
            System.out.println("EXPORTED: " + exported.size() + " entries, "
                    + wireBytes(exported) + " bytes, widest entry "
                    + widest(exported) + " bytes");
            List<Map<String, Object>> deeplink = core.getDeeplinkComponents();
            System.out.println("DEEPLINK: " + deeplink.size() + " entries, "
                    + wireBytes(deeplink) + " bytes, widest entry "
                    + widest(deeplink) + " bytes");

            Map<String, Object> obj = core.getTypeHierarchy("java.lang.Object");
            System.out.println("HIERARCHY Object: " + wireBytes(obj) + " bytes");
            long widestName = 0;
            String widestOwner = null;
            for (String name : core.getAllClasses(null, 0)) {
                if (name.startsWith("@")) {
                    continue;
                }
                if (name.length() > widestName) {
                    widestName = name.length();
                    widestOwner = name;
                }
            }
            System.out.println("HIERARCHY widest class NAME: " + widestName
                    + " chars (" + widestOwner + ")");

            // --- 3. the doc numbers ---
            Map<String, List<String>> in = core.searchMethodByName("in", 0);
            long classesWithHit = in.keySet().stream()
                    .filter(k -> !k.startsWith("@")).count();
            System.out.println("searchMethodByName(\"in\"): " + classesWithHit
                    + " classes (map has " + in.size() + " keys)");

            String smali = core.getSmaliOfMethod(
                    "kotlin.collections.ArraysKt___ArraysKt", "joinTo");
            System.out.println("getSmaliOfMethod ArraysKt.joinTo: "
                    + smali.length() + " chars, " + wireBytes(smali) + " wire bytes");
        } finally {
            core.close();
        }
    }

    private static long widest(List<Map<String, Object>> entries) throws Exception {
        long max = 0;
        for (Map<String, Object> e : entries) {
            max = Math.max(max, wireBytes(e));
        }
        return max;
    }
}

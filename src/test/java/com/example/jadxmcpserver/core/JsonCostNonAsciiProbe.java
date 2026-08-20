package com.example.jadxmcpserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. How wrong is {@link JadxAnalyzerCore#jsonCostOf} on text
 * that is not ASCII?
 *
 * Every {@code @oversized} decision on a list- or map-returning tool rests on
 * that estimate, and it charges a String {@code length() + 3} -- UTF-16 code
 * units, not the UTF-8 bytes the daemon actually writes. A BMP character
 * outside ASCII serialises to two or three bytes, so the estimate can be a
 * third of the truth on text that is all CJK, and the JSON-cost estimator bounds
 * the error only against payloads that happen to be ASCII.
 *
 * Two questions, and they have different answers:
 *
 *   1. the ARITHMETIC worst case -- what the ratio is on a string that is
 *      entirely non-ASCII. That is a property of UTF-8 and needs no APK.
 *   2. the REAL worst case -- how much non-ASCII this server's answers actually
 *      contain on a production APK, which is what decides whether the guard is
 *      wrong in practice or only in principle.
 *
 * WHAT IT MEASURED, 2026-08-08, with the old length()-based estimate:
 *
 * <pre>
 *   arithmetic   all-ASCII        estimate 100% of real
 *                Latin-1           57%
 *                CJK               39%      <- the worst case
 *                emoji (surrogates) 57%
 *
 *   real         0 of a large production APK's 141,461 class names hold a non-ASCII char
 *                0 of its 16,559 resource file names
 *                0 of the strings in its 47 component records
 *                every wide answer within 0.007% of the real serialisation,
 *                and the only two that under-reported did so by 15 bytes
 *                (the sink sweep's quoted names) and 1 byte
 * </pre>
 *
 * So the briefed "under-reports by up to 50%" was wrong twice over: the
 * arithmetic worst case is a 61% under-report, not 50%, and on the APKs this
 * server is measured against the error is zero, not the worst case. Both halves
 * mattered -- one says the guard could be badly wrong, the other says nothing
 * observed here ever was.
 *
 * Run: java -cp target/test-classes:target/classes:$(cat /tmp/jadx-cp.txt) \
 *          com.example.jadxmcpserver.core.JsonCostNonAsciiProbe [apk]
 */
public class JsonCostNonAsciiProbe {

    static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        arithmetic();

        String apk = args.length > 0 ? args[0]
                : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        try {
            real(core);
        } finally {
            core.close();
        }
    }

    private static void arithmetic() throws Exception {
        System.out.println("== the arithmetic worst case, no APK needed ==");
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("ascii", "abcdefghij");
        cases.put("latin-1 supplement (2 UTF-8 bytes each)", "éééééééééé");
        cases.put("CJK (3 UTF-8 bytes each)", "滑行出行中文名称测试");
        cases.put("emoji, surrogate pairs (4 bytes / 2 chars)", "😀😀😀😀😀");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            long est = JadxAnalyzerCore.jsonCostOf(e.getValue());
            long real = serialised(e.getValue()) + 1;  // + the phantom comma
            System.out.printf("  %-44s estimate %4d  real %4d  estimate is %.0f%% of real%n",
                    e.getKey(), est, real, 100.0 * est / real);
        }
        System.out.println();
    }

    private static void real(JadxAnalyzerCore core) throws Exception {
        System.out.println("== every wide list/map answer on this APK, estimate vs real ==");
        List<Object[]> rows = new ArrayList<>();
        int d = JadxAnalyzerCore.DEFAULT_RESULT_LIMIT;
        rows.add(new Object[]{"get_all_classes(limit: 0)", core.getAllClasses(null, 0)});
        rows.add(new Object[]{"get_all_resource_file_names(limit: 0)",
                core.getAllResourceFileNames(null, 0)});
        rows.add(new Object[]{"get_deeplink_components()", core.getDeeplinkComponents()});
        rows.add(new Object[]{"get_exported_components()", core.getExportedComponentsAsMap()});
        rows.add(new Object[]{"find_sink_call_sites()",
                core.findSinkCallSites(null, JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT)});
        rows.add(new Object[]{"get_xrefs_to_class(Intrinsics, 0)",
                core.getXrefsToClass("kotlin.jvm.internal.Intrinsics", 0)});
        rows.add(new Object[]{"search_method_by_name(\"et\")", core.searchMethodByName("et", d)});
        rows.add(new Object[]{"get_fields_of_class(C29225R.id, 0)",
                core.getFieldsOfClass("com.example.app.R.id", 0)});
        rows.add(new Object[]{"get_type_hierarchy(Object)", core.getTypeHierarchy("java.lang.Object")});
        rows.add(new Object[]{"get_overrides(Runnable.run, 0)",
                core.getOverrides("java.lang.Runnable", "run", null, 0)});

        for (Object[] row : rows) {
            long est = JadxAnalyzerCore.jsonCostOf(row[1]);
            long real = serialised(row[1]);
            System.out.printf("  %-38s estimate %,12d  real %,12d  %+.4f%%  %s%n",
                    row[0], est, real, 100.0 * (est - real) / real,
                    est < real ? "UNDER" : "over/exact");
        }

        // Where the non-ASCII actually is, if anywhere: the widest name lists.
        System.out.println();
        System.out.println("== non-ASCII census over the name-shaped answers ==");
        census("class names", core.getAllClasses(null, 0));
        census("resource file names", core.getAllResourceFileNames(null, 0));
        censusStrings("deeplink component values", flatten(core.getDeeplinkComponents()));
    }

    private static List<String> flatten(Object value) {
        List<String> out = new ArrayList<>();
        collect(value, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object value, List<String> out) {
        if (value instanceof String) {
            out.add((String) value);
        } else if (value instanceof Map) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
                out.add(String.valueOf(e.getKey()));
                collect(e.getValue(), out);
            }
        } else if (value instanceof Iterable) {
            for (Object o : (Iterable<Object>) value) {
                collect(o, out);
            }
        }
    }

    private static void census(String what, List<String> names) {
        censusStrings(what, names);
    }

    private static void censusStrings(String what, List<String> names) {
        long chars = 0, bytes = 0, nonAscii = 0, worstEntryChars = 0, worstEntryBytes = 0;
        String worst = null;
        for (String n : names) {
            long c = n.length();
            long b = n.getBytes(StandardCharsets.UTF_8).length;
            chars += c;
            bytes += b;
            if (b > c) {
                nonAscii++;
                if (b - c > worstEntryBytes - worstEntryChars) {
                    worstEntryChars = c;
                    worstEntryBytes = b;
                    worst = n;
                }
            }
        }
        System.out.printf("  %-28s %,10d entries, %,d entries hold non-ASCII;"
                        + " chars %,d vs UTF-8 bytes %,d (%+.4f%%)%n",
                what, names.size(), nonAscii, chars, bytes,
                chars == 0 ? 0.0 : 100.0 * (chars - bytes) / bytes);
        if (worst != null) {
            System.out.printf("    widest single under-report: %d chars, %d bytes -- %s%n",
                    worstEntryChars, worstEntryBytes, worst);
        }
    }

    private static long serialised(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }
}

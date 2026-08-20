package com.example.jadxmcpserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The widest response the DEFAULT list cap can produce, re-taken.
 *
 * DEFAULT_RESULT_LIMIT's javadoc names search_method_by_name("et") at 429,418
 * bytes as the maximum, and this is where that number comes from. Re-run
 * 2026-08-08: the two-character ranking reproduces exactly -- "et" 429,418, "bd"
 * 419,553, "yt" 398,320, "se" 349,339.
 *
 * WHAT THE SWEEP DOES AND DOES NOT ESTABLISH. It covers all 676 two-character
 * queries and, since this round, 520 three-character ones formed by extending
 * the ten widest pairs at each end -- the widest of those is "bda"/"mbd" at
 * 421,310, so "et" survives. That bounds SHORT METHOD-NAME SEARCHES. It says
 * nothing about the other twelve tools the cap applies to, and nothing at all
 * about the byte-capped ones: get_class_source at the default DEFAULT_MAX_BYTES
 * is 1,048,578 bytes, 2.4 times wider than anything here. The javadoc used to
 * call this "the widest response this cap can produce" and the test used to
 * assert "the widest capped response this server can produce"; the sweep can
 * carry neither superlative, and both now say what was actually swept. The
 * "519 three-character" figure that stood beside the 676 had no probe behind it
 * at all -- nothing in this file had ever run a three-character sweep.
 *
 * A probe, not a test: it asserts nothing and prints. Run it by name.
 */
class WidestCappedQueryProbe {

    static final String BIG_APK = "misc/DivaApplication.apk";
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sweepEveryShortMethodQuery() throws Exception {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        JadxAnalyzerCore core = new JadxAnalyzerCore(BIG_APK);
        core.loadApk();
        try {
            int d = JadxAnalyzerCore.DEFAULT_RESULT_LIMIT;
            List<Object[]> rows = new ArrayList<>();
            for (char a = 'a'; a <= 'z'; a++) {
                for (char b = 'a'; b <= 'z'; b++) {
                    String q = "" + a + b;
                    Map<String, List<String>> r = core.searchMethodByName(q, d);
                    rows.add(new Object[]{wire(r), q});
                }
                System.out.println("  .. done " + a);
            }
            rows.sort(Comparator.comparingLong(r -> -(Long) r[0]));
            System.out.println("== widest two-character method queries, limit=" + d
                    + ", real serialised bytes ==");
            for (Object[] row : rows.subList(0, 12)) {
                System.out.println("  " + row[0] + "  \"" + row[1] + "\"");
            }

            // Three-character queries, seeded from the widest pairs rather than
            // swept exhaustively: 17,576 of them at ~90 ms each is an hour, and
            // a three-character query is a SUBSTRING of a two-character one's
            // matches, so the widest can only be an extension of a wide pair.
            System.out.println("== three-character queries seeded from the"
                    + " widest pairs ==");
            List<Object[]> three = new ArrayList<>();
            for (Object[] row : rows.subList(0, 10)) {
                String seed = (String) row[1];
                for (char c = 'a'; c <= 'z'; c++) {
                    three.add(new Object[]{wire(core.searchMethodByName(seed + c, d)),
                            seed + c});
                    three.add(new Object[]{wire(core.searchMethodByName(c + seed, d)),
                            c + seed});
                }
            }
            three.sort(Comparator.comparingLong(r -> -(Long) r[0]));
            System.out.println("  " + three.size() + " queries");
            for (Object[] row : three.subList(0, 8)) {
                System.out.println("  " + row[0] + "  \"" + row[1] + "\"");
            }

            System.out.println("== the queries the current test uses ==");
            for (String q : new String[]{"in", "se", "get"}) {
                System.out.println("  " + wire(core.searchMethodByName(q, d))
                        + "  \"" + q + "\"");
            }
            System.out.println("  " + wire(core.searchClassesByKeyword("com", d))
                    + "  keyword \"com\"");
            System.out.println("  " + wire(core.getAllClasses(null, d))
                    + "  getAllClasses()");
            System.out.println("  " + wire(core.getAllClasses("c", d))
                    + "  getAllClasses(\"c\")");

            // The comparison the superlative actually needs: every tool the
            // list cap applies to, on the widest target it has on this APK, plus
            // the byte-capped tool that beats all of them.
            System.out.println("== widest DEFAULT-capped response, per tool ==");
            List<Object[]> tools = new ArrayList<>();
            tools.add(new Object[]{wire(core.searchMethodByName("et", d)),
                    "search_method_by_name(\"et\")"});
            tools.add(new Object[]{wire(core.getXrefsToField("kotlin.Unit", "INSTANCE", d)),
                    "get_xrefs_to_field(Unit.INSTANCE)"});
            tools.add(new Object[]{wire(core.findSinkCallSites(null,
                    JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT)), "find_sink_call_sites()"});
            tools.add(new Object[]{wire(core.getXrefsToMethod(
                    "kotlin.jvm.internal.Intrinsics", "checkNotNullParameter", null, d)),
                    "get_xrefs_to_method(checkNotNullParameter)"});
            tools.add(new Object[]{wire(core.getXrefsToClass(
                    "kotlin.jvm.internal.Intrinsics", d)), "get_xrefs_to_class(Intrinsics)"});
            tools.add(new Object[]{wire(core.getMethodsOfClass(
                    "kotlin.collections.ArraysKt___ArraysKt", d)),
                    "get_methods_of_class(ArraysKt)"});
            tools.add(new Object[]{wire(core.getCallees(
                    "com.example.spi.ServiceRegistry", "<clinit>", null, d)),
                    "get_callees(ServiceRegistry.<clinit>)"});
            tools.add(new Object[]{wire(core.searchClassesByKeyword("com", d)),
                    "search_classes_by_keyword(\"com\")"});
            tools.add(new Object[]{wire(core.getAllResourceFileNames(null, d)),
                    "get_all_resource_file_names()"});
            tools.add(new Object[]{wire(core.getOverrides("java.lang.Runnable", "run", null, d)),
                    "get_overrides(Runnable.run)"});
            tools.add(new Object[]{wire(core.getAllClasses(null, d)), "get_all_classes()"});
            tools.add(new Object[]{wire(core.getTypeHierarchy("java.lang.Object")),
                    "get_type_hierarchy(Object)"});
            tools.add(new Object[]{wire(core.getFieldsOfClass("com.example.app.R.id", d)),
                    "get_fields_of_class(C29225R.id)"});
            tools.add(new Object[]{wire(core.getDeeplinkComponents()),
                    "get_deeplink_components()"});
            tools.add(new Object[]{wire(core.getExportedComponentsAsMap()),
                    "get_exported_components()"});
            tools.add(new Object[]{wire(core.getClassSource("com.example.app.R.id",
                    JadxAnalyzerCore.DEFAULT_MAX_BYTES)),
                    "get_class_source(C29225R.id) -- byte-capped, not this cap"});
            tools.sort(Comparator.comparingLong(r -> -(Long) r[0]));
            for (Object[] row : tools) {
                System.out.println("  " + row[0] + "  " + row[1]);
            }
        } finally {
            core.close();
        }
    }

    private static long wire(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }
}

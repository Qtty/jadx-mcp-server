package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.JadxAnalyzerCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A caller who names no limit must not be able to ask for a file dump by
 * accident.
 *
 * The previous attempt put a two-character floor under the two substring
 * searches and called it a size guard. It is not one: a floor rejects a query
 * that can only be a sweep, and says nothing about a real query that happens to
 * be wide. Measured on a large production APK after that fix,
 * search_method_by_name("in") returned 22,317,076 bytes with HTTP 200 and
 * nothing in the body admitting it -- the exact failure the guard existed to
 * prevent, one character over the floor.
 *
 * The machinery was already there. Every one of these tools took a limit and
 * carried a truncation marker; none of them applied either unless asked. This
 * is the layer where "the caller named no limit" is known, so this is where the
 * default belongs -- next to findSinkCallSites, which had one all along.
 */
class DefaultResultLimitTest {

    static final String APK = "misc/DivaApplication.apk";
    static JadxApkAnalyzerAPI api;

    @BeforeAll
    static void load() throws Exception {
        assumeTrue(new File(APK).exists(), "test fixture " + APK + " not present");
        api = new JadxApkAnalyzerAPI();
        api.loadApk(APK);
    }

    @AfterAll
    static void close() {
        if (api != null) {
            api.close();
        }
    }

    /**
     * The default is a real cap, not a documented intention: this fixture has
     * 1791 classes and a bare call must come back with 1000 of them plus the
     * marker that says so.
     */
    @Test
    void aBareClassListIsCappedAndSaysSo() throws Exception {
        List<String> bare = api.getAllClasses();

        assertThat(bare).hasSize(JadxAnalyzerCore.DEFAULT_RESULT_LIMIT + 1);
        assertThat(bare.get(bare.size() - 1))
                .startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)
                .contains("1791");
    }

    /**
     * "No limit given" and "the default limit" have to be the same request, or
     * the default is a per-tool accident.
     *
     * Four tools of the thirteen that take a limit, and only getAllClasses is
     * over the cap on this 1,791-class fixture -- so this cannot be the test
     * that carries the universal, whatever its name used to say. The sweep over
     * all thirteen, on targets where the cap actually bites, is
     * the default-limit sweep.
     */
    @Test
    void anAbsentLimitIsTheDefaultLimitOnTheToolsThisFixtureCanShow() throws Exception {
        int d = JadxAnalyzerCore.DEFAULT_RESULT_LIMIT;

        assertThat(api.getAllClasses()).isEqualTo(api.getAllClasses(null, d));
        assertThat(api.getAllClasses("jakhar", null)).isEqualTo(api.getAllClasses("jakhar", d));
        assertThat(api.searchClassesByKeyword("Activity"))
                .isEqualTo(api.searchClassesByKeyword("Activity", d));
        assertThat(api.searchMethod("get")).isEqualTo(api.searchMethod("get", d));
        assertThat(api.findSinkCallSites(null, null)).isEqualTo(
                api.findSinkCallSites(null, JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT));
    }

    /**
     * The argument against a default is that a caller who reads the whole list
     * silently gets less. It is not silent -- the marker names the real total --
     * and the whole list is still one parameter away, unchanged.
     */
    @Test
    void limitZeroStillMeansEverythingAndIsUnchangedByTheDefault() throws Exception {
        List<String> all = api.getAllClasses(null, 0);

        assertThat(all).hasSize(1791);
        assertThat(all).noneMatch(n -> n.startsWith("@"));
        assertThat(api.searchClassesByKeyword("Activity", 0))
                .isEqualTo(api.searchClassesByKeyword("Activity", 1_000_000));
        assertThat(api.searchMethod("get", 0))
                .isEqualTo(api.searchMethod("get", 1_000_000));
    }

    /**
     * A query narrow enough to fit under the cap is byte-for-byte what it was
     * before the cap existed -- which is most real calls, and the reason the
     * number is 1000 rather than 100.
     */
    @Test
    void anAnswerThatFitsUnderTheCapIsUntouched() throws Exception {
        assertThat(api.searchClassesByKeyword("LogActivity"))
                .isEqualTo(api.searchClassesByKeyword("LogActivity", 0))
                .noneMatch(n -> n.startsWith("@"));
        assertThat(api.searchMethod("onCreate"))
                .isEqualTo(api.searchMethod("onCreate", 0));
    }

    /**
     * The one check a caller should have to write, on the two markers this
     * 1,791-class fixture can produce.
     *
     * Named for what it covers rather than for the universal, which is the same
     * correction the test above already carries. @oversized needs a response
     * over 1 MB and @ambiguous needs an overload set, and neither is reachable
     * from these five calls -- so the sweep over all four markers and all five
     * container shapes is
     * the marker-uniformity checks, on the APK
     * where they all occur.
     */
    @Test
    void everyMarkerThisFixtureProducesIsASelfDescribingString() throws Exception {
        assertMarker(last(api.getAllClasses()), JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
        assertMarker(last(api.searchClassesByKeyword("a")),
                JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
        assertMarker(last(api.searchClassesByKeyword("Activity", 1)),
                JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);

        Map<String, List<String>> refused = api.searchMethod("a");
        assertThat(refused).containsOnlyKeys(JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
        assertMarker(refused.get(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).get(0),
                JadxAnalyzerCore.REJECTED_MARKER_PREFIX);

        Map<String, List<String>> capped = api.searchMethod("get", 1);
        assertMarker(capped.get(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX).get(0),
                JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
    }

    private static String last(List<String> list) {
        return list.get(list.size() - 1);
    }

    private static void assertMarker(String value, String prefix) {
        assertThat(value).startsWith(prefix + ": ").hasSizeGreaterThan(prefix.length() + 20);
    }
}

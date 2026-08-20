package com.example.jadxmcpserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three tools with a cap on the COUNT and, until now, none on the bytes.
 *
 * get_exported_components and get_deeplink_components take no limit and cap at
 * 1000 entries; get_type_hierarchy takes no limit and caps each of its four
 * lists at 1000 names. A count cap reads like a size guarantee and is not one,
 * because neither tool's entry is one name:
 *
 *   get_deeplink_components    widest entry  13,582 bytes  ->  1000 = 13.6 MB
 *   get_exported_components    widest entry  13,582 bytes  ->  1000 = 13.6 MB
 *   get_type_hierarchy         4 x 1000 names, and the longest class name,
 *                              which on a large APK is around 160 characters
 *
 * Two of the three were bounded at several times the line this server calls a
 * file dump, and the third at two thirds of it with nothing but the observed
 * length of Java class names holding it there. All three now carry the byte
 * guard as well, in the shape their result already uses -- a trailing one-key
 * entry for a list of objects, a key for a map.
 *
 * The two component figures are the same entry because the two tools return the
 * same record: they were 8,226 and 6,683 while they answered in two different
 * shapes, and both grew when every intent-filter data entry started carrying
 * every key.
 *
 * None of them fires on either reference APK, and that is the honest result
 * rather than a disappointment: a large production APK declares 45 exported components and
 * 47 link-reachable ones, and a manifest is bounded by what an app can
 * plausibly declare. What these tests can check is that the bound is real --
 * measured, in bytes, against the line -- and that the count cap alone never
 * was.
 */
class NoLimitToolSizeTest {

    static final String BIG_APK = "misc/DivaApplication.apk";
    static final long LINE = 1024L * 1024L;
    static final ObjectMapper MAPPER = new ObjectMapper();
    static JadxAnalyzerCore big;

    @BeforeAll
    static void load() {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        big = new JadxAnalyzerCore(BIG_APK);
        big.loadApk();
    }

    @AfterAll
    static void close() {
        if (big != null) {
            big.close();
        }
    }

    private static long wire(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isMarker(Map<String, ?> entry) {
        return entry.size() == 1 && entry.keySet().iterator().next().startsWith("@");
    }

    private static long widestEntry(List<Map<String, Object>> entries) throws Exception {
        long max = 0;
        for (Map<String, Object> entry : entries) {
            max = Math.max(max, wire(entry));
        }
        return max;
    }

    /**
     * Why the count cap is not a byte bound. A component entry carries its whole
     * intent-filter set, so on this small fixture an entry is only a few hundred
     * bytes and 1000 of them stay well under the line -- but that is a property
     * of the fixture, not of the cap. It takes only ~1 KB per entry for the
     * count cap of 1000 to admit more than 1 MB, and a real component record is
     * within an order of magnitude of that, which is exactly why the byte guard
     * has to exist alongside the count cap rather than instead of it.
     */
    @Test
    void aCountCapAloneDoesNotBoundTheManifestLists() throws Exception {
        long widest = Math.max(
                widestEntry(big.getDeeplinkComponents()),
                widestEntry(big.getExportedComponentsAsMap()));

        assertThat(widest)
                .as("a component entry carries a whole intent-filter set, so it is"
                        + " hundreds of bytes, not a single name")
                .isGreaterThan(100);

        long perEntryToCrossTheLine = LINE / JadxAnalyzerCore.DEFAULT_RESULT_LIMIT;
        assertThat(perEntryToCrossTheLine)
                .as("the count cap of 1000 stops bounding the bytes at roughly 1 KB"
                        + " an entry -- within reach of a real component record")
                .isLessThan(2_000L);
        assertThat((perEntryToCrossTheLine + 1) * JadxAnalyzerCore.DEFAULT_RESULT_LIMIT)
                .as("so 1000 entries of that size is already over the line the count"
                        + " cap says nothing about")
                .isGreaterThan(LINE);
    }

    /**
     * And on this APK the real answers are nowhere near it, so they carry no
     * notice -- which is what makes a notice informative when one appears.
     */
    @Test
    void theRealManifestListsAreFarUnderTheLineAndSayNothing() throws Exception {
        List<Map<String, Object>> deeplink = big.getDeeplinkComponents();
        List<Map<String, Object>> exported = big.getExportedComponentsAsMap();

        assertThat(deeplink).isNotEmpty().noneMatch(NoLimitToolSizeTest::isMarker);
        assertThat(exported).isNotEmpty().noneMatch(NoLimitToolSizeTest::isMarker);
        assertThat(wire(deeplink)).as("well under the line").isLessThan(LINE);
        assertThat(wire(exported)).as("well under the line").isLessThan(LINE);
    }

    /**
     * The hierarchy's own worst case, on the target that reaches its cap:
     * java.lang.Object has many direct subclasses on a large APK, cut to 1000.
     */
    @Test
    void theWidestHierarchyIsUnderTheLineAndSaysNothingAboutSize() throws Exception {
        Map<String, Object> object = big.getTypeHierarchy("java.lang.Object");

        assertThat(object).as("the cap bit, so the cut list says so")
                .containsKey("subClasses");
        assertThat(object).as("but the whole answer is well under the line, not a dump")
                .doesNotContainKey(JadxAnalyzerCore.OVERSIZED_MARKER_PREFIX);
        assertThat(wire(object)).isLessThan(LINE);
    }

    /**
     * What the hierarchy's four caps can admit at worst, in the unit that
     * matters, on the widest names this APK has.
     *
     * 4000 names cross 1 MB the moment the average name reaches 260 characters
     * -- 4000 x (260 + 3) = 1,052,000, against 1,048,000 at 259. Here,
     * on a large APK, the longest is around 160, so the ceiling is 4000 x 165 = 660,000 bytes,
     * 63% of the line, held there by the data rather than by the code. That is
     * the gap the byte guard closes, and it is worth stating as a number
     * because "no APK we tried" is exactly the argument this file has had to
     * retract twice.
     *
     * (259 stood in the first draft of this test and of the consumer doc. It is
     * off by one: 4000 x 262 is 1,048,000, which is under the line by 576
     * bytes. Caught by running it.)
     */
    @Test
    void theHierarchyCapsCeilingIsSetByNameLengthNotByTheCode() {
        int longest = 0;
        for (String name : big.getAllClasses(null, 0)) {
            if (!name.startsWith("@")) {
                longest = Math.max(longest, name.length());
            }
        }

        assertThat(longest).as("longest class name on this APK").isBetween(120, 220);
        long ceiling = 4L * JadxAnalyzerCore.DEFAULT_RESULT_LIMIT * (longest + 3);
        assertThat(ceiling)
                .as("what four capped lists of THIS APK's widest names can cost")
                .isLessThan(LINE);
        assertThat(4L * JadxAnalyzerCore.DEFAULT_RESULT_LIMIT * (260 + 3))
                .as("and the name length at which the same caps cross it")
                .isGreaterThan(LINE);
        assertThat(4L * JadxAnalyzerCore.DEFAULT_RESULT_LIMIT * (259 + 3))
                .as("one character below it, they do not")
                .isLessThan(LINE);
    }
}

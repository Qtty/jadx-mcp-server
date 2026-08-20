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
 * The number every size guard is decided on, checked against the real thing.
 *
 * Every {@code @oversized} decision in this server is made on an ESTIMATE of
 * what a value will cost once serialised -- the guard cannot afford to
 * serialise a 10 MB answer to find out whether it should have said it was 10
 * MB. An estimate that is wrong makes every guard built on it wrong in the same
 * direction, silently.
 *
 * So it is measured against real serialisation rather than described. This is
 * deliberately NOT a shape where the
 * check re-implemented the guard's own arithmetic and could therefore only ever
 * confirm that the code agreed with itself. Jackson is what the daemon actually
 * writes the response with; it is the authority here, and the estimator is the
 * thing on trial.
 *
 * <h2>The estimate is now exact, and used not to be</h2>
 *
 * ONE error term is left, and it is a constant: +1, because every value is
 * charged for the comma that follows it and the outermost value has no comma
 * after it. That is what makes the nested arithmetic come out exact at every
 * level, so it is paid once at the top.
 *
 * There used to be a second, and it ran the other way -- the guard UNDER-reporting
 * is the direction that makes it silent. A string was charged {@code length()},
 * i.e. UTF-16 code units, against a response written in UTF-8 with JSON escaping:
 *
 *   - one byte per escape. An escaped quote or newline is one char and two
 *     bytes. That is where the default sink sweep's 15-byte shortfall came from:
 *     its truncation notices quote the sink name.
 *   - one or two bytes per non-ASCII character, and two per astral pair. On text
 *     that is entirely CJK the estimate came to 39% of the real size -- a 1 MB
 *     cap letting through 2.7 MB. Neither reference APK contains any (0 of
 *     a large APK's class names, none of its resource file names, none of
 *     the strings in its component records), so this was wrong in principle
 *     rather than in practice -- but "no APK we have tried has a Chinese host in
 *     its manifest" is not a property of the server.
 *
 * Both are gone because {@code jsonCostOf} now weighs a string with
 * {@code jsonCost(char)}, the exact per-character byte cost the BYTE-capped
 * string tools have always cut their answers with. One arithmetic, two families,
 * and the tests below assert equality rather than a tolerance.
 */
class JsonCostEstimateTest {

    static final String BIG_APK = "misc/DivaApplication.apk";
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

    /** What the response really costs on the wire, per the daemon's own writer. */
    private static long serialised(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void assertPredicts(String what, Object value) throws Exception {
        long estimate = JadxAnalyzerCore.jsonCostOf(value);
        long real = serialised(value);

        assertThat(estimate)
                .as(what + ": the estimate is the real size plus the outermost"
                        + " value's phantom trailing comma, exactly -- not within"
                        + " a tolerance. A tolerance is what let a one-sided"
                        + " under-report live here.")
                .isEqualTo(real + 1);
    }

    /**
     * A list of {className, methodName, signature} entries -- the object-list
     * shape the estimate was originally calibrated against.
     */
    @Test
    void itPredictsAnEntryList() throws Exception {
        List<Map<String, String>> xrefs =
                big.getXrefsToClass("jakhar.aseem.diva.LogActivity", 0);

        assertPredicts("xref entries", xrefs);
    }

    @Test
    void itPredictsAFlatNameList() throws Exception {
        assertPredicts("the full class list", big.getAllClasses(null, 0));
    }

    /**
     * The nested shapes, which are why the estimator had to be generalised: a
     * component carries its whole intent-filter set, so an entry is an object
     * with list-valued keys rather than three strings.
     */
    @Test
    void itPredictsTheNestedManifestShapes() throws Exception {
        assertPredicts("exported components", big.getExportedComponentsAsMap());
        assertPredicts("deeplink components", big.getDeeplinkComponents());
    }

    /** A map of lists, which is what find_sink_call_sites and the hierarchy are. */
    @Test
    void itPredictsAMapOfLists() throws Exception {
        assertPredicts("default sink sweep",
                big.findSinkCallSites(null, JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT));
        assertPredicts("hierarchy of Object", big.getTypeHierarchy("java.lang.Object"));
        assertPredicts("searchMethodByName(\"in\")", big.searchMethodByName("in", 1000));
    }

    /**
     * The property that let the two flat estimators be replaced by the general
     * one rather than joined by it: on the shapes they already handled, the
     * general form returns the identical number.
     *
     * A value is charged for the comma that follows it, which is what makes a
     * {@code "key":"value"} pair come to key + value + 6 and a bare name to
     * length + 3 -- the two arithmetics the guard has always used.
     */
    @Test
    void theGeneralFormAgreesWithTheFlatArithmeticItReplaced() {
        Map<String, String> entry = Map.of("className", "com.example.Foo",
                "methodName", "onCreate", "signature", "(Landroid/os/Bundle;)V");

        long byHand = 2;
        for (Map.Entry<String, String> e : entry.entrySet()) {
            byHand += e.getKey().length() + e.getValue().length() + 6;
        }
        assertThat(JadxAnalyzerCore.jsonCostOf(entry)).isEqualTo(byHand);

        assertThat(JadxAnalyzerCore.jsonCostOf("kotlin.jvm.internal.Intrinsics"))
                .isEqualTo("kotlin.jvm.internal.Intrinsics".length() + 3);
    }

    /**
     * The half of the estimate no APK here can exercise: text that is not ASCII.
     *
     * Every payload above is ASCII, on both reference APKs, in every list --
     * which is exactly why {@code length() + 3} survived. These are the shapes a
     * manifest of a Chinese or Russian app puts through the same guard, and they
     * are checked against Jackson like everything else rather than against the
     * arithmetic that produced them.
     */
    @Test
    void itPredictsTextThatIsNotAscii() throws Exception {
        assertPredicts("latin-1 supplement", List.of("café", "naïve", "Straße"));
        assertPredicts("CJK", List.of("示例一", "示例二名称", "com.example.测试.Activity"));
        assertPredicts("astral plane", List.of("😀", "a😀b", "𝄞𝄞"));
        assertPredicts("escapes", List.of("a\"b", "line\nbreak", "back\\slash", ""));
        assertPredicts("a component-shaped record", Map.of(
                "name", "com.example.分享.ShareActivity",
                "intent_filters", List.of(Map.of(
                        "actions", List.of("android.intent.action.VIEW"),
                        "categories", List.of(),
                        "data", List.of(Map.of("scheme", "示例", "host", "example.com",
                                "port", "", "autoVerify", false))))));
    }

    /**
     * The failure the exact arithmetic prevents, stated as a number.
     *
     * A guard that under-reports is silent, so the direction matters more than
     * the magnitude: the old estimate charged one byte for a character that
     * costs three, so a response of CJK text was weighed at 39% of its size and
     * a 1 MB cap let 2.7 MB through. This asserts against the real serialisation
     * that the shortfall is gone, on a payload big enough for it to have
     * mattered.
     */
    @Test
    void aWholeResponseOfCjkIsNotWeighedAtAThirdOfItsSize() throws Exception {
        List<String> cjk = new java.util.ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            cjk.add("com.example.测试类名称第" + i + "号包名.Activity的实现");
        }
        long estimate = JadxAnalyzerCore.jsonCostOf(cjk);
        long real = serialised(cjk);

        // What the old arithmetic would have said: UTF-16 units, + 3 a string.
        long byCharacters = 2;
        for (String name : cjk) {
            byCharacters += name.length() + 3;
        }

        assertThat(estimate).isEqualTo(real + 1);
        assertThat(real)
                .as("the payload has to be over the line for the guard to matter")
                .isGreaterThan(JadxAnalyzerCore.OVERSIZED_RESPONSE_BYTES);
        assertThat(byCharacters)
                .as("counting characters, this %,d-byte response weighed in under"
                        + " the 1 MB line and no notice was written", real)
                .isLessThan(JadxAnalyzerCore.OVERSIZED_RESPONSE_BYTES);
    }
}

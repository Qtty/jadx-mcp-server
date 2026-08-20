package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * searchMethodByName used to call JavaClass.getMethods(), which calls load() --
 * i.e. it decompiled every class in the APK to match a name. On a large production APK that
 * cost 64 seconds and dragged in bouncycastle, Rhino and OSGi. Worse, several
 * agents hitting that cliff at once is what produced the simultaneous responses
 * that killed the old MCP transport.
 *
 * Method names live in the dex metadata; reading them must not decompile anything.
 */
class SearchMethodByNameTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";
    static JadxAnalyzerCore small;

    @BeforeAll
    static void loadSmall() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        small = new JadxAnalyzerCore(SMALL_APK);
        small.loadApk();
    }

    @Test
    void findsMethodsAndEveryHitContainsTheQuery() {
        Map<String, List<String>> hits = small.searchMethodByName("onCreate");

        assertThat(hits).as("onCreate must exist in an Android app").isNotEmpty();
        assertThat(hits.values().stream().flatMap(List::stream))
                .allMatch(m -> m.contains("onCreate"));
    }

    @Test
    void returnsEmptyRatherThanFailingForAnUnknownName() {
        assertThat(small.searchMethodByName("nosuchmethodanywhere_zzz")).isEmpty();
    }

    @Test
    void keysAreClassNamesAndValuesAreQualifiedMethodNames() {
        Map<String, List<String>> hits = small.searchMethodByName("onCreate");
        String cls = hits.keySet().iterator().next();

        assertThat(cls).as("key is a class name").doesNotContain("(");
        assertThat(hits.get(cls)).isNotEmpty();
    }

    @Test
    void doesNotRepeatANameWithinAClass() {
        // The dex tree exposes synthetic bridge methods that share a name with the
        // method they forward to; they must not surface as duplicate entries.
        Map<String, List<String>> hits = small.searchMethodByName("onCreate");
        for (Map.Entry<String, List<String>> e : hits.entrySet()) {
            assertThat(e.getValue())
                    .as("duplicate method names for " + e.getKey())
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * The reported defect. name.contains("") is true for every method that
     * exists, and nothing checked, so a query that asked for nothing was
     * answered with the whole APK: every method in it, tens of MB on a large one --
     * eight times the 7.7 MB bare get_all_classes dump that the truncation
     * marker and the oversize warning exist to prevent.
     *
     * A refusal, not an empty map: an empty map reads as "this APK has no such
     * method", which is a different fact and the one a caller acts on.
     */
    @Test
    void anEmptyNameIsRefusedWithAStatedReasonNotAnsweredWithTheWholeApk() {
        Map<String, List<String>> refused = small.searchMethodByName("");

        assertThat(refused).containsOnlyKeys(JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
        assertThat(refused.get(JadxAnalyzerCore.REJECTED_MARKER_PREFIX)).hasSize(1);
        assertThat(refused.get(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).get(0))
                .as("the refusal has to say why, not just be empty")
                .startsWith(JadxAnalyzerCore.REJECTED_MARKER_PREFIX + ": ")
                .contains("methodName")
                .contains("substring");
    }

    /**
     * One character is refused for a reason that is not "short queries are
     * rude": loadApk sets DeobfuscationMinLength(2), so no alias in a loaded
     * tree is one character long. A one-character query can therefore never be
     * a name -- only a substring sweep, measured at 26.5 MB on a large production APK.
     */
    @Test
    void aOneCharacterNameIsRefusedBecauseNoAliasIsThatShort() {
        assertThat(small.searchMethodByName("a"))
                .containsOnlyKeys(JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
    }

    /**
     * The two short queries are refused for different reasons at different
     * prices, and the refusal has to give the caller the one that applies.
     *
     * One string served both, so "a" was told it asked for "every method in the
     * APK -- 790,530 of them and 58.9 MB ... because every name contains the
     * empty string". Every clause of that is about the EMPTY query: "a" does not
     * match every name, does not contain the empty-string argument, and cost
     * 26.5 MB rather than 58.9 -- overstated by better than 2x. The refusal
     * sentence is what a caller reads to decide what to type next, so a wrong
     * cause in it is a wrong instruction.
     */
    @Test
    void theRefusalGivesTheReasonThatAppliesToWhatWasTyped() {
        String empty = small.searchMethodByName("")
                .get(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).get(0);
        String oneChar = small.searchMethodByName("a")
                .get(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).get(0);

        assertThat(empty)
                .as("the empty query really does match every name")
                .contains("every method in the APK")
                .contains("58.9 MB")
                .contains("every name contains the empty string");
        assertThat(oneChar)
                .as("one character does not, and did not cost that")
                .contains("26.5 MB")
                .doesNotContain("every method in the APK")
                .doesNotContain("every name contains the empty string");
        assertThat(oneChar)
                .as("both still explain the floor itself")
                .contains("no method alias is 1 character");
    }

    /**
     * The floor is two and not three because a two-character method name is a
     * real thing in obfuscated code -- many of a large APK's methods
     * carry a two-character alias -- and refusing it would break a legitimate
     * search.
     */
    @Test
    void aTwoCharacterNameIsAnsweredRatherThanRefused() {
        Map<String, List<String>> hits = small.searchMethodByName("on");

        assertThat(hits).isNotEmpty()
                .doesNotContainKey(JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
    }

    @Test
    void anUnlimitedCallIsUnchangedAndLimitZeroMeansTheSameThing() {
        Map<String, List<String>> bare = small.searchMethodByName("onCreate");

        assertThat(small.searchMethodByName("onCreate", 0)).isEqualTo(bare);
        assertThat(bare).doesNotContainKey(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
    }

    /** The same bargain getAllClasses strikes: cut short, but never silently. */
    @Test
    void aLimitCapsTheClassesAndNamesTheRealTotal() {
        Map<String, List<String>> all = small.searchMethodByName("get");
        assumeTrue(all.size() > 3, "fixture needs >3 classes declaring a 'get' method");
        long methods = all.values().stream().mapToLong(List::size).sum();

        Map<String, List<String>> capped = small.searchMethodByName("get", 3);

        assertThat(capped).hasSize(4).containsKey(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
        assertThat(capped.get(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX).get(0))
                .startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX + ": ")
                .contains("3")
                .contains(String.valueOf(all.size()))
                .contains(String.valueOf(methods));
    }

    @Test
    void aLimitLargerThanTheResultTruncatesNothingAndSaysNothing() {
        Map<String, List<String>> hits = small.searchMethodByName("onCreate", 100_000);

        assertThat(hits).isEqualTo(small.searchMethodByName("onCreate"))
                .doesNotContainKey(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
    }

    /**
     * Truncation takes the first N of class-name order, which makes it
     * reproducible rather than a ranking -- the same rule findSinkCallSites
     * follows.
     */
    @Test
    void classNameOrderMakesTruncationDeterministic() {
        List<String> all = new ArrayList<>(small.searchMethodByName("get").keySet());
        assumeTrue(all.size() > 3, "fixture needs >3 classes declaring a 'get' method");
        assertThat(all).isSorted();

        assertThat(small.searchMethodByName("get", 3).keySet())
                .filteredOn(k -> !k.startsWith("@"))
                .containsExactlyElementsOf(all.subList(0, 3));
    }

    /**
     * Both markers share the map with class names and must be impossible to
     * mistake for one; '@' is not legal in a Java class name.
     */
    @Test
    void theMarkerKeysCannotBeConfusedWithAClassName() {
        assertThat(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).startsWith("@");
        assertThat(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX).startsWith("@");
    }

}

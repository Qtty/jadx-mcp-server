package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * search_classes_by_keyword closes a gap against jeb-plus: without it, a worker
 * that knows only part of a class name has no way to find the class.
 *
 * The constraint that matters is the same one that shaped searchMethodByName --
 * matching a name is metadata work and must not decompile anything. Class names
 * come off the dex tree, so this stays fast on a 141k-class APK.
 */
class SearchClassesByKeywordTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    @Test
    void findsAClassByAPartialName() {
        List<String> hits = core.searchClassesByKeyword("LogActivity");

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(n -> n.toLowerCase().contains("logactivity"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        List<String> lower = core.searchClassesByKeyword("logactivity");
        List<String> upper = core.searchClassesByKeyword("LOGACTIVITY");

        assertThat(lower).isNotEmpty();
        assertThat(upper).containsExactlyElementsOf(lower);
    }

    @Test
    void returnsEmptyRatherThanFailingForAnUnknownKeyword() {
        assertThat(core.searchClassesByKeyword("nosuchclassanywhere_zzz")).isEmpty();
    }

    /**
     * The load-bearing one. loadApk() enables deobfuscation, so a raw dex name
     * would be rejected by get_class_source with "Class not found". Feeding a
     * result straight back in is the only way to prove the names are usable.
     */
    @Test
    void everyResultIsANameThatGetClassSourceAccepts() {
        List<String> hits = core.searchClassesByKeyword("Activity");
        assertThat(hits).isNotEmpty();

        for (String name : hits.subList(0, Math.min(5, hits.size()))) {
            assertThat(core.getClassSource(name))
                    .as("get_class_source must resolve %s", name)
                    .isNotNull();
        }
    }

    /**
     * The empty keyword was already refused, but silently: an empty list reads
     * as "no such class", which is a different fact from "you asked for nothing"
     * and is the one a caller acts on.
     */
    @Test
    void anEmptyKeywordIsRefusedWithAStatedReasonNotASilentEmptyList() {
        List<String> refused = core.searchClassesByKeyword("");

        assertThat(refused).singleElement().asString()
                .startsWith(JadxAnalyzerCore.REJECTED_MARKER_PREFIX)
                .contains("keyword")
                .contains("substring");
    }

    /**
     * The one-character guard the empty-string check should always have been.
     * The match is a substring of the whole alias name, whose shortest instance
     * on a large production APK is five characters ("b3.a0"), so one character can never be a
     * name -- it matched 134,834 of 141,461 classes and 7.5 MB, as wide as the
     * bare get_all_classes dump it is documented as the narrow alternative to.
     */
    @Test
    void aOneCharacterKeywordIsRefusedBecauseItIsNotALookup() {
        assertThat(core.searchClassesByKeyword("a")).singleElement().asString()
                .startsWith(JadxAnalyzerCore.REJECTED_MARKER_PREFIX);
    }

    /** Two characters is a real query and stays one. */
    @Test
    void aTwoCharacterKeywordIsAnsweredRatherThanRefused() {
        List<String> hits = core.searchClassesByKeyword("ac");

        assertThat(hits).isNotEmpty()
                .noneMatch(n -> n.startsWith(JadxAnalyzerCore.REJECTED_MARKER_PREFIX));
    }

    @Test
    void resultsAreSortedAndFreeOfDuplicates() {
        List<String> hits = core.searchClassesByKeyword("ac");

        assertThat(hits).isSorted();
        assertThat(hits).doesNotHaveDuplicates();
    }

    @Test
    void anUnlimitedCallIsUnchangedAndLimitZeroMeansTheSameThing() {
        List<String> bare = core.searchClassesByKeyword("Activity");

        assertThat(core.searchClassesByKeyword("Activity", 0)).isEqualTo(bare);
        assertThat(bare).noneMatch(n -> n.startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX));
    }

    /** Same bargain as getAllClasses: cut short, but never silently. */
    @Test
    void aLimitCapsTheListAndNamesTheRealTotal() {
        List<String> all = core.searchClassesByKeyword("Activity");
        assumeTrue(all.size() > 3, "fixture needs >3 classes named *Activity*");

        List<String> capped = core.searchClassesByKeyword("Activity", 3);

        assertThat(capped).hasSize(4);
        assertThat(capped.get(3)).startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)
                .contains("3")
                .contains(String.valueOf(all.size()));
        assertThat(capped.subList(0, 3)).isEqualTo(all.subList(0, 3));
    }

    @Test
    void aLimitLargerThanTheResultTruncatesNothingAndSaysNothing() {
        List<String> hits = core.searchClassesByKeyword("Activity", 100_000);

        assertThat(hits).isEqualTo(core.searchClassesByKeyword("Activity"))
                .noneMatch(n -> n.startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX));
    }

    /**
     * Both markers share the list with class names and must be impossible to
     * mistake for one; '@' is not legal in a Java class name.
     */
    @Test
    void theMarkersCannotBeConfusedWithAClassName() {
        assertThat(JadxAnalyzerCore.REJECTED_MARKER_PREFIX).startsWith("@");
        assertThat(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX).startsWith("@");
    }
}

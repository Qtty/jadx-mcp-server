package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Enumerating every class is the widest answer this server can give, and after
 * the inner-class fix it is 141,461 names and 7.7 MB on the largest APK here --
 * a file dump in a pipeline whose stated principle is "narrow lookups, never
 * file dumps".
 *
 * The no-argument call keeps returning the complete list, because agents and the
 * CLI already depend on it. What is new is being able to ask for less, and never
 * being cut short without being told.
 */
class GetAllClassesTest {

    static final String APK = "misc/DivaApplication.apk";
    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(APK).exists(), "test fixture " + APK + " not present");
        core = new JadxAnalyzerCore(APK);
        core.loadApk();
    }

    @AfterAll
    static void close() {
        if (core != null) {
            core.close();
        }
    }

    /**
     * "Unmarked" means no bookkeeping of any kind, which is the one check this
     * server documents: a name starting with '@'. The previous body excluded
     * only {@value JadxAnalyzerCore#TRUNCATION_MARKER_PREFIX}, so an
     * {@code @oversized} notice on the no-argument call -- which is exactly the
     * call that can produce one, being the uncapped one -- would have passed a
     * test whose name says the answer is unmarked.
     *
     * 1791 is a property of the fixture file, not of this run; the invariant
     * that the count does not depend on the daemon's history is asserted
     * separately below rather than assumed here.
     */
    @Test
    void noArgumentsStillReturnsEveryClassSortedAndUnmarked() {
        List<String> all = core.getAllClasses();

        assertThat(all).hasSize(1791).isSorted();
        assertThat(all).as("the one check: bookkeeping starts with '@'")
                .noneMatch(name -> name.startsWith("@"));
        assertThat(all).isEqualTo(core.getAllClasses(null, 0));
    }

    /**
     * The listing is a dex fact, so decompiling the APK must not move it.
     *
     * Raised by an analysis as a claim that the constant above "is false against a
     * warm daemon". Measured, it is not: this fixture lists 1,791 classes, in
     * the same order, before and after every one of them is decompiled. That is
     * a good property and it was being taken on trust, so it is checked here
     * instead -- the number in the test above is now backed by an invariant
     * rather than by a previous run's output.
     */
    @Test
    void theListingDoesNotMoveWhenTheApkIsDecompiled() {
        JadxAnalyzerCore fresh = new JadxAnalyzerCore(APK);
        fresh.loadApk();
        try {
            List<String> cold = fresh.getAllClasses();

            int decompiled = 0;
            for (String name : cold) {
                fresh.getClassSource(name);
                decompiled++;
            }
            assertThat(decompiled).isGreaterThan(1_500);

            assertThat(fresh.getAllClasses())
                    .as("same names, same order, after the whole APK is decompiled")
                    .isEqualTo(cold);
        } finally {
            fresh.close();
        }
    }

    @Test
    void aPackagePrefixNarrowsToASubsetOfTheSameList() {
        List<String> diva = core.getAllClasses("jakhar.aseem.diva", 0);

        assertThat(diva).isNotEmpty().isSubsetOf(core.getAllClasses());
        assertThat(diva).allSatisfy(name ->
                assertThat(name).startsWith("jakhar.aseem.diva"));
        assertThat(diva.size()).isLessThan(core.getAllClasses().size());
    }

    @Test
    void thePrefixMatchesTheWholeNameNotJustThePackage() {
        // "the start of the fully-qualified name" is one rule, so it can name a
        // package, half a package, or the beginning of a class name.
        assertThat(core.getAllClasses("jakhar.aseem.diva.MainAct", 0))
                .containsExactly("jakhar.aseem.diva.MainActivity");
    }

    @Test
    void aPrefixThatMatchesNothingIsAnEmptyListNotAnError() {
        assertThat(core.getAllClasses("com.nonexistent.package", 0)).isEmpty();
    }

    /**
     * The whole point: a caller must be able to tell a complete list from a
     * truncated one. A silent cap would read as "this APK has 10 classes".
     */
    @Test
    void aTruncatedListSaysSoAndNamesTheRealTotal() {
        List<String> capped = core.getAllClasses(null, 10);

        assertThat(capped).hasSize(11);
        String marker = capped.get(capped.size() - 1);
        assertThat(marker).startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)
                .contains("10")
                .contains("1791")
                .contains("search_classes_by_keyword");
        assertThat(capped.subList(0, 10)).isEqualTo(core.getAllClasses().subList(0, 10));
    }

    @Test
    void aLimitLargerThanTheApkTruncatesNothingAndSaysNothing() {
        List<String> all = core.getAllClasses(null, 100_000);

        assertThat(all).isEqualTo(core.getAllClasses());
        assertThat(all.get(all.size() - 1))
                .doesNotStartWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
    }

    @Test
    void limitZeroMeansUncapped() {
        assertThat(core.getAllClasses(null, 0)).isEqualTo(core.getAllClasses());
    }

    /** The two narrow together, and the marker counts matches, not the APK. */
    @Test
    void prefixAndLimitCompose() {
        int matching = core.getAllClasses("jakhar.aseem.diva", 0).size();
        List<String> capped = core.getAllClasses("jakhar.aseem.diva", 5);

        assertThat(capped).hasSize(6);
        assertThat(capped.get(5)).startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX)
                .contains(String.valueOf(matching))
                .contains("jakhar.aseem.diva");
        assertThat(capped.subList(0, 5))
                .isEqualTo(core.getAllClasses("jakhar.aseem.diva", 0).subList(0, 5));
    }

    /**
     * The marker has to be impossible to mistake for a class name, because the
     * value is a list of class names and a caller may well iterate it.
     */
    @Test
    void theMarkerCannotBeConfusedWithAClassName() {
        assertThat(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX).startsWith("@");
        assertThat(core.getAllClasses(null, 10))
                .filteredOn(n -> n.startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX))
                .hasSize(1);
    }
}

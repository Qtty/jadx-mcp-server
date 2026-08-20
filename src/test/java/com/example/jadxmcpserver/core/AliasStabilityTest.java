package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deobfuscated alias names are a contract, not a convenience.
 *
 * the analysis client records findings against names jadx invented -- classes
 * like "com.usdk.android.C29382G" that have no such name in the dex -- and
 * getClassSource rejects any name it does not recognise. If a jadx upgrade, or
 * merely a second load of the same APK, numbered those classes differently,
 * every recorded finding would stop resolving and the whole H1 corpus
 * cross-reference would rot silently. Nothing else in the suite pins that down.
 *
 * This test pins the load-to-load half of it on the small fixture. The
 * cross-version half was measured against the 141,461-class a large production APK APK for
 * the 1.5.5 -> 1.5.6 upgrade: 141,461 class and 872,119 method aliases, zero
 * added, zero removed, zero changed, dumps byte-identical -- and both loads
 * ran with GeneratedRenamesMappingFileMode.READ and no mapping file on disk,
 * so each recomputed its names from scratch rather than replaying a cache.
 */
class AliasStabilityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    /**
     * A name jadx generated: the dex calls this class "android.support.design.R".
     * Asserting on it keeps the equality checks below from passing vacuously if
     * deobfuscation ever silently stops running -- raw names would still compare
     * equal to each other.
     */
    static final String GENERATED_ALIAS = "android.support.design.C0000R";

    static JadxAnalyzerCore first;
    static JadxAnalyzerCore second;

    @BeforeAll
    static void loadTwice() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        first = new JadxAnalyzerCore(SMALL_APK);
        first.loadApk();
        second = new JadxAnalyzerCore(SMALL_APK);
        second.loadApk();
    }

    // Every alias in the APK, which is what these comparisons mean. They used to
    // spell it searchClassesByKeyword("a") -- a one-character substring sweep
    // that happened to return most of the tree. That is now refused, because on
    // a real APK it is 134,834 names and 7.5 MB; getAllClasses is the call that
    // actually asks for everything.

    @Test
    void deobfuscationActuallyRanAndInventedNames() {
        assertThat(first.getAllClasses()).contains(GENERATED_ALIAS);
    }

    @Test
    void classAliasesAreIdenticalAcrossTwoLoads() {
        List<String> a = first.getAllClasses();
        List<String> b = second.getAllClasses();

        assertThat(a).hasSizeGreaterThan(500);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void methodAliasesAreIdenticalAcrossTwoLoads() {
        Map<String, List<String>> a = first.searchMethodByName("get");
        Map<String, List<String>> b = second.searchMethodByName("get");

        assertThat(a).hasSizeGreaterThan(100);
        assertThat(b).isEqualTo(a);
    }
}

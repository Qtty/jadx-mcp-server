package com.example.jadxmcpserver.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * get_class_source answers with an outline when the source does not fit.
 *
 * This is the one byte-capped tool that does not cut, and the reason is what
 * the cut would be worth. Every other one returns a stream whose first N bytes
 * are a usable prefix of the whole: resource bytes are still resource bytes,
 * smali is still smali. The first N bytes of a decompiled class are its imports
 * and two of forty methods, and nothing in the answer tells the caller whether
 * the method they came for was among them.
 *
 * What the cap is worth was measured rather than argued. On a large analysis
 * run get_class_source dominated the tool payload -- a majority of every byte
 * pulled -- and each byte was re-read on every subsequent turn of a
 * long-running caller. At the soft budget a large share of those calls degrade
 * to an outline and the tool's payload falls sharply. See
 * {@link JadxAnalyzerCore#OUTLINE_MARKER_PREFIX} and
 * {@link JadxAnalyzerCore#DEFAULT_SOFT_BYTES}.
 */
class ClassSourceOutlineTest {

    static final String APK = "misc/DivaApplication.apk";
    static final int SOFT = JadxAnalyzerCore.DEFAULT_SOFT_BYTES;
    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(APK).exists(), "test APK not present");
        core = new JadxAnalyzerCore(APK);
        core.loadApk();
    }

    @AfterAll
    static void close() {
        if (core != null) {
            core.close();
        }
    }

    /** A class comfortably over the soft budget whose member list still fits. */
    private static final String BIG = "jakhar.aseem.diva.C0303R.id";

    /**
     * The whole point: over budget, the caller gets what the class declares
     * rather than the top of its file.
     */
    @Test
    void oversizedClassReturnsAnOutlineNotACutBody() {
        String whole = core.getClassSource(BIG, 0);
        assumeTrue(whole != null && whole.length() > SOFT, "pick a bigger class");

        String answer = core.getClassSource(BIG, SOFT);

        assertThat(answer)
                .startsWith(JadxAnalyzerCore.OUTLINE_MARKER_PREFIX + ": ")
                .as("and it says how to get what it did not give")
                .contains("get_method_by_name")
                .contains("maxBytes=0")
                .as("an outline is not a cut body, and must not claim to be one")
                .doesNotContain(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
    }

    /**
     * An outline that dropped methods would be worse than the cut it replaced:
     * the caller would pick from a list that silently excluded their target.
     */
    @Test
    void theOutlineNamesEveryMemberTheClassDeclares() {
        assumeTrue(core.getClassSource(BIG, 0).length() > SOFT, "pick a bigger class");

        String answer = core.getClassSource(BIG, SOFT);
        List<String> methods = core.getMethodsOfClass(BIG);
        List<String> fields = core.getFieldsOfClass(BIG);
        assumeTrue(methods.size() < JadxAnalyzerCore.DEFAULT_RESULT_LIMIT,
                "this assertion is about a class whose members all fit");

        for (String method : methods) {
            assertThat(answer).contains(method);
        }
        for (String field : fields) {
            assertThat(answer).contains(field);
        }
        assertThat(answer).contains("methods (" + methods.size() + ")");
    }

    /** The saving is the point, so it is asserted rather than assumed. */
    @Test
    void theOutlineIsMuchSmallerThanTheSourceItReplaced() {
        String whole = core.getClassSource(BIG, 0);
        assumeTrue(whole.length() > SOFT, "pick a bigger class");

        assertThat(core.getClassSource(BIG, SOFT).length())
                .as("an outline that saved nothing would not be worth the round trip")
                .isLessThan(whole.length() / 2);
    }

    /** Under budget, nothing happens: no marker, byte-identical to uncapped. */
    @Test
    void aClassUnderTheBudgetIsReturnedWhole() {
        String small = smallestClassUnder(SOFT);
        assumeTrue(small != null, "no class under the soft budget on this APK");

        assertThat(core.getClassSource(small, SOFT))
                .doesNotStartWith("@")
                .isEqualTo(core.getClassSource(small, 0));
    }

    /**
     * maxBytes=0 is the escape hatch, and it has to keep working: reading how
     * several methods relate through fields and static state is the one thing
     * an outline cannot serve.
     */
    @Test
    void zeroMaxBytesStillReturnsTheWholeFile() {
        assertThat(core.getClassSource(BIG, 0))
                .doesNotContain(JadxAnalyzerCore.OUTLINE_MARKER_PREFIX);
    }

    /**
     * A generated resource table with hundreds of field declarations. Its
     * source is far over the budget, and because its member list does not fit
     * the budget either, the outline it degrades to is itself capped and says
     * so. Both facts are true at once and the answer states both.
     */
    @Test
    void theGeneratedResourceTableOutlinesAndTheOutlineIsItselfGuarded() {
        String answer = core.getClassSource("jakhar.aseem.diva.C0303R.styleable", SOFT);

        assertThat(answer)
                .startsWith(JadxAnalyzerCore.OUTLINE_MARKER_PREFIX + ": ")
                .as("a class with hundreds of members outlined at the budget cannot list them all whole")
                .contains(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX);
        assertThat(answer.length())
                .as("the guard on the outline is what stops it re-creating the dump")
                .isLessThan(10 * SOFT);
    }

    /** First class whose source fits the budget, so the test names no constant. */
    private static String smallestClassUnder(int budget) {
        for (String name : core.getAllClasses(null, 400)) {
            String source = core.getClassSource(name, 0);
            if (source != null && !source.startsWith("@")
                    && source.length() > 0 && source.length() < budget / 2) {
                return name;
            }
        }
        return null;
    }
}

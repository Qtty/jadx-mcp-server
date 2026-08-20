package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A method's name is a fact about the dex, and it has to survive the daemon.
 *
 * the analysis client records a method name in one phase and asks about it in a
 * later one, often against a daemon some other session has warmed in between.
 * Three separate things broke that, all measured before this file existed:
 *
 * <pre>
 *   get_methods_of_class read JavaClass.getMethods(), which filters
 *   AFlag.DONT_GENERATE -- a flag ClassModifier SETS while decompiling. It
 *   silently dropped constructors and static initialisers the dex declares:
 *   19 of 200 sampled classes on DivaApplication.apk, and 77 methods across
 *   300 classes on a large production APK (69 <init>, 4 <clinit>, 4 ordinary). It also
 *   RE-SORTED its answer once the class was decompiled, so the same call
 *   returned the same names in a different order.
 *
 *   Aliases DRIFT. ClassModifier removes a synthetic bridge and hands its name
 *   to the method behind it, so a name the server already handed out stops
 *   being the name it reports: 18 of 14,809 methods here, 230 of 872,119 on
 *   a large production APK after only 3,000 of 141,461 classes were decompiled.
 *
 *   That drift COLLIDES. Two dex methods end up sharing one alias -- 0 cold,
 *   76 names over 65 classes on a large production APK once warmed -- and the tools unioned
 *   them with no signal.
 * </pre>
 *
 * The fix is one idea applied three times: identity comes from the dex, pinned
 * while the tree is pristine. Class and field aliases are NOT pinned, because
 * they were measured and they do not drift -- 0 of 141,461 classes and 0 of
 * 861,374 fields on a large production APK across the same 3,000-class warm-up.
 *
 * CursorLoader is the fixture for most of this because one class exercises all
 * of it: two constructors the old tool dropped, an onCanceled/onCanceled2
 * bridge pair that drifts into a collision the moment the class decompiles,
 * and two genuine overload sets (deliverResult, loadInBackground).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MethodIdentityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    /**
     * Cold this class has onCanceled (the synthetic bridge, taking Object) and
     * onCanceled2 (the real one, taking Cursor). Decompiling it alone renames
     * onCanceled2 to onCanceled, so both then answer to one name.
     */
    static final String DRIFTER = "android.support.v4.content.CursorLoader";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    private static List<String> bare(List<String> fullNames) {
        List<String> out = new ArrayList<>();
        for (String n : fullNames) {
            int dot = n.lastIndexOf('.');
            out.add(dot < 0 ? n : n.substring(dot + 1));
        }
        return out;
    }

    // ------------------------------------------------------------------ 1

    /**
     * The dex declares two constructors on CursorLoader. JavaClass.getMethods()
     * hid both, so the tool an agent uses to find out what a class HAS was the
     * one tool that could not see a constructor -- while find_sink_call_sites
     * hands out 163 &lt;init&gt;/&lt;clinit&gt; call sites on a large production APK uncapped,
     * 90 at the default limit.
     */
    @Test
    @Order(1)
    void getMethodsOfClassListsTheConstructorsTheDexDeclares() {
        List<String> names = bare(core.getMethodsOfClass(DRIFTER));

        // Two of them, so each carries the signature that tells them apart --
        // a bare "<init>" listed twice would be the silent merge in a listing.
        assertThat(names).contains(
                "<init>(Landroid/content/Context;)V",
                "<init>(Landroid/content/Context;Landroid/net/Uri;[Ljava/lang/String;"
                        + "Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)V");
    }

    /**
     * A static initialiser is the other kind DONT_GENERATE hides. There is only
     * ever one, so it is listed under its plain name -- the signature is only
     * spent where it buys something.
     */
    @Test
    @Order(2)
    void getMethodsOfClassListsStaticInitialisers() {
        List<String> names = bare(core.getMethodsOfClass(
                "android.support.v7.widget.LinearLayoutManager.SavedState"));

        assertThat(names).contains("<clinit>");
        assertThat(names).contains("<init>()V");
    }

    /**
     * An unambiguous name is listed plainly. Spelling every method as
     * name+signature would be stable too, and it would break every worker
     * prompt and recorded finding that holds a bare name, to say something the
     * bare name already said unambiguously.
     */
    @Test
    @Order(3)
    void anUnambiguousNameIsListedWithoutASignature() {
        assertThat(bare(core.getMethodsOfClass(DRIFTER)))
                .contains("onStartLoading", "getUri")
                .doesNotContain("onStartLoading()V");
    }

    /**
     * Same question, same answer, whether or not the class has been decompiled
     * -- including the ORDER, which used to change on its own because
     * decompilation re-sorts ClassNode.getMethods().
     */
    @Test
    @Order(3)
    void getMethodsOfClassIsUnchangedByDecompilation() {
        List<String> cold = core.getMethodsOfClass(DRIFTER);
        assertThat(cold).isNotEmpty();

        core.getClassSource(DRIFTER);
        core.getClassSource("android.support.v4.content.AsyncTaskLoader");

        assertThat(core.getMethodsOfClass(DRIFTER)).isEqualTo(cold);
    }

    // ------------------------------------------------------------------ 2

    /**
     * The pin. Decompiling CursorLoader renames onCanceled2 to onCanceled;
     * the name the server reports must not move with it, or a finding recorded
     * against "onCanceled2" stops naming anything.
     */
    @Test
    @Order(4)
    void aReportedNameDoesNotMoveWhenTheClassIsDecompiled() {
        List<String> cold = bare(core.getMethodsOfClass(DRIFTER));
        assertThat(cold).contains("onCanceled2");

        core.getClassSource(DRIFTER);

        assertThat(bare(core.getMethodsOfClass(DRIFTER))).contains("onCanceled2");
    }

    /**
     * Leniency, in both directions: names already recorded in existing
     * workspaces are out there, and so is whatever a warm get_class_source
     * prints. Both have to resolve.
     */
    @Test
    @Order(5)
    void bothThePinnedAndTheDriftedNameResolve() {
        core.getClassSource(DRIFTER);

        assertThat(core.getXrefsToMethod(DRIFTER, "onCanceled2")).isNotNull();
        assertThat(core.getXrefsToMethod(DRIFTER, "onCanceled")).isNotNull();
        assertThat(core.getSmaliOfMethod(DRIFTER, "onCanceled2")).isNotNull();
    }

    /**
     * The round trip, which is what all of this is for: every name any tool
     * RETURNS is accepted by every tool that TAKES a methodName -- after the
     * class has been decompiled, which is when the names used to stop matching.
     */
    @Test
    @Order(6)
    void everyReturnedNameResolvesEverywhereEvenWarm() {
        core.getClassSource(DRIFTER);

        List<String> failures = new ArrayList<>();
        List<String> full = core.getMethodsOfClass(DRIFTER);
        assertThat(full).isNotEmpty();

        for (String fullName : full) {
            int dot = fullName.lastIndexOf('.');
            String bare = dot < 0 ? fullName : fullName.substring(dot + 1);
            for (String name : List.of(fullName, bare)) {
                try {
                    core.getXrefsToMethod(DRIFTER, name);
                    core.getCallees(DRIFTER, name);
                    core.getOverrides(DRIFTER, name);
                    core.getSmaliOfMethod(DRIFTER, name);
                    core.getMethodByName(DRIFTER, name);
                } catch (RuntimeException e) {
                    failures.add(name + " -> " + e.getMessage());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    // ------------------------------------------------------------------ 3

    /**
     * deliverResult is two distinct dex methods. Answering as though it were
     * one is the silent merge; saying so is the fix.
     */
    @Test
    @Order(7)
    void anAmbiguousNameSaysHowManyMethodsItCovers() {
        Map<String, Object> callees = core.getCallees(DRIFTER, "deliverResult");

        assertThat(callees).containsKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
        assertThat(callees.get(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX).toString())
                .contains("2");
    }

    /**
     * An unambiguous name must NOT carry the notice, or it becomes noise that
     * gets ignored on the one call where it mattered.
     */
    @Test
    @Order(8)
    void anUnambiguousNameCarriesNoSuchNotice() {
        assertThat(core.getCallees(DRIFTER, "onStartLoading"))
                .doesNotContainKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
    }

    /**
     * The signature every xref and callee entry already carries is the thing
     * that tells two overloads apart, so it is what disambiguates them.
     */
    @Test
    @Order(9)
    void aSignatureNarrowsAnAmbiguousNameToOneMethod() {
        Map<String, Object> both = core.getCallees(DRIFTER, "deliverResult", null);
        assertThat(both).containsKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);

        Map<String, Object> one =
                core.getCallees(DRIFTER, "deliverResult", "(Landroid/database/Cursor;)V");
        assertThat(one).doesNotContainKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
    }

    /**
     * A signature that names no method is an error, not an empty answer: an
     * empty caller list already means "nothing calls this", and the two must
     * not be spelled the same way.
     */
    @Test
    @Order(10)
    void aSignatureThatMatchesNothingIsRejected() {
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> core.getCallees(DRIFTER, "deliverResult", "(Lno/such/Type;)V"))
                        .getMessage())
                .contains("deliverResult");
    }

    /**
     * The acceptance pattern this whole line of work is checked against: a cold
     * answer, the whole APK decompiled, then the identical answer.
     *
     * A handful of classes is not enough to catch this -- a 341-class sample
     * once cleared a tool that was in fact losing 3,593 edges -- so this sweeps
     * every class the fixture has.
     *
     * EVERY DEX-DERIVED TOOL, which is the name's claim and now the body's.
     * This test was called everyToolGivesTheSameAnswer... and compared exactly
     * two, getMethodsOfClass and getXrefsToMethod, while its own javadoc said
     * three. The remaining tools that take a class or a method name were
     * unchecked, and they are not free riders on the two that were: getCallees
     * reads a different index, getOverrides walks the class hierarchy, and
     * getSmaliOfMethod and getFieldsOfClass resolve names through paths of
     * their own.
     *
     * The exclusion is deliberate and it is exactly one tool. getClassSource is
     * the WARM-UP here, and it is the one tool this server has measured as
     * warmth-dependent: 4 of 2,810 probed classes on a large production APK differ after a
     * 3,000-class warm-up and 41 differ under a reverse-order read. It is a
     * decompiler tool, not a dex tool, and its stability is a separate claim
     * owned by {@link ClassSourceStabilityTest}. Naming it here rather than
     * quietly leaving it out, because "every tool" with a silent exception is
     * the shape of claim this analysis exists to remove.
     *
     * getMethodByName IS included even though it decompiles, because the claim
     * it makes is narrower than getClassSource's and the caller depends on it: a
     * method name recorded in one phase and re-read in a later one must come
     * back as the same text. On this fixture it does, over every method of every
     * class, under a whole-APK warm-up. If that stops being true this test fails
     * naming the tool, which is the wanted outcome -- a measured retraction
     * rather than a surprise in an analysis.
     *
     * The warm-up is the WHOLE fixture rather than the classes being asked
     * about: drift is caused by decompiling a class, and the interesting failure
     * is the one where some unrelated request warmed the daemon between two
     * phases of an analysis.
     */
    @Test
    @Order(20)
    void everyDexDerivedToolGivesTheSameAnswerAfterTheApkIsDecompiled() {
        List<String> classes = new ArrayList<>();
        for (String name : core.getAllClasses()) {
            if (!name.startsWith("@")) {
                classes.add(name);
            }
        }
        assertThat(classes).hasSizeGreaterThan(500);

        Map<String, Object> cold = new java.util.LinkedHashMap<>();
        int methodCount = 0;
        for (String cn : classes) {
            List<String> methods = core.getMethodsOfClass(cn);
            methodCount += methods.size();
            record(cold, "getMethodsOfClass", cn, methods);
            record(cold, "getFieldsOfClass", cn, core.getFieldsOfClass(cn));
            record(cold, "getTypeHierarchy", cn, core.getTypeHierarchy(cn));
            record(cold, "getXrefsToClass", cn, core.getXrefsToClass(cn));
            for (String full : methods) {
                String bare = bareName(full);
                String at = cn + "#" + bare;
                record(cold, "getXrefsToMethod", at, core.getXrefsToMethod(cn, bare));
                record(cold, "getCallees", at, core.getCallees(cn, bare));
                record(cold, "getOverrides", at, core.getOverrides(cn, bare));
                record(cold, "getSmaliOfMethod", at, core.getSmaliOfMethod(cn, bare));
                record(cold, "getMethodByName", at, core.getMethodByName(cn, bare));
            }
        }
        assertThat(methodCount).isGreaterThan(5000);
        assertThat(cold).hasSizeGreaterThan(20_000);

        int decompiled = 0;
        for (String cn : classes) {
            core.getClassSource(cn);
            decompiled++;
        }
        assertThat(decompiled).isGreaterThan(500);

        List<String> drifted = new ArrayList<>();
        for (String cn : classes) {
            check(drifted, cold, "getMethodsOfClass", cn, core.getMethodsOfClass(cn));
            check(drifted, cold, "getFieldsOfClass", cn, core.getFieldsOfClass(cn));
            check(drifted, cold, "getTypeHierarchy", cn, core.getTypeHierarchy(cn));
            check(drifted, cold, "getXrefsToClass", cn, core.getXrefsToClass(cn));
        }
        for (String key : new ArrayList<>(cold.keySet())) {
            int at = key.indexOf('|');
            String tool = key.substring(0, at);
            String target = key.substring(at + 1);
            if (!target.contains("#")) {
                continue;  // class-scoped, already re-read above
            }
            String cn = target.substring(0, target.indexOf('#'));
            String bare = target.substring(target.indexOf('#') + 1);
            Object now;
            switch (tool) {
                case "getXrefsToMethod": now = core.getXrefsToMethod(cn, bare); break;
                case "getCallees": now = core.getCallees(cn, bare); break;
                case "getOverrides": now = core.getOverrides(cn, bare); break;
                case "getSmaliOfMethod": now = core.getSmaliOfMethod(cn, bare); break;
                case "getMethodByName": now = core.getMethodByName(cn, bare); break;
                default: continue;
            }
            check(drifted, cold, tool, target, now);
        }
        assertThat(drifted).isEmpty();
    }

    private static String bareName(String fullName) {
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }

    private static void record(Map<String, Object> into, String tool, String target,
                               Object answer) {
        into.put(tool + "|" + target, answer);
    }

    private static void check(List<String> drifted, Map<String, Object> cold,
                              String tool, String target, Object now) {
        Object before = cold.get(tool + "|" + target);
        if (!java.util.Objects.equals(before, now)) {
            drifted.add(tool + "(" + target + ")");
        }
    }

    /**
     * "Across a daemon restart", which is the case the caller actually lives in:
     * phase 2 records a name against one daemon, phase 5 asks a different one.
     *
     * A second core is a second load of the same APK, which is what a restart
     * is. The names the WARMED core reports must be exactly what a fresh core
     * reports, and must resolve there -- if pinning only held within a process,
     * a recorded finding would still rot the moment the daemon was recycled.
     * This works because jadx's assignment is deterministic at load; see
     * AliasStabilityTest, which pins that separately.
     */
    @Test
    @Order(21)
    void namesRecordedAgainstAWarmedDaemonResolveInAFreshOne() {
        // `core` is thoroughly warmed by the tests above, including the
        // whole-APK sweep, so these are names taken from a drifted tree.
        List<String> recorded = core.getMethodsOfClass(DRIFTER);
        assertThat(recorded).isNotEmpty();

        JadxAnalyzerCore restarted = new JadxAnalyzerCore(SMALL_APK);
        restarted.loadApk();
        try {
            assertThat(restarted.getMethodsOfClass(DRIFTER)).isEqualTo(recorded);

            List<String> failures = new ArrayList<>();
            for (String fullName : recorded) {
                int dot = fullName.lastIndexOf('.');
                String bare = dot < 0 ? fullName : fullName.substring(dot + 1);
                try {
                    restarted.getXrefsToMethod(DRIFTER, bare);
                    restarted.getCallees(DRIFTER, bare);
                    restarted.getSmaliOfMethod(DRIFTER, bare);
                } catch (RuntimeException e) {
                    failures.add(bare + " -> " + e.getMessage());
                }
            }
            assertThat(failures).isEmpty();
        } finally {
            restarted.close();
        }
    }

    /**
     * The xref list is a list, so its notice is a trailing entry -- the shape
     * the marker convention already uses everywhere else.
     */
    @Test
    @Order(11)
    void anAmbiguousXrefListSaysSoAsATrailingEntry() {
        List<Map<String, String>> xrefs = core.getXrefsToMethod(DRIFTER, "deliverResult");

        assertThat(xrefs).isNotEmpty();
        assertThat(xrefs.get(xrefs.size() - 1))
                .containsKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
    }

    /**
     * The notice must never be mistaken for data: an entry naming a caller has
     * a className, the notice does not, and every marker in this server starts
     * with '@' so one check finds all of them.
     */
    @Test
    @Order(12)
    void theXrefNoticeIsNotMistakableForACaller() {
        for (Map<String, String> entry : core.getXrefsToMethod(DRIFTER, "deliverResult")) {
            if (entry.containsKey(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX)) {
                assertThat(entry).doesNotContainKey("className");
            } else {
                assertThat(entry).containsKey("className");
            }
        }
    }
}

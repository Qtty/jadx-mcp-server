package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A field's name is a fact about the dex, and it has to survive the daemon --
 * the guarantee {@link MethodIdentityTest} already holds methods to.
 *
 * get_fields_of_class read {@code JavaClass.getFields()}, the decompiled view,
 * which is the same mistake get_methods_of_class made before commit 3ad692f and
 * it fails in a nastier way. {@code JavaClass.load()} is LATCHED --
 * {@code listsLoaded} is set once and the list is never rebuilt -- and
 * {@code ClassNode.decompile()} returns {@code ICodeInfo.EMPTY} without doing
 * any work for an inner class. So which fields an inner class appears to have
 * depends on whether the first ask beat the decompiler to it:
 *
 * <pre>
 *   ask first, then decompile   -> the pristine list, synthetic fields included
 *   decompile first, then ask   -> filtered by AFlag.DONT_GENERATE
 * </pre>
 *
 * Measured by running the identical probe twice in two JVMs, once asking before
 * the warm-up and once after:
 *
 * <pre>
 *   DivaApplication.apk    276 of   890 probed classes answered differently
 *   a large production APK            1,016 of 2,954, after only 3,000 of 89,861 top-level
 *                        classes were decompiled
 * </pre>
 *
 * It moves in BOTH directions, which is why "just decompile everything first"
 * is not a fix: {@code NavigationMenuPresenter$1} LOSES its {@code this$0} when
 * warm, while {@code FloatingActionButtonEclairMr1$1} GAINS a
 * {@code val$listener}. Both are stable across a restart, so both are a
 * confident wrong answer rather than a flaky one.
 *
 * Two smaller things came with it. The list was never sorted -- 1,160 of 2,954
 * a large production APK classes came back in an order the dex happened to leave -- and
 * getClassDetails counted {@code fieldCount} off the view while shipping the
 * list from the same place, so the count could contradict its own list.
 *
 * The NAMES in the list move too, which the round that pinned methods concluded
 * they did not. That measurement warmed a 3,000-class prefix of the tree and saw
 * 0 of 861,374 field aliases drift. EnumVisitor is the only pass in jadx that
 * renames a field, and a prefix of {@code getClasses()} on that APK holds almost
 * no enums -- warm every enum first instead and 1,958 of 861,374 move. So fields
 * are pinned now, exactly as methods are; see {@link PinnedFieldNames}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FieldIdentityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    /**
     * Inner class holding a synthetic outer reference. Cold this reports
     * {@code this$0}; warm, ClassModifier has flagged it DONT_GENERATE and the
     * view drops it.
     */
    static final String LOSES_WHEN_WARM =
            "android.support.design.internal.NavigationMenuPresenter.ViewOnClickListenerC00011";

    /**
     * The other direction, which is the one that makes "decompile first" no
     * fix at all: cold the view listed NO fields for this class, warm it listed
     * {@code val$listener}. The dex declares both that and a {@code this$0},
     * so neither the cold nor the warm view was ever right.
     */
    static final String GAINS_WHEN_WARM =
            "android.support.design.widget.FloatingActionButtonEclairMr1.C00131";

    /** Three fields in the dex; the view shows two cold and three warm. */
    static final String FLING_RUNNABLE =
            "android.support.design.widget.HeaderBehavior.FlingRunnable";

    /** What the dex declares, read out of ClassNode.getFields() and sorted. */
    static final Map<String, List<String>> DEX_TRUTH = Map.of(
            LOSES_WHEN_WARM,
            List.of("android.support.design.internal.NavigationMenuPresenter this$0"),
            GAINS_WHEN_WARM,
            List.of("android.support.design.widget.FloatingActionButtonEclairMr1 this$0",
                    "android.support.design.widget.FloatingActionButtonImpl$"
                            + "InternalVisibilityChangedListener val$listener"),
            FLING_RUNNABLE,
            List.of("V extends android.view.View mLayout",
                    "android.support.design.widget.CoordinatorLayout mParent",
                    "android.support.design.widget.HeaderBehavior this$0"));

    private static JadxAnalyzerCore freshCore() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        JadxAnalyzerCore core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
        return core;
    }

    /** Decompile the whole fixture, the way an unrelated request would. */
    private static int warmUp(JadxAnalyzerCore core) {
        int decompiled = 0;
        for (String name : core.getAllClasses()) {
            if (!name.startsWith("@")) {
                core.getClassSource(name);
                decompiled++;
            }
        }
        assertThat(decompiled).isGreaterThan(500);
        return decompiled;
    }

    // ------------------------------------------------------------------ 1

    /**
     * The dex is the answer, whether or not anything has been decompiled. A
     * fresh core asked cold has to say what a fresh core asked warm says.
     */
    @Test
    @Order(1)
    void aColdCoreAndAWarmCoreAgreeOnWhatAClassDeclares() {
        JadxAnalyzerCore cold = freshCore();
        Map<String, List<String>> coldAnswers = new LinkedHashMap<>();
        for (String cls : DEX_TRUTH.keySet()) {
            coldAnswers.put(cls, cold.getFieldsOfClass(cls));
        }

        JadxAnalyzerCore warm = freshCore();
        warmUp(warm);

        for (String cls : DEX_TRUTH.keySet()) {
            assertThat(warm.getFieldsOfClass(cls))
                    .as("warm answer for " + cls)
                    .isEqualTo(coldAnswers.get(cls))
                    .isEqualTo(DEX_TRUTH.get(cls));
        }
    }

    // ------------------------------------------------------------------ 2

    /**
     * The same core, asked twice with the fixture decompiled in between. This is
     * the shape a long-lived daemon actually sees.
     */
    @Test
    @Order(2)
    void oneCoreGivesTheSameFieldsBeforeAndAfterTheApkIsDecompiled() {
        JadxAnalyzerCore core = freshCore();
        List<String> classes = new ArrayList<>();
        for (String name : core.getAllClasses()) {
            if (!name.startsWith("@")) {
                classes.add(name);
            }
        }
        assertThat(classes).hasSizeGreaterThan(500);

        Map<String, List<String>> before = new LinkedHashMap<>();
        for (String cn : classes) {
            before.put(cn, core.getFieldsOfClass(cn));
        }
        assertThat(before.values().stream().mapToInt(List::size).sum())
                .isGreaterThan(5000);

        warmUp(core);

        List<String> drifted = new ArrayList<>();
        for (String cn : classes) {
            if (!before.get(cn).equals(core.getFieldsOfClass(cn))) {
                drifted.add(cn);
            }
        }
        assertThat(drifted).isEmpty();
    }

    // ------------------------------------------------------------------ 3

    /**
     * Sorted, for the reason every other list here is sorted: the same question
     * has to come back in the same order, and the order the dex or a
     * decompilation pass happens to leave is not one.
     *
     * By NAME, not by the printed "type name" string -- the name is the part a
     * caller feeds back, and it is what get_methods_of_class sorts on too.
     * Sorting the printed string would group by type and scatter the names.
     */
    @Test
    @Order(3)
    void theFieldListIsSortedByName() {
        JadxAnalyzerCore core = freshCore();
        int checked = 0;
        for (String cn : core.getAllClasses()) {
            if (cn.startsWith("@")) {
                continue;
            }
            List<String> fields = core.getFieldsOfClass(cn);
            if (fields.size() < 2) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String entry : fields) {
                int sp = entry.lastIndexOf(' ');
                names.add(sp < 0 ? entry : entry.substring(sp + 1));
            }
            assertThat(names).as("field names of " + cn).isSorted();
            checked++;
        }
        assertThat(checked).isGreaterThan(100);
    }

    // ------------------------------------------------------------------ 4

    /** A record whose count contradicts its own list is worse than either. */
    @Test
    @Order(4)
    void fieldCountMatchesTheListItShips() {
        JadxAnalyzerCore core = freshCore();
        for (String cls : DEX_TRUTH.keySet()) {
            Map<String, Object> details = core.getClassDetails(cls);
            assertThat(details).as("details for " + cls).isNotNull();
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) details.get("fields");
            assertThat(details.get("fieldCount"))
                    .as("fieldCount for " + cls)
                    .isEqualTo(fields.size());
        }
    }

    // ------------------------------------------------------------------ 5

    /**
     * The round-trip invariant, on fields: a name this server hands out has to
     * be a name this server accepts.
     *
     * get_fields_of_class prints "type name" and get_xrefs_to_field matched the
     * bare alias, so every one of 504 sampled entries was rejected when pasted
     * back verbatim -- with "Field not found", which reads as "no such field"
     * rather than "wrong spelling".
     */
    @Test
    @Order(5)
    void everyFieldNameThisServerHandsOutIsAcceptedBack() {
        JadxAnalyzerCore core = freshCore();
        List<String> rejected = new ArrayList<>();
        int checked = 0;
        for (String cn : core.getAllClasses()) {
            if (cn.startsWith("@")) {
                continue;
            }
            for (String entry : core.getFieldsOfClass(cn, 5)) {
                if (entry.startsWith("@")) {
                    continue;
                }
                checked++;
                try {
                    core.getXrefsToField(cn, entry, 1);
                } catch (RuntimeException e) {
                    if (rejected.size() < 10) {
                        rejected.add(cn + " | \"" + entry + "\" -> " + e.getMessage());
                    }
                }
            }
            if (checked >= 500) {
                break;
            }
        }
        assertThat(checked).isGreaterThan(400);
        assertThat(rejected).isEmpty();
    }

    /**
     * Field aliases DRIFT, and the round that pinned methods concluded they did
     * not. The measurement then was a 3,000-class prefix of the tree, which on
     * a large production APK contains almost no enums -- and EnumVisitor, which recovers a
     * constant's real name out of the {@code <clinit>} and renames the field to
     * it, is the only pass in jadx that renames a field at all.
     *
     * Re-measured with every enum decompiled first: 1,958 of 861,374 moved,
     * e.g. {@code C2833e$b.f2385a -> NONE}. Eight times the method rate that
     * justified pinning methods in the first place.
     *
     * DivaApplication.apk declares exactly one enum, so this asserts the
     * invariant rather than a fixture: whatever a class's fields are called
     * cold, they are still called that once the APK is decompiled.
     */
    @Test
    @Order(7)
    void fieldNamesSurviveDecompilationOfTheWholeApk() {
        JadxAnalyzerCore core = freshCore();
        List<String> classes = new ArrayList<>();
        for (String name : core.getAllClasses()) {
            if (!name.startsWith("@")) {
                classes.add(name);
            }
        }

        Map<String, List<String>> before = new LinkedHashMap<>();
        for (String cn : classes) {
            before.put(cn, core.getFieldsOfClass(cn));
        }
        warmUp(core);

        List<String> moved = new ArrayList<>();
        for (String cn : classes) {
            List<String> now = core.getFieldsOfClass(cn);
            if (!before.get(cn).equals(now)) {
                moved.add(cn + ": " + before.get(cn) + " -> " + now);
            }
            // and every cold-issued name still resolves
            for (String entry : before.get(cn)) {
                if (entry.startsWith("@")) {
                    continue;
                }
                try {
                    core.getXrefsToField(cn, entry, 1);
                } catch (RuntimeException e) {
                    moved.add(cn + " | " + entry + " -> " + e.getMessage());
                }
            }
        }
        assertThat(moved).isEmpty();
    }

    /**
     * The resolver has to see the same field population the listing does.
     *
     * Decompiling an enum makes EnumVisitor synthesise FieldNodes for the
     * constants it recovers, aliased to names existing constants already carry.
     * The listing has filtered those out since commit 339b54d; the resolver did
     * not, so a name the server itself emitted resolved to one field cold and to
     * TWO once the class decompiled -- and the {@code @ambiguous} notice that
     * came back could not be acted on, because its advice is "pass the whole
     * type name entry" and both candidates print the identical type and name:
     *
     * <pre>
     *   "...NetworkConnectionInfo$MobileSubtype EVDO_0" names 2 fields in this
     *   class and this answer covers all of them:
     *   ...NetworkConnectionInfo$MobileSubtype EVDO_0,
     *   ...NetworkConnectionInfo$MobileSubtype EVDO_0.
     * </pre>
     *
     * Measured on a large production APK with every one of its 2,517 enums decompiled: two
     * classes grow (NetworkConnectionInfo$MobileSubtype 24 -&gt; 26 fields,
     * $NetworkType 22 -&gt; 24), 19,750 emitted enum-field entries fed back, 4
     * unactionable notices before the fix and 0 after.
     *
     * DivaApplication.apk declares one enum and NO class's field list grows on
     * it, so on this fixture the assertion is the invariant rather than the
     * repro -- the repro needs the large APK and lives in the numbers above.
     */
    @Test
    @Order(8)
    void aNameTheListingEmitsNeverResolvesToTwoFields() {
        JadxAnalyzerCore core = freshCore();
        warmUp(core);

        List<String> unactionable = new ArrayList<>();
        int checked = 0;
        for (String cn : core.getAllClasses()) {
            if (cn.startsWith("@")) {
                continue;
            }
            for (String entry : core.getFieldsOfClass(cn, 5)) {
                if (entry.startsWith("@")) {
                    continue;
                }
                checked++;
                for (Map<String, String> ref : core.getXrefsToField(cn, entry, 5)) {
                    String notice = ref.get(JadxAnalyzerCore.AMBIGUOUS_MARKER_PREFIX);
                    if (notice != null && unactionable.size() < 10) {
                        unactionable.add(cn + " | " + entry + " -> " + notice);
                    }
                }
            }
            if (checked >= 500) {
                break;
            }
        }
        assertThat(checked).isGreaterThan(400);
        assertThat(unactionable).isEmpty();
    }

    /**
     * "Field not found" must offer names the listing emits and the resolver
     * accepts -- otherwise the recovery path it exists to provide leads to a
     * second rejection.
     */
    @Test
    @Order(9)
    void theNotFoundMessageOffersOnlyNamesTheListingEmits() {
        JadxAnalyzerCore core = freshCore();
        warmUp(core);

        List<String> listed = core.getFieldsOfClass(FLING_RUNNABLE);
        assertThat(listed).isNotEmpty();
        try {
            core.getXrefsToField(FLING_RUNNABLE, "noSuchFieldHere");
            throw new AssertionError("expected a rejection");
        } catch (RuntimeException e) {
            for (String entry : listed) {
                assertThat(e.getMessage()).contains(entry);
            }
        }
    }

    /** The bare name has to keep working -- older workspaces recorded it. */
    @Test
    @Order(6)
    void theBareFieldNameStillResolves() {
        JadxAnalyzerCore core = freshCore();
        assertThat(core.getXrefsToField(FLING_RUNNABLE, "mParent", 5)).isNotNull();
        assertThat(core.getXrefsToField(FLING_RUNNABLE,
                "android.support.design.widget.CoordinatorLayout mParent", 5)).isNotNull();
    }
}

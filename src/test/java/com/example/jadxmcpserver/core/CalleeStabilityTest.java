package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * get_callees must answer the same thing whether or not the class has been
 * decompiled. It did not, and that is why this file exists.
 *
 * jadx's usage graph is not a record of the bytecode. It is a record of the Java
 * jadx intends to PRINT, and decompilation rewrites it in place:
 *
 *   InlineMethods.updateUsageInfo   folds a synthetic accessor into its callers
 *                                   and re-points the accessor's own edges
 *   ClassModifier.modifySyntheticMethod -> UsageInfoVisitor.replaceMethodUsage
 *                                   does the same for synthetic constructors
 *   MethodNode.getUsed()            then drops, ON READ, every callee whose
 *                                   getUseIn() no longer names this method
 *                                   (removeInvalidMethodsUsed)
 *
 * Measured on this APK before the fix: decompiling all 651 top-level classes
 * made 699 methods report fewer callees -- 963 edges gone, none gained, 374 of
 * those methods left reporting no in-APK callees at all.
 *
 * Why that is expensive here and not merely untidy: the daemon is long-lived and
 * shared between agents, so "has this class been decompiled yet" depends on some
 * earlier, unrelated request. A sink tracer walking forward would lose a hop and
 * conclude the path does not exist -- a false negative, the direction a bug
 * caller cannot afford.
 *
 * The dex is the truthful graph. getTargetShadowSize below really does contain
 * two invoke-static instructions; the accessors are a hop an agent would
 * otherwise have to follow by hand, not an artefact. So getCallees reads the
 * method's own dex instructions (JadxAnalyzerCore.scanCallees) rather than the
 * usage graph, which no decompiler pass can rewrite.
 *
 * Method order is pinned: the "cold" reads have to happen before anything in the
 * fixture is decompiled, or the test stops reproducing the bug it guards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CalleeStabilityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * The verifier's own scenario, narrowed to one method.
     *
     * getTargetShadowSize in the smali:
     *   invoke-static {v0}, ...FloatingActionButtonEclairMr1;->access$400(...)F
     *   invoke-static {v1}, ...FloatingActionButtonEclairMr1;->access$500(...)F
     * Two calls in the bytecode, so two callees, before and after.
     */
    @Test
    @Order(1)
    void oneMethodAnswersTheSameColdAndWarm() {
        String cls = "android.support.design.widget.FloatingActionButtonEclairMr1"
                + ".ElevateToTranslationZAnimation";

        Map<String, Object> cold = core.getCallees(cls, "getTargetShadowSize");
        assertThat(names(internal(cold))).containsExactly(
                "android.support.design.widget.FloatingActionButtonEclairMr1.access$400",
                "android.support.design.widget.FloatingActionButtonEclairMr1.access$500");

        core.getClassSource(cls);

        assertThat(core.getCallees(cls, "getTargetShadowSize")).isEqualTo(cold);
    }

    /**
     * One hop down: the accessor itself. Decompiling the enclosing class used to
     * empty its callee list, so a tracer arriving here from a caller in some
     * other, still-pristine class hit a dead end -- while the bytecode plainly
     * still holds the invoke.
     *
     * smali: access$400 is a single invoke-static to getViewOffsetHelper.
     */
    @Test
    @Order(2)
    void anInlinedAccessorStillReportsWhatItCalls() {
        String cls = "android.support.design.widget.CollapsingToolbarLayout";

        assertThat(names(internal(core.getCallees(cls, "access$400"))))
                .containsExactly(cls + ".getViewOffsetHelper");

        core.getClassSource(cls);

        assertThat(names(internal(core.getCallees(cls, "access$400"))))
                .containsExactly(cls + ".getViewOffsetHelper");
    }

    /**
     * The widest form: decompile the whole APK, then re-ask. These five were the
     * worst offenders in the sweep -- dispatchLayout lost 13 edges, the
     * PlaybackStateCompat constructor lost all 10, AppBarLayout.access$000 lost
     * its only one. LogActivity.checkout is the control: it never moved, and
     * must still not move.
     */
    @Test
    @Order(3)
    void nothingAnswersDifferentlyAfterTheWholeApkIsDecompiled() {
        List<String> probes = List.of(
                "android.support.v7.widget.RecyclerView#dispatchLayout",
                "android.support.design.widget.AppBarLayout#access$000",
                "android.support.v4.media.session.PlaybackStateCompat.Builder#<init>",
                "android.support.design.widget.CollapsingToolbarLayout.OffsetUpdateListener"
                        + "#onOffsetChanged",
                "jakhar.aseem.diva.LogActivity#checkout");

        Map<String, Map<String, Object>> cold = new LinkedHashMap<>();
        for (String probe : probes) {
            cold.put(probe, calleesOf(probe));
        }

        for (String className : core.getAllClasses()) {
            core.getClassSource(className);
        }

        for (String probe : probes) {
            assertThat(calleesOf(probe)).as("%s", probe).isEqualTo(cold.get(probe));
        }
    }

    private static Map<String, Object> calleesOf(String probe) {
        String[] parts = probe.split("#", 2);
        return core.getCallees(parts[0], parts[1]);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> internal(Map<String, Object> callees) {
        return (List<Map<String, String>>) callees.get("callees");
    }

    private static List<String> names(List<Map<String, String>> entries) {
        return entries.stream()
                .map(e -> e.get("className") + "." + e.get("methodName"))
                .collect(Collectors.toList());
    }
}

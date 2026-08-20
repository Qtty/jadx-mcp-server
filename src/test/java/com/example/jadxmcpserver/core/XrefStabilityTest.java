package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three get_xrefs_to_* tools and find_sink_call_sites must answer the same
 * thing whether or not the APK has been decompiled. None of them did.
 *
 * This is CalleeStabilityTest's question asked in the other direction. jadx's
 * reverse edges are not a record of the bytecode either -- they are a record of
 * the Java jadx intends to PRINT -- and four separate passes rewrite them:
 *
 * <pre>
 *   UsageInfoVisitor.replaceMethodUsage  merges a synthetic method's caller list
 *                                        into its replacement and EMPTIES it
 *                                        (from ClassModifier.modifySyntheticMethod)
 *   InlineMethods.updateUsageInfo        drops the inlining caller from the
 *                                        inlined method's useIn
 *   InlineMethods.replaceClsUsage        swaps the inlined method for its caller
 *                                        in the target class's useInMth and useIn
 *   ProcessAnonymous                     adds an outer class back to an anonymous
 *                                        class's useIn when inlining is undone
 * </pre>
 *
 * Measured on this APK before the fix, decompiling all 1,791 classes: 988
 * methods answered differently (1,343 caller edges lost, 380 gained, and 692
 * methods left claiming NOTHING calls them), 983 fields, 59 classes. On
 * a large production APK after only 3,000 of 141,461 classes: 1,536 methods, 1,458 fields,
 * 165 classes.
 *
 * Why that is expensive rather than untidy: the daemon is long-lived and shared,
 * so "has this class been decompiled yet" depends on some earlier, unrelated
 * request. A caller who asks who calls a sink and gets an empty list concludes
 * the sink is unreachable. That is a false negative, and get_xrefs_to_method is
 * the tool a router trace leans on hardest.
 *
 * The fix is {@link DexReverseIndex}: the same reverse edges, rebuilt from the
 * dex bytes and the declaration metadata, which no pass rewrites.
 *
 * Method order is pinned: the "cold" reads have to happen before anything in the
 * fixture is decompiled, or the test stops reproducing the bug it guards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class XrefStabilityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * A synthetic accessor: the worst case, because replaceMethodUsage does not
     * trim its caller list, it empties it.
     *
     * TabLayout.access$100 is called from four places in the bytecode and
     * reported zero once TabLayout is decompiled -- an agent asking "who reaches
     * this" got "nobody", which is the answer that ends a trace.
     */
    @Test
    @Order(1)
    void aSyntheticAccessorKeepsItsCallersAfterItsClassIsDecompiled() {
        String cls = "android.support.design.widget.TabLayout";

        List<Map<String, String>> cold = core.getXrefsToMethod(cls, "access$100");
        assertThat(cold).hasSize(4);

        core.getClassSource(cls);

        assertThat(core.getXrefsToMethod(cls, "access$100")).isEqualTo(cold);
    }

    /**
     * The same, one level in: an accessor on an inner class, reached through the
     * outer class's decompilation rather than its own.
     */
    @Test
    @Order(2)
    void anInnerClassAccessorKeepsItsCallers() {
        String cls = "android.support.v7.widget.RecyclerView.State";

        List<Map<String, String>> cold = core.getXrefsToMethod(cls, "access$2202");
        assertThat(cold).isNotEmpty();

        core.getClassSource("android.support.v7.widget.RecyclerView");

        assertThat(core.getXrefsToMethod(cls, "access$2202")).isEqualTo(cold);
    }

    /**
     * Fields moved the other way -- they GAINED users. InlineMethods re-points a
     * folded accessor's field accesses at whoever it was folded into, so a field
     * ends up naming a method whose own bytecode never touches it. Wrong in the
     * cheap direction, but still wrong, and still dependent on daemon history.
     */
    @Test
    @Order(3)
    void aFieldKeepsExactlyTheMethodsThatTouchIt() {
        String cls = "android.support.v4.view.ViewPropertyAnimatorCompat";

        List<Map<String, String>> cold = core.getXrefsToField(cls, "mEndAction");
        assertThat(cold).isNotEmpty();

        core.getClassSource(cls);

        assertThat(core.getXrefsToField(cls, "mEndAction")).isEqualTo(cold);
    }

    /** Class-level xrefs move too, through InlineMethods.replaceClsUsage. */
    @Test
    @Order(4)
    void aClassKeepsItsReferences() {
        String cls = "android.support.v7.internal.view.menu.BaseMenuPresenter";

        List<Map<String, String>> cold = core.getXrefsToClass(cls);
        assertThat(cold).isNotEmpty();

        core.getClassSource("android.support.v7.widget.ActionMenuPresenter");

        assertThat(core.getXrefsToClass(cls)).isEqualTo(cold);
    }

    /**
     * The sink sweep's other half.
     *
     * find_sink_call_sites reads getUnresolvedUsed(), which never moves, and --
     * since the in-APK sink blind spot was fixed -- the callers of in-APK
     * declarations that satisfy a sink. That second half was read out of
     * getUseIn() and turned a previously order-independent sweep into an
     * order-dependent one. This is the whole answer, every sink, before and
     * after.
     */
    @Test
    @Order(5)
    void theWholeSinkSweepIsUnchangedByDecompilation() {
        Map<String, Object> cold = core.findSinkCallSites(null, 0);
        assertThat(cold).isNotEmpty();

        core.getClassSource("jakhar.aseem.diva.MainActivity");
        core.getClassSource("android.support.v7.widget.ActionMenuPresenter");
        core.getClassSource("android.support.design.widget.TabLayout");

        assertThat(core.findSinkCallSites(null, 0)).isEqualTo(cold);
    }

    /**
     * The widest form: decompile every class in the APK, then re-ask everything.
     *
     * These probes were the worst offenders in the sweep. LogActivity.checkout
     * is the control -- it never moved, and must still not move.
     */
    @Test
    @Order(6)
    void nothingAnswersDifferentlyAfterTheWholeApkIsDecompiled() {
        List<String> methodProbes = List.of(
                "android.support.design.widget.TabLayout#access$100",
                "android.support.v7.widget.ActionMenuPresenter#access$200",
                "android.support.v7.widget.RecyclerView.State#access$2202",
                "android.support.v4.view.ViewPager#setScrollState",
                "jakhar.aseem.diva.LogActivity#checkout");
        List<String> fieldProbes = List.of(
                "android.support.v4.view.ViewPropertyAnimatorCompat#mEndAction",
                "android.support.v4.widget.AutoScrollHelper#mAnimating",
                "android.support.v7.widget.RecyclerView#sQuinticInterpolator");
        List<String> classProbes = List.of(
                "android.support.v7.internal.view.menu.BaseMenuPresenter",
                "android.support.v7.widget.ListPopupWindow",
                "android.support.design.internal.NavigationMenuPresenter",
                "jakhar.aseem.diva.NotesProvider.DBHelper");

        Map<String, Object> cold = new LinkedHashMap<>();
        for (String probe : methodProbes) {
            cold.put(probe, xrefsToMethod(probe));
        }
        for (String probe : fieldProbes) {
            cold.put(probe, xrefsToField(probe));
        }
        for (String probe : classProbes) {
            cold.put(probe, core.getXrefsToClass(probe));
        }
        Set<String> coldSinks = callSiteIdentities(core.findSinkCallSites(null, 0));

        for (String className : core.getAllClasses()) {
            core.getClassSource(className);
        }

        for (String probe : methodProbes) {
            assertThat(xrefsToMethod(probe)).as("%s", probe).isEqualTo(cold.get(probe));
        }
        for (String probe : fieldProbes) {
            assertThat(xrefsToField(probe)).as("%s", probe).isEqualTo(cold.get(probe));
        }
        for (String probe : classProbes) {
            assertThat(core.getXrefsToClass(probe)).as("%s", probe).isEqualTo(cold.get(probe));
        }
        assertThat(callSiteIdentities(core.findSinkCallSites(null, 0))).isEqualTo(coldSinks);
    }

    /**
     * A sweep's call sites, identified by class and descriptor rather than by
     * the name they are reported under.
     *
     * The name still moves, and this is the one instability the reverse index
     * does not fix, so it is projected out here rather than hidden: decompiling
     * an APK end to end removes synthetic bridge methods, and jadx re-aliases
     * the method left behind -- on this APK, ActivityChooserModel's
     * PersistHistoryAsyncTask.doInBackground2 becomes doInBackground. It is one
     * call site of 74, it is the SAME call site, and it was measured and written
     * up in 0783597 (514 of a large production APK's 872,113 aliases move the same way).
     * Everything else about the sweep -- which methods, under which sink, how
     * many -- must be identical, and that is what this compares.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> callSiteIdentities(Map<String, Object> sweep) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : sweep.entrySet()) {
            if (!(e.getValue() instanceof List)) {
                continue;  // "@truncated" / "@resolved_in_apk" bookkeeping
            }
            for (Object o : (List<Object>) e.getValue()) {
                Map<String, String> site = (Map<String, String>) o;
                String signature = site.get("signature");
                // "name(argTypes)retType" -> "(argTypes)retType"
                int paren = signature == null ? -1 : signature.indexOf('(');
                out.add(e.getKey() + "  " + site.get("className")
                        + (paren < 0 ? signature : signature.substring(paren)));
            }
        }
        return out;
    }

    /**
     * Stable is only half of it -- a stable wrong answer is worse than an
     * unstable one, so one answer is pinned to the bytecode by hand.
     *
     * TabLayout;->access$100(Landroid/support/design/widget/TabLayout;I)V is the
     * target of exactly four invoke-static instructions in the whole APK, all
     * four inside TabLayout$Tab, in setContentDescription, setCustomView,
     * setIcon and setText. Read out of the smali, not out of another tool.
     */
    @Test
    @Order(7)
    void theCallersAreTheOnesTheBytecodeNames() {
        List<String> callers = names(core.getXrefsToMethod(
                "android.support.design.widget.TabLayout", "access$100"));

        assertThat(callers).containsExactlyInAnyOrder(
                "android.support.design.widget.TabLayout.Tab.setContentDescription",
                "android.support.design.widget.TabLayout.Tab.setCustomView",
                "android.support.design.widget.TabLayout.Tab.setIcon",
                "android.support.design.widget.TabLayout.Tab.setText");
    }

    /**
     * Recursion is not a cross-reference. jadx's own UseSet drops a self edge
     * before it is ever recorded, get_callees reports it separately under
     * "callsSelf", and a caller list that answers "who calls this" with "itself"
     * tells a tracer nothing. The index has to agree, or every recursive method
     * in the APK gains a phantom caller.
     */
    @Test
    @Order(8)
    void aRecursiveMethodIsNotItsOwnCaller() {
        List<String> callers = names(core.getXrefsToMethod(
                "android.support.v4.app.FragmentActivity", "dumpViewHierarchy"));

        assertThat(callers)
                .doesNotContain("android.support.v4.app.FragmentActivity.dumpViewHierarchy");
    }

    private static List<Map<String, String>> xrefsToMethod(String probe) {
        String[] parts = probe.split("#", 2);
        return core.getXrefsToMethod(parts[0], parts[1]);
    }

    private static List<Map<String, String>> xrefsToField(String probe) {
        String[] parts = probe.split("#", 2);
        return core.getXrefsToField(parts[0], parts[1]);
    }

    private static List<String> names(List<Map<String, String>> entries) {
        return entries.stream()
                .map(e -> e.get("className") + "." + e.get("methodName"))
                .collect(Collectors.toList());
    }
}

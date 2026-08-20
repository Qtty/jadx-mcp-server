package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The forward direction the xref tools lack.
 *
 * getXrefsToMethod answers "who calls this". Sink tracing is the other
 * direction: what does this method reach. Before 1.5.6 the only way to walk
 * forward was to decompile the method and read the source, which is what every
 * route-to-sink worker in the analysis client currently does.
 *
 * The split between "callees" and "external" is the point, not a detail. A call
 * that does not resolve to a MethodNode in the dex is a call into the framework,
 * and a call into the framework is what a sink is -- so "external" is the sink
 * list for that method, already filtered. Measured cost per lookup is 0.0006 ms
 * (docs/research/2026-jadx-1.5.6-assessment.md section 3); the data is built by
 * load(), same as getUseIn().
 */
class CalleesTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * LogActivity.checkout calls its own processCC (in the dex) and Log.e,
     * Toast.makeText and friends (not in the dex). Both halves must be reported,
     * because a tracer that only sees one of them either loses the chain or
     * loses the sink.
     */
    @Test
    void reportsBothInApkAndFrameworkCallees() {
        Map<String, Object> callees = core.getCallees("jakhar.aseem.diva.LogActivity", "checkout");

        assertThat(internal(callees)).isNotEmpty();
        assertThat(external(callees)).isNotEmpty();
        assertThat(names(internal(callees))).contains("jakhar.aseem.diva.LogActivity.processCC");
        assertThat(names(external(callees))).contains("android.util.Log.e");
    }

    /**
     * The reason this tool exists. onCreate here builds a WebView; the answer an
     * agent needs is "setJavaScriptEnabled", and it arrives without decompiling
     * a line.
     */
    @Test
    void externalCalleesAreTheSinkListForTheMethod() {
        Map<String, Object> callees = core.getCallees(
                "jakhar.aseem.diva.InputValidation2URISchemeActivity", "onCreate");

        assertThat(names(external(callees)))
                .contains("android.webkit.WebSettings.setJavaScriptEnabled");
    }

    /**
     * "external" means unresolved, not "package android.*". An invoke on an
     * inherited framework method names the in-APK subclass as its receiver --
     * findViewById is declared by android.app.Activity but the bytecode says
     * LogActivity -- and nothing in the dex declares it, so it lands in
     * "external" under the subclass's name.
     *
     * Pinned because it is a trap for consumers: filtering "external" by
     * package to find sinks silently drops these.
     */
    @Test
    void externalIsDefinedByResolutionNotByPackage() {
        Map<String, Object> callees = core.getCallees("jakhar.aseem.diva.LogActivity", "checkout");

        assertThat(names(external(callees)))
                .contains("jakhar.aseem.diva.LogActivity.findViewById");
        assertThat(names(internal(callees)))
                .doesNotContain("jakhar.aseem.diva.LogActivity.findViewById");
    }

    /** Same entry shape as an xref: class, method and signature. */
    @Test
    void eachEntryNamesTheClassMethodAndSignature() {
        Map<String, Object> callees = core.getCallees("jakhar.aseem.diva.LogActivity", "checkout");

        for (List<Map<String, String>> side : List.of(internal(callees), external(callees))) {
            assertThat(side).isNotEmpty();
            assertThat(side).allSatisfy(e -> {
                assertThat(e.get("className")).isNotBlank();
                assertThat(e.get("methodName")).isNotBlank();
                assertThat(e.get("signature")).isNotBlank();
            });
        }
    }

    /**
     * A callee name is the next hop: the agent feeds it straight back into
     * get_class_source. Raw dex names would break the walk on the first step,
     * exactly as they do for xrefs.
     */
    @Test
    void inApkCalleeClassNamesResolveBackToSource() {
        List<Map<String, String>> internal =
                internal(core.getCallees("jakhar.aseem.diva.LogActivity", "checkout"));

        assertThat(internal).isNotEmpty();
        for (Map<String, String> e : internal.subList(0, Math.min(3, internal.size()))) {
            assertThat(core.getClassSource(e.get("className")))
                    .as("callee %s must resolve", e.get("className"))
                    .isNotBlank();
        }
    }

    /**
     * A native method has no body, so it calls nothing. That is an answer, not a
     * failure -- an agent branches on the empty list, so it must not be an error.
     */
    @Test
    void aMethodThatCallsNothingReturnsEmptyListsNotAnError() {
        Map<String, Object> callees = core.getCallees("jakhar.aseem.diva.DivaJni", "access");

        assertThat(internal(callees)).isEmpty();
        assertThat(external(callees)).isEmpty();
        assertThat(callees.get("callsSelf")).isEqualTo(false);
    }

    /**
     * findNamedViews walks a view tree by calling itself. Recursion is invisible
     * in getUsed() -- jadx reports it separately -- and any forward walk built on
     * this tool needs it as a termination guard.
     */
    @Test
    void directRecursionIsReported() {
        Map<String, Object> callees = core.getCallees(
                "android.support.v4.app.FragmentTransitionCompat21", "findNamedViews");

        assertThat(callees.get("callsSelf")).isEqualTo(true);
    }

    @Test
    void aNonRecursiveMethodReportsCallsSelfFalse() {
        Map<String, Object> callees = core.getCallees("jakhar.aseem.diva.LogActivity", "checkout");

        assertThat(callees.get("callsSelf")).isEqualTo(false);
    }

    /** Same wording as getClassSource, so an agent recognises it without a special case. */
    @Test
    void unknownClassRaisesNotFound() {
        assertThatThrownBy(() -> core.getCallees("com.nope.NoSuchClass", "anything"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Class not found");
    }

    @Test
    void unknownMethodOnARealClassRaisesNotFound() {
        assertThatThrownBy(() ->
                core.getCallees("jakhar.aseem.diva.LogActivity", "noSuchMethodZzz"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Method not found");
    }

    /**
     * FragmentManagerImpl declares four moveToState overloads and only the
     * widest one recurses. The caller has one name to give, so the answer is the
     * union -- the same choice getXrefsToMethod makes in the other direction,
     * and callsSelf is true if any overload recurses.
     *
     * Entries stay unique by class + signature, not by class + name: two
     * overloads of the same callee are two different hops and collapsing them
     * would hide one.
     */
    @Test
    void overloadsAreUnionedUnderTheOneName() {
        Map<String, Object> callees =
                core.getCallees("android.support.v4.app.FragmentManagerImpl", "moveToState");

        assertThat(internal(callees)).isNotEmpty();
        assertThat(external(callees)).isNotEmpty();
        assertThat(callees.get("callsSelf")).isEqualTo(true);
        assertThat(signatures(internal(callees))).doesNotHaveDuplicates();
        assertThat(signatures(external(callees))).doesNotHaveDuplicates();
        // Callees of the narrow overloads are present alongside the wide one's.
        assertThat(names(internal(callees)))
                .contains("android.support.v4.app.FragmentManagerImpl.moveToState");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> internal(Map<String, Object> callees) {
        return (List<Map<String, String>>) callees.get("callees");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> external(Map<String, Object> callees) {
        return (List<Map<String, String>>) callees.get("external");
    }

    private static List<String> signatures(List<Map<String, String>> entries) {
        return entries.stream()
                .map(e -> e.get("className") + "#" + e.get("signature"))
                .collect(Collectors.toList());
    }

    private static List<String> names(List<Map<String, String>> entries) {
        return entries.stream()
                .map(e -> e.get("className") + "." + e.get("methodName"))
                .collect(Collectors.toList());
    }
}

package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whole-APK sink discovery with no decompilation.
 *
 * A call that leaves the dex is a call into the framework, and a call into the
 * framework is what a sink is. getUnresolvedUsed() therefore turns "where does
 * this app touch WebView.loadUrl" from a decompile-and-read problem into a
 * metadata scan -- on a large APK, well over a hundred thousand classes swept
 * in about a tenth of a second, with no decompilation at all.
 *
 * The three things these tests pin, because each is a decision that could
 * plausibly have gone the other way:
 *
 *   1. A sink is matched by receiver *subtype*, not by exact declaring class.
 *      The bytecode names the receiver's static type, so an in-APK WebView
 *      subclass calling loadUrl arrives as com.foo.MyWebView.loadUrl and an
 *      exact android.webkit.WebView.loadUrl match drops it.
 *   2. Called with no arguments the sweep runs the caller's own sink catalogue
 *      (references/sink-catalog.md), keyed by the catalogue's tag names, so an
 *      agent can call it cold and file what comes back under the tag it already
 *      reasons about.
 *   3. Truncation is stated, never silent. A popular sink has hundreds of call
 *      sites; a capped list that does not say it was capped reads as complete.
 */
class SinkCallSitesTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";
    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * The cold call: no argument, and it still answers. DIVA's one WebView sits
     * in InputValidation2URISchemeActivity, and the sweep finds it without being
     * told what a WebView is.
     */
    @Test
    void aSweepWithNoArgumentRunsTheSinkCatalogue() {
        Map<String, Object> hits = core.findSinkCallSites(null, 0);

        assertThat(hits.keySet()).contains(
                "webview_loadurl", "webview_settings", "intent_launch", "intent_parse",
                "file_read", "file_write", "exec", "native_load", "class_load");
        assertThat(callers(hits, "webview_loadurl"))
                .contains("jakhar.aseem.diva.InputValidation2URISchemeActivity.get");
        assertThat(callers(hits, "webview_settings"))
                .contains("jakhar.aseem.diva.InputValidation2URISchemeActivity.onCreate");
        assertThat(callers(hits, "native_load"))
                .anyMatch(c -> c.startsWith("jakhar.aseem.diva."));
    }

    /**
     * Every sink asked for gets a key, even when it matched nothing. "Swept and
     * found none" and "never swept" are different facts, and an absent key
     * conflates them -- DIVA genuinely has no Runtime.exec and no Intent.parseUri.
     */
    @Test
    void everySweptSinkGetsAKeyEvenWhenItMatchesNothing() {
        Map<String, Object> hits = core.findSinkCallSites(null, 0);

        assertThat(hits).containsKey("exec");
        assertThat(sites(hits, "exec")).isEmpty();
        assertThat(hits).containsKey("intent_parse");
        assertThat(sites(hits, "intent_parse")).isEmpty();
    }

    /**
     * The subtype rule, and the reason the sweep is not a string equality test.
     *
     * MainActivity.startChallenge calls startActivity on itself. The invoke names
     * jakhar.aseem.diva.MainActivity as the receiver, because that is the static
     * type at the call, and nothing in the dex declares MainActivity.startActivity
     * -- so it lands in getUnresolvedUsed() under the subclass's name. Matching
     * android.content.Context.startActivity by exact declaring class silently
     * loses it, along with every other Activity that starts an Activity.
     */
    @Test
    void aSinkMatchesReceiverSubtypesNotOnlyItsDeclaringClass() {
        Map<String, Object> hits =
                core.findSinkCallSites(List.of("android.content.Context.startActivity"), 0);

        assertThat(callers(hits, "android.content.Context.startActivity")).contains(
                "jakhar.aseem.diva.MainActivity.startChallenge",
                "jakhar.aseem.diva.AccessControl1Activity.viewAPICredentials");
    }

    /**
     * An unrelated class that happens to declare a same-named method is not a
     * hit. This is what a bare-name match would get wrong: sweeping "write"
     * across a large APK returns many call sites against many receivers, only a
     * fraction of which are FileOutputStream.
     */
    @Test
    void aSinkDoesNotMatchAnUnrelatedClassWithTheSameMethodName() {
        Map<String, Object> hits =
                core.findSinkCallSites(List.of("java.io.FileOutputStream.write"), 0);

        for (Map<String, String> site : sites(hits, "java.io.FileOutputStream.write")) {
            assertThat(site.get("className")).isNotBlank();
        }
        // StringBuilder.append is an unresolved call in every APK; asking for a
        // sink on a class that has no such method must not drag it in.
        Map<String, Object> none =
                core.findSinkCallSites(List.of("java.io.FileOutputStream.append"), 0);
        assertThat(sites(none, "java.io.FileOutputStream.append")).isEmpty();
    }

    /**
     * The other half of the subtype rule, and the one that used to be missing.
     *
     * getUnresolvedUsed() holds calls that LEAVE the dex. So the moment the APK
     * itself declares the method -- a bundled SDK class, or the app's own
     * subclass overriding it -- the call resolves and drops out of that list
     * entirely. DIVA bundles support-v4, and
     * LocalBroadcastManager.sendBroadcastSync calls sendBroadcast on itself: one
     * real call site that the unresolved half cannot see at all.
     *
     * The failure mode is why this matters more than its size. The sweep does
     * not say "I cannot see in there", it returns an empty list, which reads as
     * "this app never calls it".
     */
    @Test
    void aSinkTheApkItselfDeclaresIsNotInvisible() {
        String sink = "android.support.v4.content.LocalBroadcastManager.sendBroadcast";

        Map<String, Object> hits = core.findSinkCallSites(List.of(sink), 0);

        assertThat(callers(hits, sink))
                .contains("android.support.v4.content.LocalBroadcastManager.sendBroadcastSync");
    }

    /**
     * Which in-APK types absorbed the dispatch, reported rather than assumed.
     *
     * This is a lead, not a caveat: on a real hybrid app the answer to
     * webview_loadurl is the list of the app's own WebView containers. DIVA's is
     * duller -- support-v4's LocalBroadcastManager declares the sendBroadcast
     * that its own sendBroadcastSync calls -- but the shape is the point, and an
     * agent that sees a class here knows the sink is reached through an in-APK
     * type it can decompile.
     */
    @Test
    void theSweepNamesTheInApkTypesItResolvedThrough() {
        String sink = "android.support.v4.content.LocalBroadcastManager.sendBroadcast";

        Map<String, Object> hits = core.findSinkCallSites(List.of(sink), 0);

        assertThat(resolvedInApk(hits)).containsKey(sink);
        assertThat(resolvedInApk(hits).get(sink))
                .contains("android.support.v4.content.LocalBroadcastManager");
    }

    /**
     * Named only if it actually absorbed something.
     *
     * DIVA's support-v4 FragmentActivity does override startActivityForResult,
     * so it is a type calls COULD hide behind -- but nothing in the APK calls
     * it, so listing it would send an agent to read a class with no call sites
     * on it. The key exists to point at where the calls went, not to enumerate
     * the class hierarchy.
     */
    @Test
    void anInApkTypeThatAbsorbedNoCallIsNotNamed() {
        Map<String, Object> hits = core.findSinkCallSites(
                List.of("android.app.Activity.startActivityForResult"), 0);

        assertThat(resolvedInApk(hits))
                .doesNotContainKey("android.app.Activity.startActivityForResult");
    }

    /**
     * The annotation obeys the cap it annotates.
     *
     * A bare name matches any receiver, so every in-APK class declaring it is a
     * type the sweep resolved through -- "getName" is 416 of them on a large production APK
     * against a call-site list capped at 100. Left uncapped the bookkeeping key
     * became three quarters of the response, so it takes the same limit and says
     * when it did, the way getAllClasses does.
     */
    @Test
    void theResolvedTypeListObeysTheSameCap() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("getName"), 2);

        List<String> types = resolvedInApk(hits).get("getName");
        assertThat(types).hasSize(3);
        assertThat(types.get(2)).startsWith("@truncated: showing 2 of ");
        assertThat(resolvedInApk(core.findSinkCallSites(List.of("getName"), 0)).get("getName"))
                .as("uncapped keeps every type and adds no marker")
                .hasSizeGreaterThan(2)
                .noneMatch(t -> t.startsWith("@truncated"));
    }

    /** A sink no in-APK type implements says so by absence, like @truncated. */
    @Test
    void aSinkNoInApkTypeImplementsReportsNoResolution() {
        Map<String, Object> hits =
                core.findSinkCallSites(List.of("android.content.Intent.parseUri"), 0);

        assertThat(hits).doesNotContainKey("@resolved_in_apk");
    }

    /** A bare method name is the deliberate wide net: any receiver, any package. */
    @Test
    void aBareMethodNameMatchesAnyReceiver() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("loadUrl"), 0);

        assertThat(callers(hits, "loadUrl"))
                .contains("jakhar.aseem.diva.InputValidation2URISchemeActivity.get");
    }

    /**
     * Constructors are call sites too -- new FileOutputStream(path) is the write,
     * not the later .write(). They are addressable the way the dex names them.
     */
    @Test
    void constructorsAreAddressableAsInit() {
        Map<String, Object> hits =
                core.findSinkCallSites(List.of("java.io.FileOutputStream.<init>"), 0);

        assertThat(sites(hits, "java.io.FileOutputStream.<init>")).isNotEmpty();
    }

    /** Category tokens and raw API tokens are the same vocabulary, mixable. */
    @Test
    void categoryTokensAndApiTokensCanBeMixedAndComeBackKeyedAsAsked() {
        Map<String, Object> hits = core.findSinkCallSites(
                List.of("native_load", "android.webkit.WebView.loadUrl"), 0);

        assertThat(hits.keySet())
                .containsExactlyInAnyOrder("native_load", "android.webkit.WebView.loadUrl");
        assertThat(sites(hits, "android.webkit.WebView.loadUrl")).isNotEmpty();
    }

    /**
     * A sink nobody calls is an answer, not a failure -- same contract as an
     * unreferenced xref target. Agents branch on the empty list.
     */
    @Test
    void anUnknownSinkYieldsAnEmptyListNotAnError() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("com.nope.Nope.nope"), 0);

        assertThat(hits).containsKey("com.nope.Nope.nope");
        assertThat(sites(hits, "com.nope.Nope.nope")).isEmpty();
    }

    /** Same entry shape as an xref and a callee: class, method, signature. */
    @Test
    void eachCallSiteNamesTheCallingClassMethodAndSignature() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("intent_launch"), 0);

        assertThat(sites(hits, "intent_launch")).isNotEmpty();
        assertThat(sites(hits, "intent_launch")).allSatisfy(e -> {
            assertThat(e.get("className")).isNotBlank();
            assertThat(e.get("methodName")).isNotBlank();
            assertThat(e.get("signature")).isNotBlank();
        });
    }

    /**
     * A call site is the next thing the agent reads, so its class name has to be
     * the alias get_class_source accepts. A raw dex name ends the trace here.
     */
    @Test
    void callSiteClassNamesResolveBackToSource() {
        List<Map<String, String>> found =
                sites(core.findSinkCallSites(List.of("class_load"), 0), "class_load");

        assertThat(found).isNotEmpty();
        for (Map<String, String> e : found.subList(0, Math.min(3, found.size()))) {
            assertThat(core.getClassSource(e.get("className")))
                    .as("call site %s must resolve", e.get("className"))
                    .isNotBlank();
        }
    }

    @Test
    void callSitesAreDedupedAndStablySorted() {
        Map<String, Object> first = core.findSinkCallSites(List.of("intent_launch"), 0);
        Map<String, Object> second = core.findSinkCallSites(List.of("intent_launch"), 0);

        assertThat(keys(sites(first, "intent_launch"))).doesNotHaveDuplicates();
        assertThat(keys(second, "intent_launch")).isEqualTo(keys(first, "intent_launch"));
        // Grouped by class, class order ascending -- the same ordering the xref
        // and callee tools use. Not lexicographic on "class.method": '$' sorts
        // before '.', so an inner class interleaves with its outer class's
        // methods and a naive isSorted() on the joined name is simply wrong.
        assertThat(sites(first, "intent_launch").stream()
                .map(e -> e.get("className")).collect(Collectors.toList())).isSorted();
    }

    /**
     * The cap has to be visible. 553 call sites for Context.startActivity on one
     * real APK, times a 20-entry catalogue, is a payload nobody wants by
     * accident -- but a list silently cut to its first N reads as the whole
     * truth, which is worse than a big response.
     *
     * The notice is a self-describing string, as it is on every other tool. It
     * used to be a {sink: total} map here and a bare sentence under a key in
     * searchMethodByName, so reading "was this cut short" took a different check
     * per tool -- and the count was a number with no units next to it, which is
     * the sort of thing that gets read as "3 truncated" rather than "3 is the
     * real total".
     */
    @Test
    void truncationIsStatedNeverSilent() {
        Map<String, Object> capped = core.findSinkCallSites(List.of("intent_launch"), 3);

        assertThat(sites(capped, "intent_launch")).hasSize(3);
        assertThat(capped).containsKey("@truncated");
        @SuppressWarnings("unchecked")
        List<String> truncated = (List<String>) capped.get("@truncated");
        int real = sites(core.findSinkCallSites(List.of("intent_launch"), 0),
                "intent_launch").size();
        assertThat(truncated).singleElement().asString()
                .startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX + ": ")
                .contains("intent_launch")
                .as("the real total, not the returned count")
                .contains(String.valueOf(real));
    }

    /** One notice per capped sink, so a partly-capped sweep names each one. */
    @Test
    void eachCappedSinkGetsItsOwnNotice() {
        Map<String, Object> capped = core.findSinkCallSites(
                List.of("intent_launch", "webview_loadurl"), 1);

        @SuppressWarnings("unchecked")
        List<String> truncated = (List<String>) capped.get("@truncated");
        assertThat(truncated).hasSize(1).allSatisfy(n ->
                assertThat(n).startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX + ": ")
                        .contains("intent_launch"));
    }

    @Test
    void anUncappedSweepReportsNoTruncation() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("intent_launch"), 0);

        assertThat(hits).doesNotContainKey("@truncated");
    }

    @Test
    void aCapLargerThanTheResultChangesNothing() {
        Map<String, Object> hits = core.findSinkCallSites(List.of("webview_loadurl"), 100);

        assertThat(sites(hits, "webview_loadurl")).hasSize(1);
        assertThat(hits).doesNotContainKey("@truncated");
    }

    /**
     * The property the timing test is a proxy for, asserted directly so it
     * cannot pass by being on a fast machine. JavaClass.loadingWouldRequireDecompilation()
     * is 1.5.6's own answer to "is this call about to be expensive"; if the sweep
     * had touched getMethods() or getCode() on these classes it would now be false.
     */
    @Test
    void theSweepDecompilesNothing() throws Exception {
        JadxAnalyzerCore fresh = new JadxAnalyzerCore(SMALL_APK);
        fresh.loadApk();
        try {
            long total = totalClasses(fresh);
            assertThat(coldClasses(fresh))
                    .as("a freshly loaded APK is entirely cold")
                    .isEqualTo(total);

            fresh.findSinkCallSites(null, 0);

            assertThat(coldClasses(fresh))
                    .as("no class got decompiled by the sweep")
                    .isEqualTo(total);
        } finally {
            fresh.close();
        }
    }

    /**
     * Reflection rather than a production accessor: "did that decompile
     * anything" is a test's question, and the core has no reason to expose the
     * decompiler itself.
     */
    private static long coldClasses(JadxAnalyzerCore core) throws Exception {
        return decompiler(core).getClasses().stream()
                .filter(jadx.api.JavaClass::loadingWouldRequireDecompilation)
                .count();
    }

    private static long totalClasses(JadxAnalyzerCore core) throws Exception {
        return decompiler(core).getClasses().size();
    }

    private static jadx.api.JadxDecompiler decompiler(JadxAnalyzerCore core) throws Exception {
        java.lang.reflect.Field field = JadxAnalyzerCore.class.getDeclaredField("jadx");
        field.setAccessible(true);
        return (jadx.api.JadxDecompiler) field.get(core);
    }

    /**
     * The default keys are the caller's impact_category vocabulary, not names
     * invented here: a finding filed from this sweep carries the tag straight
     * into references/sink-catalog.md.
     *
     * '@' is not legal in a class name, a method name or a tag, which is why the
     * sweep's own bookkeeping keys wear it. A caller reading the result by the
     * token it asked for never collides with one.
     */
    @Test
    void theDefaultKeysAreCatalogueTagsOrAtPrefixedBookkeeping() {
        Map<String, Object> hits = core.findSinkCallSites(null, 0);

        for (String key : hits.keySet()) {
            if (key.startsWith("@")) {
                assertThat(key).isIn("@truncated", "@resolved_in_apk");
                continue;
            }
            assertThat(key)
                    .as("catalogue tags are snake_case, not API names")
                    .matches("[a-z][a-z0-9_]*");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> sites(Map<String, Object> hits, String sink) {
        return (List<Map<String, String>>) hits.get(sink);
    }

    private static List<String> callers(Map<String, Object> hits, String sink) {
        return sites(hits, sink).stream()
                .map(e -> e.get("className") + "." + e.get("methodName"))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> resolvedInApk(Map<String, Object> hits) {
        Object v = hits.get("@resolved_in_apk");
        return v == null ? Map.of() : (Map<String, List<String>>) v;
    }

    private static List<String> keys(Map<String, Object> hits, String sink) {
        return keys(sites(hits, sink));
    }

    private static List<String> keys(List<Map<String, String>> entries) {
        List<String> out = new ArrayList<>();
        for (Map<String, String> e : entries) {
            out.add(e.get("className") + "#" + e.get("signature"));
        }
        return out;
    }
}

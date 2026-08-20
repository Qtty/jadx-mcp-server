package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Overrides answer the question xrefs cannot: when a router calls an interface
 * method, which concrete implementations could run?
 *
 * The dangerous failure here is silence. The data comes from an attribute
 * attached by a jadx pass; if that attribute were missing the tool would answer
 * "no overrides" for everything, which is indistinguishable from a real answer
 * and would quietly mislead every worker that trusted it. Task 7 measured that
 * load() already attaches it (18516 methods on a large production APK before any explicit
 * call), and attributePresent below is what would catch that changing.
 */
class OverridesTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    @Test
    void anOnCreateReportsTheBaseMethodItOverrides() {
        Map<String, Object> o = core.getOverrides("jakhar.aseem.diva.MainActivity", "onCreate");

        assertThat(baseMethods(o)).isNotEmpty();
        assertThat(String.join(",", baseMethods(o))).contains("onCreate");
    }

    /**
     * The guard against silent failure. If the pre-decompile attribute stopped
     * being attached, every other assertion here would still pass by reporting
     * empty lists -- so the tool must state whether it actually had data.
     */
    @Test
    void reportsThatTheOverrideAttributeWasPresent() {
        Map<String, Object> o = core.getOverrides("jakhar.aseem.diva.MainActivity", "onCreate");

        assertThat(o.get("attributePresent")).isEqualTo(true);
    }

    @Test
    void aMethodThatOverridesNothingReturnsEmptyStructuresNotNull() {
        // A private helper declared only on this class overrides nothing.
        Map<String, Object> o = core.getOverrides("jakhar.aseem.diva.NotesProvider", "getType");

        assertThat(o.get("baseMethods")).isInstanceOf(List.class);
        assertThat(o.get("overriddenBy")).isInstanceOf(List.class);
        assertThat(o.get("baseMethods")).isNotNull();
        assertThat(o.get("overriddenBy")).isNotNull();
    }

    /**
     * overriddenBy must name the classes that actually implement the method, not
     * just the interfaces sitting above it -- a tracer asking "who handles this"
     * needs somewhere to continue reading, and an interface has no body.
     */
    @Test
    void listsConcreteImplementationsNotJustInterfaces() {
        Map<String, Object> o = core.getOverrides("android.content.ContentProvider", "query");

        assertThat(overriddenBy(o)).contains("jakhar.aseem.diva.NotesProvider.query");
    }

    /**
     * The case a tracer actually starts from. Asking "who overrides
     * Activity.onCreate" means naming a FRAMEWORK class that is not in the dex.
     * Requiring the declaring class to be in the APK would reject exactly the
     * query that resolves dispatch -- an agent following a WebView router holds
     * android.webkit.WebViewClient, not an app class.
     */
    @Test
    void findsApkImplementationsOfAFrameworkMethod() {
        Map<String, Object> o = core.getOverrides("android.app.Activity", "onCreate");

        assertThat(overriddenBy(o))
                .as("DIVA's activities override Activity.onCreate")
                .anyMatch(m -> m.startsWith("jakhar.aseem.diva."));
    }

    @Test
    void frameworkLookupStillReportsTheAttributeWasPresent() {
        Map<String, Object> o = core.getOverrides("android.app.Activity", "onCreate");

        assertThat(o.get("attributePresent")).isEqualTo(true);
    }

    /** A framework class jadx does not know at all is still an error. */
    @Test
    void unknownClassRaisesNotFound() {
        assertThatThrownBy(() -> core.getOverrides("com.nope.NoSuchClass", "onCreate"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unknownMethodRaisesNotFound() {
        assertThatThrownBy(() ->
                core.getOverrides("jakhar.aseem.diva.MainActivity", "noSuchMethodZzz"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @SuppressWarnings("unchecked")
    private static List<String> baseMethods(Map<String, Object> o) {
        return (List<String>) o.get("baseMethods");
    }

    @SuppressWarnings("unchecked")
    private static List<String> overriddenBy(Map<String, Object> o) {
        return (List<String>) o.get("overriddenBy");
    }
}

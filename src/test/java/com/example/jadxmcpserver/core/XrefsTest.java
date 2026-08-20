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
 * Cross-references are the gap that actually degraded JADX-backed analyses. Without
 * them a worker cannot resolve interface dispatch, which is how most real
 * deeplink routers pick a destination -- the a large production APK c7 trace had to confirm
 * callers of BankPayMethod.getActivity by reading source by hand.
 *
 * An entry must name the referencing class AND method: a bare class name does
 * not tell a tracer where to continue following the chain.
 *
 * Cost was measured before any of this was written (docs/research/
 * 2026-jadx-capabilities.md): usage info is built during load() and getUseIn()
 * is 0 ms cold, so there is no index to build here.
 */
class XrefsTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    /**
     * DBHelper is instantiated by its outer NotesProvider, so it has real
     * callers. MainActivity would not work here: it is the launcher entry point,
     * so the framework calls it and nothing in the app does -- an empty result
     * that says nothing about whether xrefs work.
     */
    static final String REFERENCED_CLASS = "jakhar.aseem.diva.NotesProvider.DBHelper";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    @Test
    void findsClassesThatReferenceAClass() {
        List<Map<String, String>> xrefs = core.getXrefsToClass(REFERENCED_CLASS);

        assertThat(xrefs).isNotEmpty();
    }

    @Test
    void eachEntryNamesTheReferencingClassMethodAndSignature() {
        List<Map<String, String>> xrefs = core.getXrefsToClass(REFERENCED_CLASS);

        assertThat(xrefs).isNotEmpty();
        assertThat(xrefs).allSatisfy(x -> {
            assertThat(x.get("className")).isNotBlank();
            assertThat(x.get("methodName")).isNotNull();
            assertThat(x.get("signature")).isNotNull();
        });
    }

    /**
     * A class-level reference carries all three KEYS, two of them empty.
     *
     * The doc promises {className, methodName, signature} on every entry, so a
     * caller writes entry["signature"] once and applies it everywhere. A
     * superclass or interface bound has no enclosing method, and that entry used
     * to arrive with two keys -- so the documented access pattern raised a
     * KeyError rather than returning a value, on a class the caller had no way
     * to predict. It is not rare: measured over every class of both reference
     * APKs by {@link XrefEntryShapeProbe}, 902 of DivaApplication.apk's 14,451
     * entries are class-level (6.2%, across 631 of 1,791 classes) and 154,307 of
     * a large production APK's 1,930,800 (8.0%, across 30,754 of 141,461).
     *
     * Empty rather than absent, because the two facts are not the same: an
     * absent key says "this server does not report signatures", an empty one
     * says "this reference is the class's own, not made from inside a method",
     * which is a thing a tracer needs to know rather than a defect to hide.
     */
    @Test
    void aClassLevelReferenceCarriesEveryKeyWithAnEmptyMethodContext() {
        // C0303R names its own inner class in its declaration, so the reference
        // exists with no method enclosing it.
        List<Map<String, String>> xrefs =
                core.getXrefsToClass("jakhar.aseem.diva.C0303R.styleable");

        assertThat(xrefs).isNotEmpty();
        Map<String, String> classLevel = xrefs.stream()
                .filter(x -> "".equals(x.get("methodName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class-level entry to check"));
        assertThat(classLevel)
                .containsEntry("className", "jakhar.aseem.diva.C0303R")
                .containsEntry("methodName", "")
                .containsEntry("signature", "")
                .hasSize(3);
    }

    /**
     * The same claim as a universal over the fixture, because "one entry has
     * three keys" is what the previous version of this checked and the whole
     * point is that no entry anywhere is shaped differently.
     */
    @Test
    void noEntryOnThisApkIsMissingAKey() {
        long entries = 0;
        long classLevel = 0;
        for (String name : core.getAllClasses(null, 0)) {
            if (name.startsWith("@")) {
                continue;
            }
            for (Map<String, String> x : core.getXrefsToClass(name, 0)) {
                if (x.size() == 1 && x.keySet().iterator().next().startsWith("@")) {
                    continue;   // bookkeeping
                }
                entries++;
                assertThat(x.keySet())
                        .as("entry %s", x)
                        .containsExactlyInAnyOrder("className", "methodName", "signature");
                if ("".equals(x.get("methodName"))) {
                    classLevel++;
                }
            }
        }
        assertThat(entries).isEqualTo(14451);
        assertThat(classLevel)
                .as("the entries the old two-key shape produced")
                .isEqualTo(902);
    }

    /**
     * The names an xref reports are the names the caller will feed back into
     * get_class_source to keep tracing. If they are raw dex names the chain
     * breaks at the first hop.
     */
    @Test
    void referencingClassNamesResolveBackToSource() {
        List<Map<String, String>> xrefs = core.getXrefsToClass(REFERENCED_CLASS);

        assertThat(xrefs).isNotEmpty();
        for (Map<String, String> x : xrefs.subList(0, Math.min(3, xrefs.size()))) {
            assertThat(core.getClassSource(x.get("className")))
                    .as("caller %s must resolve", x.get("className"))
                    .isNotBlank();
        }
    }

    /** NotesProvider.getType reads the UriMatcher, and the provider calls it. */
    @Test
    void findsCallersOfAMethod() {
        List<Map<String, String>> xrefs =
                core.getXrefsToMethod("jakhar.aseem.diva.NotesProvider.DBHelper", "onCreate");

        assertThat(xrefs).isNotNull();
    }

    @Test
    void findsReadersAndWritersOfAField() {
        List<Map<String, String>> xrefs =
                core.getXrefsToField("jakhar.aseem.diva.NotesProvider", "urimatcher");

        assertThat(xrefs).isNotEmpty();
        assertThat(xrefs).allSatisfy(x -> assertThat(x.get("className")).isNotBlank());
    }

    /**
     * An unreferenced class is a fact, not a failure. MainActivity is the
     * launcher entry point: the framework starts it and no app code calls it.
     * Agents branch on the empty list, so it must not become an error.
     */
    @Test
    void aClassWithNoReferencesReturnsEmptyNotAnError() {
        assertThat(core.getXrefsToClass("jakhar.aseem.diva.MainActivity")).isEmpty();
    }

    @Test
    void unknownClassRaisesNotFound() {
        assertThatThrownBy(() -> core.getXrefsToClass("com.nope.NoSuchClass"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unknownMethodOnARealClassRaisesNotFound() {
        assertThatThrownBy(() ->
                core.getXrefsToMethod("jakhar.aseem.diva.MainActivity", "noSuchMethodZzz"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void unknownFieldOnARealClassRaisesNotFound() {
        assertThatThrownBy(() ->
                core.getXrefsToField("jakhar.aseem.diva.MainActivity", "noSuchFieldZzz"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    /** Inner classes were unreachable before Task 1; xrefs must not regress that. */
    @Test
    void resolvesXrefsForAnInnerClass() {
        List<Map<String, String>> xrefs = core.getXrefsToClass(
                "android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback");

        assertThat(xrefs).isNotNull();
    }
}

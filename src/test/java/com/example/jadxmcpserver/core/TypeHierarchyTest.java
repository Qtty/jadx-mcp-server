package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Type hierarchy is the other half of resolving interface dispatch: xrefs say
 * who calls an interface method, the hierarchy says which concrete classes could
 * be on the other end.
 *
 * The awkward case is a supertype that is not in the APK. android.app.Activity
 * is not a class in the dex, so a naive implementation reports nothing and the
 * caller cannot distinguish "no supertype" from "supertype not in this APK".
 * ClspGraph knows the framework types, and the plan is explicit that a silent
 * empty result is not acceptable here.
 */
class TypeHierarchyTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    @Test
    void reportsTheSuperclassOfAnActivity() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.MainActivity");

        assertThat(h.get("superClass")).asString().isNotBlank();
        assertThat(h.get("superClass")).asString().contains("Activity");
    }

    @Test
    void reportsDeclaredInterfaces() {
        // NotesProvider.DBHelper extends SQLiteOpenHelper; the class itself
        // declares no interfaces, so the key must still exist and be a list.
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.NotesProvider.DBHelper");

        assertThat(h.get("interfaces")).isInstanceOf(List.class);
    }

    /**
     * subClasses reports DIRECT subclasses. DIVA's activities extend
     * AppCompatActivity rather than android.app.Activity, so the direct parent
     * is what must find them -- asking android.app.Activity would be testing a
     * transitive relationship this key does not claim to answer.
     */
    @Test
    void findsASubclassDeclaredInTheApk() {
        Map<String, Object> h = core.getTypeHierarchy("android.support.v7.app.AppCompatActivity");

        assertThat(asList(h, "subClasses"))
                .contains("jakhar.aseem.diva.MainActivity");
    }

    /**
     * The transitive relationship lives in superTypes instead: MainActivity is
     * an android.app.Activity even though it does not extend it directly.
     */
    @Test
    void superTypesSpanTheWholeChainNotJustTheDirectParent() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.MainActivity");

        assertThat(asList(h, "superTypes")).contains("android.app.Activity");
    }

    @Test
    void reportsInnerAndOuterClasses() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.NotesProvider");

        assertThat(asList(h, "innerClasses"))
                .contains("jakhar.aseem.diva.NotesProvider.DBHelper");
    }

    @Test
    void innerClassNamesItsOuterClass() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.NotesProvider.DBHelper");

        assertThat(h.get("outerClass")).isEqualTo("jakhar.aseem.diva.NotesProvider");
    }

    /**
     * The plan's explicit requirement: a framework type not present in the APK
     * must still resolve its supertypes via ClspGraph, or say clearly that it
     * cannot -- never a silent empty result.
     */
    @Test
    void resolvesSupertypesOfAFrameworkTypeNotInTheApk() {
        Map<String, Object> h = core.getTypeHierarchy("android.app.Activity");

        assertThat(h).isNotEmpty();
        assertThat(h.get("inApk")).isEqualTo(false);
        assertThat(asList(h, "superTypes")).isNotEmpty();
    }

    @Test
    void marksAClassThatIsInTheApk() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.MainActivity");

        assertThat(h.get("inApk")).isEqualTo(true);
    }

    @Test
    void aCompletelyUnknownTypeRaisesRatherThanReturningEmpty() {
        assertThatThrownBy(() -> core.getTypeHierarchy("com.nope.NoSuchClass"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }

    /**
     * subClasses is a whole-APK scan with no limit parameter and, until now, no
     * cap: asking this tool about java.lang.Object on a large production APK names 100,036
     * direct subclasses. That was the only multi-megabyte response left after
     * the searches were capped -- and unlike them it has no parameter to narrow,
     * so the cap is not optional here. Capped, the call is 52,045 bytes. Nobody needs subclass 1001 of Object; a caller who does can
     * ask get_all_classes with a packagePrefix.
     */
    @Test
    void subClassesIsCappedAndSaysSoWhenItRunsAway() {
        Map<String, Object> h = core.getTypeHierarchy("java.lang.Object");
        List<String> subs = asList(h, "subClasses");

        assumeTrue(subs.size() > 1, "fixture must declare subclasses of Object");
        assertThat(subs.size())
                .isLessThanOrEqualTo(JadxAnalyzerCore.DEFAULT_RESULT_LIMIT + 1);
        if (subs.size() == JadxAnalyzerCore.DEFAULT_RESULT_LIMIT + 1) {
            assertThat(subs.get(subs.size() - 1))
                    .startsWith(JadxAnalyzerCore.TRUNCATION_MARKER_PREFIX + ": ");
        }
    }

    /** A hierarchy that fits carries no marker, so the marker means something. */
    @Test
    void aSmallSubclassListIsUnmarked() {
        assertThat(asList(core.getTypeHierarchy("android.support.v7.app.AppCompatActivity"),
                "subClasses"))
                .isNotEmpty()
                .noneMatch(n -> n.startsWith("@"));
    }

    /**
     * The transitive closure is answered for an inner class too.
     *
     * It was not: the ClspGraph key was ClassInfo.getFullName(), which spells an
     * inner class with a '.', and the graph is keyed on getRawName(), which
     * spells it with a '$'. So this list came back EMPTY for every inner and
     * anonymous class -- 1,140 of the 1,791 here, 51,544 of 141,461 on
     * a large production APK -- and an empty "superTypes" reads as "this class is not an
     * anything". Anonymous WebViewClients and BroadcastReceivers are exactly
     * that shape, so the hole sat under the question an analysis asks most.
     */
    @Test
    void anInnerClassGetsItsTransitiveSupertypes() {
        Map<String, Object> h = core.getTypeHierarchy("jakhar.aseem.diva.NotesProvider.DBHelper");

        assertThat(asList(h, "superTypes"))
                .contains("android.database.sqlite.SQLiteOpenHelper", "java.lang.Object");
    }

    /**
     * The round trip, over every class in the APK: each name this tool emits
     * must resolve, here, to the class it names.
     *
     * superClass, interfaces and superTypes used to be RAW dex names -- the one
     * vocabulary no class-taking tool is built on -- and the failure was not a
     * rejection but a denial: fed back, such a name misses the lookup, falls
     * through to the not-in-this-APK branch, and the tool answers inApk:FALSE
     * about a class it has in its own dex tree. That is what the second half of
     * this checks, and it is why the check is "in the APK implies inApk:true"
     * rather than "the name resolves".
     *
     * DivaApplication.apk is not obfuscated, so raw and alias names coincide for
     * most of it and only the inner classes exercise the '.'-versus-'$' half;
     * the same round-trip property holds on an obfuscated APK.
     */
    @Test
    void everyNameTheHierarchyEmitsResolvesToTheClassItNames() {
        Set<String> inApk = new HashSet<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (!n.startsWith("@")) {
                inApk.add(n);
            }
        }
        assertThat(inApk).hasSizeGreaterThan(1500);

        Set<String> checked = new HashSet<>();
        List<String> broken = new ArrayList<>();
        for (String cls : inApk) {
            for (String n : emitted(core.getTypeHierarchy(cls))) {
                if (n.startsWith("@") || !checked.add(n)) {
                    continue;
                }
                Map<String, Object> back = core.getTypeHierarchy(n);
                if (inApk.contains(n)) {
                    if (!Boolean.TRUE.equals(back.get("inApk"))
                            || !n.equals(back.get("className"))) {
                        broken.add(n + " -> inApk=" + back.get("inApk")
                                + " className=" + back.get("className"));
                    }
                } else if (Boolean.TRUE.equals(back.get("inApk"))
                        && !n.equals(back.get("className"))) {
                    broken.add(n + " resolved to a class named " + back.get("className"));
                }
            }
        }
        assertThat(checked).hasSizeGreaterThan(500);
        assertThat(broken).isEmpty();
    }

    /**
     * And the smali agrees about which class that is. The header is jadx's own
     * line, written from a name classDisplayName() never touched, so it is the
     * one place the server could name a class in a spelling of its own.
     */
    @Test
    void theSmaliHeaderNamesTheClassThatWasAskedFor() {
        for (String n : new String[] {
                "jakhar.aseem.diva.MainActivity",
                "jakhar.aseem.diva.NotesProvider.DBHelper",
                "android.support.design.widget.HeaderBehavior.FlingRunnable"}) {
            String smali = core.getSmaliOfClass(n);
            assertThat(smali).as("smali of " + n).isNotNull();

            String mark = "###### Class ";
            int at = smali.indexOf(mark);
            assertThat(at).as("smali of " + n + " carries a header").isNotNegative();
            String line = smali.substring(at + mark.length(), smali.indexOf('\n', at));
            String named = line.substring(0, line.lastIndexOf(" ("));

            assertThat(named).as("header of " + n).isEqualTo(n);
            assertThat(core.getSmaliOfClass(named))
                    .as("the header names the same class").isEqualTo(smali);
        }
    }

    private static List<String> emitted(Map<String, Object> h) {
        List<String> out = new ArrayList<>();
        for (String key : new String[] {"superClass", "outerClass"}) {
            if (h.get(key) instanceof String) {
                out.add((String) h.get(key));
            }
        }
        for (String key : new String[] {"interfaces", "superTypes", "innerClasses", "subClasses"}) {
            if (h.get(key) instanceof List) {
                out.addAll(asList(h, key));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Map<String, Object> h, String key) {
        assertThat(h).containsKey(key);
        return (List<String>) h.get(key);
    }
}

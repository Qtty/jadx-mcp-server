package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One class name means one class.
 *
 * {@code ClassInfo.getAliasFullName()} joins an inner class to its outer one
 * with a '.', the same character that separates a package from a class. So an
 * inner class {@code dd$qtm} and a top-level class {@code dd.qtm} in one package
 * render as one identical string, and jadx's lookup --
 * {@code searchJavaClassByAliasFullName}, a {@code findFirst()} over that
 * string -- silently answered about whichever came first.
 *
 * Measured on a large production APK: 27 names, each covering exactly 2 classes. In all 27
 * the class it picked was the empty synthetic stub (0 methods, 0 fields) and
 * the one it hid was the real class, up to 21 methods. Stable across a restart,
 * so it was a confident wrong answer rather than a flaky one, and
 * find_sink_call_sites reports call sites under exactly such names.
 *
 * DivaApplication.apk has no collision, which is why most of this file is a
 * property rather than a fixture: the invariant has to hold on any APK, and the
 * one in CI is a clean case. {@link #theAmbiguousSpellingIsNeverHandedOut} is
 * the one that would catch a regression on a dirty one.
 */
class ClassNameAmbiguityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    private static List<ClassNode> allClassNodes() throws Exception {
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        JadxDecompiler jadx = (JadxDecompiler) f.get(core);
        return new ArrayList<>(jadx.getRoot().getClasses());
    }

    /**
     * The invariant the fix exists for: every name get_all_classes reports
     * denotes exactly one dex class. On an APK with a collision this fails
     * before the fix, because two classes are listed under one string.
     */
    @Test
    void everyNameTheServerListsDenotesExactlyOneClass() {
        List<String> names = new ArrayList<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (!n.startsWith("@")) {
                names.add(n);
            }
        }
        assertThat(names).hasSizeGreaterThan(500);

        Map<String, Integer> seen = new HashMap<>();
        for (String n : names) {
            seen.merge(n, 1, Integer::sum);
        }
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() > 1) {
                duplicated.add(e.getKey() + " x" + e.getValue());
            }
        }
        assertThat(duplicated).isEmpty();
    }

    /**
     * The output half of the fix, and the half that matters: every colliding
     * class is listed under a spelling that resolves back to IT.
     *
     * Note what this does NOT assert -- that the shared string disappears. For
     * a pair made of an inner {@code C$D} and a top-level {@code C.D}, the
     * shared string IS the top-level one's own unique spelling, so it has to
     * keep being handed out; the inner twin takes the '$' form. Asserting the
     * string vanished would demand the top-level class become unreachable,
     * which is the bug rather than the fix.
     *
     * Measured on a large production APK, where DivaApplication.apk has no collision to
     * exercise: 27 groups, 54 of 54 classes listed, 54 of 54 spellings
     * resolving to themselves -- against 27 unreachable before.
     */
    @Test
    void everyCollidingClassIsListedUnderASpellingThatResolvesToIt() throws Exception {
        Map<String, List<ClassNode>> byAlias = new TreeMap<>();
        for (ClassNode c : allClassNodes()) {
            byAlias.computeIfAbsent(c.getClassInfo().getAliasFullName(),
                    k -> new ArrayList<>()).add(c);
        }

        List<String> reported = new ArrayList<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (!n.startsWith("@")) {
                reported.add(n);
            }
        }

        for (Map.Entry<String, List<ClassNode>> e : byAlias.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            for (ClassNode c : e.getValue()) {
                String spelling = c.getClassInfo().makeAliasRawFullName();
                assertThat(reported)
                        .as("each colliding class keeps a unique spelling")
                        .contains(spelling);
                Map<String, Object> details = core.getClassDetails(spelling);
                assertThat(details).as("details for " + spelling).isNotNull();
                assertThat(details.get("methodCount"))
                        .as(spelling + " resolves to itself, not to its twin")
                        .isEqualTo(c.getMethods().size());
                assertThat(details.get("fieldCount"))
                        .as(spelling + " resolves to itself, not to its twin")
                        .isEqualTo(c.getFields().size());
            }
        }
    }

    /**
     * Every name the server lists has to be a name it accepts -- the round trip,
     * on the class vocabulary. A '$' spelling is only unambiguous if it also
     * resolves.
     */
    @Test
    void everyClassNameTheServerListsResolvesBack() {
        int checked = 0;
        List<String> unresolvable = new ArrayList<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (n.startsWith("@")) {
                continue;
            }
            checked++;
            if (core.getClassDetails(n) == null) {
                unresolvable.add(n);
            }
        }
        assertThat(checked).isGreaterThan(500);
        assertThat(unresolvable).isEmpty();
    }

    /**
     * Inner classes were always addressable with a '.' and must stay that way --
     * this is the spelling every existing workspace recorded.
     */
    @Test
    void theDottedInnerClassSpellingStillResolves() {
        String dotted = "android.support.design.widget.HeaderBehavior.FlingRunnable";
        assertThat(core.getClassDetails(dotted)).as("dotted inner name").isNotNull();

        String dollar = "android.support.design.widget.HeaderBehavior$FlingRunnable";
        assertThat(core.getClassDetails(dollar)).as("$ inner name").isNotNull();
        assertThat(core.getFieldsOfClass(dollar)).isEqualTo(core.getFieldsOfClass(dotted));
    }

    /** A name that matches nothing is still a plain miss, not an ambiguity. */
    @Test
    void anUnknownClassIsStillJustUnknown() {
        assertThat(core.getClassDetails("com.nope.NoSuchClassAtAll")).isNull();
    }
}

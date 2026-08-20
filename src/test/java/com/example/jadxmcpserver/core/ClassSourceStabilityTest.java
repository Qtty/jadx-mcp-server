package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * get_class_source has to answer the same thing twice.
 *
 * On this fixture it does, in every history measured, and that is what these
 * tests lock. On a large APK it does NOT, and the residue is measured rather
 * than hidden -- see the class comment on
 * {@link JadxAnalyzerCore#getClassSource(String, int)} for the numbers and the
 * mechanism, and {@link ClassSourceStabilityProbe} for the harness that produced
 * them. Two APKs disagreeing about a determinism property is exactly the shape
 * that makes a fixture-only test misleading, so it is said here too:
 *
 * <pre>
 *   DivaApplication.apk   0 of 359 probed classes differ, under a whole-APK
 *                         warm-up, an every-enum warm-up, a reverse-order
 *                         re-read, a dex-reverse-index build, and member-listing
 *                         sweeps
 *   a large production APK             4 of 2,810 differ after 3,000 top-level classes and
 *                         every enum are warmed; 41 of 2,810 differ when the
 *                         same probes are merely asked in the opposite order
 * </pre>
 *
 * The probes are drawn from INSIDE the range the warm-up covers. A sample taken
 * beyond it measures classes nothing touched, which is how an earlier round in
 * this repo produced a passing-but-meaningless run.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClassSourceStabilityTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    /** Every fifth class, so the sample is a few hundred rather than a handful. */
    static final int STRIDE = 5;

    private static JadxAnalyzerCore freshCore() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        JadxAnalyzerCore core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
        return core;
    }

    /** A stride sample of every class the server will list, inner ones included. */
    private static List<String> probeSet(JadxAnalyzerCore core) {
        List<String> probes = new ArrayList<>();
        int i = 0;
        for (String name : core.getAllClasses()) {
            if (name.startsWith("@")) {
                continue;
            }
            if (i++ % STRIDE == 0) {
                probes.add(name);
            }
        }
        return probes;
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
        return decompiled;
    }

    private static Map<String, String> read(JadxAnalyzerCore core, List<String> probes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : probes) {
            out.put(name, core.getClassSource(name));
        }
        return out;
    }

    private static List<String> diff(Map<String, String> a, Map<String, String> b) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : a.entrySet()) {
            String other = b.get(e.getKey());
            if (!java.util.Objects.equals(e.getValue(), other)) {
                changed.add(e.getKey()
                        + " (" + (e.getValue() == null ? -1 : e.getValue().length())
                        + " chars -> " + (other == null ? -1 : other.length()) + ")");
            }
        }
        return changed;
    }

    /**
     * The scenario the finding described: ask, decompile several hundred other
     * classes, ask again. Byte for byte, not "roughly the same size".
     */
    @Test
    @Order(1)
    void theSourceIsTheSameAfterTheRestOfTheApkIsDecompiled() {
        JadxAnalyzerCore core = freshCore();
        List<String> probes = probeSet(core);
        assertThat(probes).hasSizeGreaterThan(300);

        Map<String, String> cold = read(core, probes);
        assertThat(warmUp(core)).isGreaterThan(500);
        Map<String, String> warm = read(core, probes);

        assertThat(diff(cold, warm)).isEmpty();
    }

    /**
     * A fresh core, the same probes, asked in the OPPOSITE order.
     *
     * Order alone is a history: probe 300 read first has seen nothing, read last
     * has seen 299 other classes decompile. This is the cheapest hostile history
     * there is, and on the large APK it is the one that finds ten times as much
     * drift as the warm-up scenario above.
     */
    @Test
    @Order(2)
    void theSourceDoesNotDependOnTheOrderTheClassesWereAsked() {
        JadxAnalyzerCore forward = freshCore();
        List<String> probes = probeSet(forward);
        Map<String, String> first = read(forward, probes);

        List<String> reversed = new ArrayList<>(probes);
        Collections.reverse(reversed);
        Map<String, String> second = read(freshCore(), reversed);

        assertThat(diff(first, second)).isEmpty();
    }

    /**
     * The tools that read the dex rather than the decompiled model must not move
     * it. get_methods_of_class, get_fields_of_class and the reverse index behind
     * get_xrefs_to_* all claim to decompile nothing; this is that claim measured
     * from the other side.
     */
    @Test
    @Order(3)
    void theDexReadingToolsDoNotDisturbTheSource() {
        JadxAnalyzerCore core = freshCore();
        List<String> probes = probeSet(core);
        Map<String, String> before = read(core, probes);

        for (String name : core.getAllClasses()) {
            if (name.startsWith("@")) {
                continue;
            }
            core.getMethodsOfClass(name, 5);
            core.getFieldsOfClass(name, 5);
        }
        // Forces DexReverseIndex.build, which reads every method's bytecode.
        core.getXrefsToMethod("android.support.design.widget"
                + ".FloatingActionButtonEclairMr1.ElevateToTranslationZAnimation",
                "getTargetShadowSize");

        assertThat(diff(before, read(core, probes))).isEmpty();
    }
}

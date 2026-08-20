package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which of the analysis's hardcoded figures actually move under warmth.
 *
 * The analysis claims three assertions are "false against a warm daemon". That is
 * a measurable claim about three specific numbers, so it gets measured rather
 * than believed.
 *
 * A probe, not a test: it asserts nothing and prints. Run it by name.
 */
class WarmthSensitiveAssertionProbe {

    static final String SMALL_APK = "misc/DivaApplication.apk";
    static final String BIG_APK = "misc/DivaApplication.apk";

    @Test
    void getAllClassesColdVersusWarm() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture missing");
        JadxAnalyzerCore core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
        try {
            List<String> cold = core.getAllClasses();
            System.out.println("SMALL cold getAllClasses: " + cold.size()
                    + " sorted=" + isSorted(cold));

            int decompiled = 0;
            for (String n : cold) {
                if (!n.startsWith("@")) {
                    core.getClassSource(n);
                    decompiled++;
                }
            }
            List<String> warm = core.getAllClasses();
            System.out.println("SMALL warm getAllClasses: " + warm.size()
                    + " sorted=" + isSorted(warm) + " decompiled=" + decompiled
                    + " equal=" + warm.equals(cold));
            if (!warm.equals(cold)) {
                for (String n : cold) {
                    if (!warm.contains(n)) {
                        System.out.println("   lost: " + n);
                    }
                }
                for (String n : warm) {
                    if (!cold.contains(n)) {
                        System.out.println("   gained: " + n);
                    }
                }
            }
        } finally {
            core.close();
        }
    }

    @Test
    void theRTableSourceLengthColdVersusWarm() {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        String target = "com.example.app.R.id";
        String smaliTarget = "kotlin.collections.ArraysKt___ArraysKt";

        JadxAnalyzerCore cold = new JadxAnalyzerCore(BIG_APK);
        cold.loadApk();
        long coldLen;
        long coldSmali;
        try {
            coldLen = body(cold.getClassSource(target, 0)).length();
            coldSmali = body(cold.getSmaliOfClass(smaliTarget, 0)).length();
            System.out.println("BIG cold  getClassSource(" + target + ") = "
                    + coldLen + " chars");
            System.out.println("BIG cold  getSmaliOfClass = " + coldSmali + " chars");
        } finally {
            cold.close();
        }

        JadxAnalyzerCore warm = new JadxAnalyzerCore(BIG_APK);
        warm.loadApk();
        try {
            // Warm the way an analysis does: decompile a few thousand unrelated classes.
            int n = 0;
            for (String name : warm.getAllClasses(null, 0)) {
                if (name.startsWith("@") || name.equals(target)) {
                    continue;
                }
                warm.getClassSource(name);
                if (++n >= 3000) {
                    break;
                }
            }
            long warmLen = body(warm.getClassSource(target, 0)).length();
            long warmSmali = body(warm.getSmaliOfClass(smaliTarget, 0)).length();
            System.out.println("BIG warm(" + n + ") getClassSource(" + target + ") = "
                    + warmLen + " chars  moved=" + (warmLen != coldLen));
            System.out.println("BIG warm  getSmaliOfClass = " + warmSmali
                    + " chars  moved=" + (warmSmali != coldSmali));
        } finally {
            warm.close();
        }

        // The history ClassSourceStabilityTest names as the hostile one: the
        // same classes read in the opposite order, which on this APK finds ten
        // times the drift a forward warm-up does.
        JadxAnalyzerCore reverse = new JadxAnalyzerCore(BIG_APK);
        reverse.loadApk();
        try {
            List<String> all = reverse.getAllClasses(null, 0);
            int n = 0;
            for (int i = all.size() - 1; i >= 0 && n < 3000; i--) {
                String name = all.get(i);
                if (name.startsWith("@") || name.equals(target)) {
                    continue;
                }
                reverse.getClassSource(name);
                n++;
            }
            long revLen = body(reverse.getClassSource(target, 0)).length();
            System.out.println("BIG reverse(" + n + ") getClassSource(" + target
                    + ") = " + revLen + " chars  moved=" + (revLen != coldLen));
        } finally {
            reverse.close();
        }
    }

    /** Strip a leading marker line, so what is measured is the answer itself. */
    private static String body(String answer) {
        if (answer != null && answer.startsWith("@")) {
            int nl = answer.indexOf("\n\n");
            return nl < 0 ? answer : answer.substring(nl + 2);
        }
        return answer == null ? "" : answer;
    }

    private static boolean isSorted(List<String> names) {
        for (int i = 1; i < names.size(); i++) {
            if (names.get(i - 1).compareTo(names.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }
}

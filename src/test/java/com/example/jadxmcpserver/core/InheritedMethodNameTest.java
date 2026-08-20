package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An error has to describe the cause it actually has.
 *
 * get_callees splits its answer by RESOLUTION rather than by package, so a call
 * to an inherited framework method is listed under the in-APK subclass the
 * bytecode names: {@code ForegroundLinearLayout.getPaddingTop}, not
 * {@code View.getPaddingTop}. That is the honest receiver and it is documented
 * as deliberate.
 *
 * It was also a name no name-taking tool accepted, because the subclass does not
 * DECLARE the method -- and the rejection read "Method not found: getPaddingTop
 * in class ...ForegroundLinearLayout", which says "no such method" when the
 * truth is "not here, one level up". Measured on DivaApplication.apk: 51 of 173
 * external callee entries fed back were rejected that way, which was every
 * rejection whose class was in the APK at all. (The other 122 name a class that
 * genuinely is not in the dex, and "Class not found" is the right answer for
 * those.)
 */
class InheritedMethodNameTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * The in-APK class really does not declare it, so this stays an error. What
     * changes is that the error names where the method lives.
     */
    @Test
    void anInheritedMethodSaysWhereItIsDeclared() {
        assertThatThrownBy(() -> core.getCallees(
                "android.support.design.internal.ForegroundLinearLayout", "getPaddingTop"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("is not declared by")
                .hasMessageContaining("inherits it from");
    }

    /** An in-APK ancestor is named as itself, so the caller can just re-ask. */
    @Test
    void anInApkAncestorIsNamedSoTheCallerCanReAsk() {
        String subclass = "jakhar.aseem.diva.MainActivity";
        String inherited = "setContentView";
        try {
            core.getCallees(subclass, inherited);
            return; // it declares it after all; nothing to assert
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("inherits it from");
        }
    }

    /**
     * A name that is not declared anywhere in the chain is still the plain
     * error -- the new branch must not swallow the old meaning.
     */
    @Test
    void aGenuinelyAbsentMethodIsStillJustNotFound() {
        assertThatThrownBy(() -> core.getCallees(
                "jakhar.aseem.diva.MainActivity", "noSuchMethodAnywhere"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Method not found")
                .hasMessageNotContaining("inherits it from");
    }

    /**
     * The whole point: no rejection of a name this server handed out may be
     * worded as "this method does not exist" when it plainly does.
     */
    @Test
    void noExternalCalleeIsRejectedWithAMisleadingCause() {
        List<String> misleading = new ArrayList<>();
        int fedBack = 0;
        for (String cn : core.getAllClasses(null, 0)) {
            if (cn.startsWith("@")) {
                continue;
            }
            for (String full : core.getMethodsOfClass(cn)) {
                if (full.startsWith("@")) {
                    continue;
                }
                int dot = full.lastIndexOf('.');
                String bare = dot < 0 ? full : full.substring(dot + 1);
                Map<String, Object> callees;
                try {
                    callees = core.getCallees(cn, bare);
                } catch (RuntimeException e) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, String>> external =
                        (List<Map<String, String>>) callees.get("external");
                for (Map<String, String> entry : external) {
                    String ecn = entry.get("className");
                    String emn = entry.get("methodName");
                    if (ecn == null || emn == null) {
                        continue;
                    }
                    fedBack++;
                    try {
                        core.getCallees(ecn, emn);
                    } catch (RuntimeException e) {
                        String msg = String.valueOf(e.getMessage());
                        boolean explained = msg.startsWith("Class not found")
                                || msg.contains("inherits it from");
                        if (!explained && misleading.size() < 10) {
                            misleading.add(ecn + "." + emn + " -> " + msg);
                        }
                    }
                }
                if (fedBack >= 400) {
                    break;
                }
            }
            if (fedBack >= 400) {
                break;
            }
        }
        assertThat(fedBack).isGreaterThan(100);
        assertThat(misleading).isEmpty();
    }
}

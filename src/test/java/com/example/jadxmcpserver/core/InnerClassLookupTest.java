package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Class lookup used to iterate jadx.getClasses(), which returns only top-level
 * classes -- 651 of DIVA's 1791. Every inner class was unreachable: source,
 * methods, fields and smali all reported "not found" for a name that plainly
 * exists. That is a large hole for deeplink work specifically, because the
 * listener and callback classes an intent router dispatches through are usually
 * inner classes.
 *
 * searchJavaClassByAliasFullName resolves them, and is also the alias-aware
 * lookup the rest of the server is supposed to use.
 */
class InnerClassLookupTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";
    static final String INNER_CLASS =
            "android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    /**
     * Asserting merely non-null here would be vacuous: jadx renders an inner
     * class inside its top-level parent's code, so JavaClass.getCode() on the
     * inner node returns "" rather than null. An empty string is a worse answer
     * than an error -- it reads as "this class has no code".
     */
    @Test
    void findsSourceOfAnInnerClass() {
        String source = core.getClassSource(INNER_CLASS);

        assertThat(source).isNotBlank();
        assertThat(source).contains("OnRequestPermissionsResultCallback");
    }

    @Test
    void stillFindsSourceOfATopLevelClass() {
        assertThat(core.getClassSource("jakhar.aseem.diva.MainActivity")).isNotNull();
    }

    @Test
    void unknownClassIsStillNull() {
        assertThat(core.getClassSource("com.nope.NoSuchClass")).isNull();
    }

    @Test
    void listsMethodsOfAnInnerClass() {
        List<String> methods = core.getMethodsOfClass(INNER_CLASS);
        assertThat(methods).isNotEmpty();
    }

    @Test
    void getsSmaliOfAnInnerClass() {
        assertThat(core.getSmaliOfClass(INNER_CLASS)).isNotNull();
    }

    /**
     * getAllClasses is what an agent enumerates before picking a target, so it
     * must list the same classes the lookups can resolve. Listing 651 while
     * 1791 are reachable would send workers searching for names that are absent.
     */
    @Test
    void getAllClassesIncludesInnerClasses() {
        assertThat(core.getAllClasses()).contains(INNER_CLASS);
    }

    /**
     * load_apk's totalClasses is the first number an agent sees, and it decides
     * how it budgets a sweep. Reporting the top-level count while get_all_classes
     * returns every class made those two disagree by 2.7x on this APK.
     */
    @Test
    void reportedClassCountMatchesWhatGetAllClassesReturns() {
        assertThat(core.getApkInfo().get("totalClasses"))
                .isEqualTo(core.getAllClasses().size());
    }
}

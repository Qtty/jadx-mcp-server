package com.example.jadxmcpserver.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.File;

/** Which lookup resolves an inner class alias? Decides how getClassSource is fixed. */
public final class InnerClassResolutionProbe {

    public static void main(String[] args) throws Exception {
        String target = "android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback";
        File apkFile = new File("misc/DivaApplication.apk");

        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(apkFile);
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            jadx.load();

            System.out.println("getClasses() size          = " + jadx.getClasses().size());
            System.out.println("getClassesWithInners() size = " + jadx.getClassesWithInners().size());

            JavaClass byAlias = jadx.searchJavaClassByAliasFullName(target);
            System.out.println("searchJavaClassByAliasFullName -> " + byAlias);
            if (byAlias != null) {
                System.out.println("  code non-null: " + (byAlias.getCode() != null));
            }

            boolean inTopLevel = jadx.getClasses().stream()
                    .anyMatch(c -> c.getFullName().equals(target));
            boolean inWithInners = jadx.getClassesWithInners().stream()
                    .anyMatch(c -> c.getFullName().equals(target));
            System.out.println("present in getClasses():          " + inTopLevel);
            System.out.println("present in getClassesWithInners(): " + inWithInners);
        }
    }
}

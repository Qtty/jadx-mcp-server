package com.example.jadxmcpserver.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.clsp.ClspGraph;

import java.io.File;
import java.util.Set;

/** What name format does ClspGraph accept, and does it know framework types? */
public final class ClspProbe {

    public static void main(String[] args) throws Exception {
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(new File("misc/DivaApplication.apk"));
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            jadx.load();
            ClspGraph clsp = jadx.getRoot().getClsp();

            for (String n : new String[] {
                    "android/app/Activity", "android.app.Activity",
                    "java/lang/Object", "java.lang.Object",
                    "android/webkit/WebViewClient",
                    "jakhar/aseem/diva/MainActivity",
            }) {
                System.out.println("isClsKnown(" + n + ") = " + clsp.isClsKnown(n));
            }

            for (String n : new String[] {
                    "android/app/Activity", "java/lang/String",
                    "jakhar/aseem/diva/MainActivity",
            }) {
                try {
                    Set<String> st = clsp.getSuperTypes(n);
                    System.out.println("getSuperTypes(" + n + ") = " + st);
                } catch (Exception e) {
                    System.out.println("getSuperTypes(" + n + ") threw " + e);
                }
            }

            System.out.println("isImplements(jakhar/aseem/diva/MainActivity, android/app/Activity) = "
                    + safeImplements(clsp, "jakhar/aseem/diva/MainActivity", "android/app/Activity"));

            // What does the APK class actually report as its superclass?
            var mainCls = jadx.getRoot().getClasses().stream()
                    .filter(c -> c.getClassInfo().getAliasFullName().equals("jakhar.aseem.diva.MainActivity"))
                    .findFirst().orElse(null);
            if (mainCls != null) {
                System.out.println("MainActivity superClass = " + mainCls.getSuperClass());
            }
        }
    }

    private static String safeImplements(ClspGraph clsp, String a, String b) {
        try {
            return String.valueOf(clsp.isImplements(a, b));
        } catch (Exception e) {
            return "threw " + e;
        }
    }
}

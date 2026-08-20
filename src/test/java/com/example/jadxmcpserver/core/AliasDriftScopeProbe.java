package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. Which of the three alias vocabularies actually drift when
 * classes decompile? The method side is established; this settles whether
 * CLASS and FIELD aliases need pinning too, so the fix is scoped by evidence
 * rather than by symmetry.
 *
 *   java -cp ... com.example.jadxmcpserver.core.AliasDriftScopeProbe <apk> <warmClasses>
 */
public class AliasDriftScopeProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int warmCount = args.length > 1 ? Integer.parseInt(args[1]) : 3000;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        RootNode root = ((JadxDecompiler) f.get(core)).getRoot();

        List<ClassNode> all = new ArrayList<>(root.getClasses());
        System.out.println("\n=== AliasDriftScopeProbe: " + apk + " ===");
        System.out.println("classes: " + all.size());

        Map<ClassNode, String> clsPin = new HashMap<>();
        Map<MethodNode, String> mthPin = new HashMap<>();
        Map<FieldNode, String> fldPin = new HashMap<>();
        for (ClassNode c : all) {
            clsPin.put(c, c.getClassInfo().getAliasFullName());
            for (MethodNode m : c.getMethods()) {
                mthPin.put(m, m.getMethodInfo().getAlias());
            }
            for (FieldNode fl : c.getFields()) {
                fldPin.put(fl, fl.getFieldInfo().getAlias());
            }
        }
        System.out.println("pinned classes=" + clsPin.size()
                + " methods=" + mthPin.size() + " fields=" + fldPin.size());

        int warmed = 0;
        for (ClassNode cls : all) {
            if (warmed >= warmCount) {
                break;
            }
            try {
                cls.decompile();
            } catch (Throwable ignored) {
                // still counts as warmed
            }
            warmed++;
        }
        System.out.println(">>> decompiled " + warmed + " classes <<<");

        int clsDrift = 0;
        int mthDrift = 0;
        int fldDrift = 0;
        List<String> fldExamples = new ArrayList<>();
        List<String> clsExamples = new ArrayList<>();
        for (ClassNode c : root.getClasses()) {
            String cp = clsPin.get(c);
            if (cp != null && !cp.equals(c.getClassInfo().getAliasFullName())) {
                clsDrift++;
                if (clsExamples.size() < 8) {
                    clsExamples.add(cp + "  ->  " + c.getClassInfo().getAliasFullName());
                }
            }
            for (MethodNode m : c.getMethods()) {
                String p = mthPin.get(m);
                if (p != null && !p.equals(m.getMethodInfo().getAlias())) {
                    mthDrift++;
                }
            }
            for (FieldNode fl : c.getFields()) {
                String p = fldPin.get(fl);
                if (p != null && !p.equals(fl.getFieldInfo().getAlias())) {
                    fldDrift++;
                    if (fldExamples.size() < 8) {
                        fldExamples.add(c.getClassInfo().getAliasFullName() + ": "
                                + p + "  ->  " + fl.getFieldInfo().getAlias());
                    }
                }
            }
        }
        System.out.println("CLASS  aliases drifted : " + clsDrift + " / " + clsPin.size());
        for (String e : clsExamples) {
            System.out.println("    " + e);
        }
        System.out.println("METHOD aliases drifted : " + mthDrift + " / " + mthPin.size());
        System.out.println("FIELD  aliases drifted : " + fldDrift + " / " + fldPin.size());
        for (String e : fldExamples) {
            System.out.println("    " + e);
        }
    }
}

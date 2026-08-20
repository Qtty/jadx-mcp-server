package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. What, if anything, tells two same-named dex classes apart?
 *
 * The repro probe found 27 alias names on a large production APK that each denote two
 * ClassNodes, and printed the same RAW name for both -- which would mean no
 * spelling can pick one. This checks every other identifier jadx carries before
 * a fix is designed around disclosure rather than disambiguation.
 */
public class ClassCollisionProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        JadxDecompiler jadx = (JadxDecompiler) f.get(core);
        RootNode root = jadx.getRoot();

        Map<String, List<ClassNode>> byAlias = new TreeMap<>();
        for (ClassNode c : root.getClasses()) {
            byAlias.computeIfAbsent(c.getClassInfo().getAliasFullName(),
                    k -> new ArrayList<>()).add(c);
        }

        int groups = 0;
        int sameRaw = 0;
        int sameType = 0;
        int sameInput = 0;
        int shown = 0;
        for (Map.Entry<String, List<ClassNode>> e : byAlias.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            groups++;
            List<ClassNode> nodes = e.getValue();
            ClassNode a = nodes.get(0);
            ClassNode b = nodes.get(1);
            boolean rawEq = a.getClassInfo().getFullName().equals(b.getClassInfo().getFullName());
            boolean typeEq = a.getClassInfo().getType().equals(b.getClassInfo().getType());
            String ia = inputFile(a);
            String ib = inputFile(b);
            boolean inputEq = ia.equals(ib);
            if (rawEq) {
                sameRaw++;
            }
            if (typeEq) {
                sameType++;
            }
            if (inputEq) {
                sameInput++;
            }
            JavaClass resolved = jadx.searchJavaClassByAliasFullName(e.getKey());
            ClassNode picked = resolved != null ? resolved.getClassNode() : null;

            if (shown++ < 6) {
                System.out.println("\n### " + e.getKey() + "   (" + nodes.size() + " nodes)");
                for (ClassNode c : nodes) {
                    System.out.println("   node " + System.identityHashCode(c)
                            + (c == picked ? " <== searchJavaClassByAliasFullName picks this" : "")
                            + "\n      rawFullName = " + c.getClassInfo().getFullName()
                            + "\n      rawName     = " + c.getRawName()
                            + "\n      type        = " + c.getClassInfo().getType()
                            + "\n      inputFile   = " + inputFile(c)
                            + "\n      methods     = " + c.getMethods().size()
                            + "  fields = " + c.getFields().size()
                            + "\n      accessFlags = " + c.getAccessFlags()
                            + "\n      firstMths   = " + firstMethods(c));
                }
            }
        }
        System.out.println("\n=== " + apk + " ===");
        System.out.println("collision groups            : " + groups);
        System.out.println("  identical raw full name   : " + sameRaw);
        System.out.println("  identical ArgType         : " + sameType);
        System.out.println("  identical input file      : " + sameInput);
    }

    private static String inputFile(ClassNode c) {
        try {
            return String.valueOf(c.getClsData().getInputFileName());
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String firstMethods(ClassNode c) {
        List<String> out = new ArrayList<>();
        for (MethodNode m : c.getMethods()) {
            out.add(m.getMethodInfo().getShortId());
            if (out.size() >= 4) {
                break;
            }
        }
        return out.toString();
    }
}

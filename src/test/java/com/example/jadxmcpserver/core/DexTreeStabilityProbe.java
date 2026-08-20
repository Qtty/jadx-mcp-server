package com.example.jadxmcpserver.core;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Is the dex TREE stable under decompilation, or only the dex BYTES?
 *
 * This decides whether a reverse index may be built lazily. scanCallees is safe
 * whenever it runs because MethodNode.getCodeReader() is a final copy of the dex
 * bytes -- but inverting it over the whole APK also has to enumerate every
 * method, and enumeration goes through RootNode.getClasses() /
 * ClassNode.getMethods() / ClassNode.getFields(), plus RootNode.resolveMethod()
 * to turn a call into an edge. If any of those moves as classes are decompiled,
 * a lazily built index is order-dependent in a new way and the whole fix is
 * pointless.
 *
 * Run by hand, not a test.
 */
public final class DexTreeStabilityProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int maxDecompile = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx.api.JadxDecompiler jadx = (jadx.api.JadxDecompiler) f.get(core);
        RootNode root = jadx.getRoot();

        Set<String> coldClasses = classIds(root);
        Set<String> coldMethods = methodIds(root);
        Set<String> coldFields = fieldIds(root);
        Set<String> coldResolved = resolvedCallEdges(root);
        System.out.printf("cold : classes=%d methods=%d fields=%d resolvedCallEdges=%d%n",
                coldClasses.size(), coldMethods.size(), coldFields.size(), coldResolved.size());

        List<ClassNode> classes = new ArrayList<>(root.getClasses());
        int n = Math.min(maxDecompile, classes.size());
        for (int i = 0; i < n; i++) {
            try {
                classes.get(i).decompile();
            } catch (Throwable ignored) {
                // still counts as touched
            }
        }
        System.out.println("decompiled " + n + " classes");

        Set<String> warmClasses = classIds(root);
        Set<String> warmMethods = methodIds(root);
        Set<String> warmFields = fieldIds(root);
        Set<String> warmResolved = resolvedCallEdges(root);
        System.out.printf("warm : classes=%d methods=%d fields=%d resolvedCallEdges=%d%n",
                warmClasses.size(), warmMethods.size(), warmFields.size(), warmResolved.size());

        report("classes", coldClasses, warmClasses);
        report("methods", coldMethods, warmMethods);
        report("fields", coldFields, warmFields);
        report("resolvedCallEdges", coldResolved, warmResolved);
    }

    private static Set<String> classIds(RootNode root) {
        Set<String> ids = new LinkedHashSet<>();
        for (ClassNode cls : root.getClasses()) {
            ids.add(cls.getClassInfo().getFullName());
        }
        return ids;
    }

    private static Set<String> methodIds(RootNode root) {
        Set<String> ids = new LinkedHashSet<>();
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                ids.add(mth.getMethodInfo().getFullId());
            }
        }
        return ids;
    }

    private static Set<String> fieldIds(RootNode root) {
        Set<String> ids = new LinkedHashSet<>();
        for (ClassNode cls : root.getClasses()) {
            for (FieldNode fld : cls.getFields()) {
                ids.add(fld.getFieldInfo().getFullId());
            }
        }
        return ids;
    }

    /**
     * Every caller->callee edge the dex bytes name, resolved through
     * RootNode.resolveMethod. This is what the reverse index would be built from,
     * so it is the thing that actually has to be stable.
     */
    private static Set<String> resolvedCallEdges(RootNode root) throws Exception {
        JadxAnalyzerCore probe = null;
        Set<String> edges = new HashSet<>();
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                if (mth.isNoCode()) {
                    continue;
                }
                jadx.api.plugins.input.data.ICodeReader reader = mth.getCodeReader();
                if (reader == null) {
                    continue;
                }
                String caller = mth.getMethodInfo().getFullId();
                try {
                    reader.copy().visitInstructions(insn -> {
                        if (insn.getOpcode() == jadx.api.plugins.input.insns.Opcode.UNKNOWN) {
                            return;
                        }
                        if (insn.getIndexType()
                                != jadx.api.plugins.input.insns.InsnIndexType.METHOD_REF) {
                            return;
                        }
                        insn.decode();
                        jadx.api.plugins.input.insns.custom.ICustomPayload payload = insn.getPayload();
                        jadx.core.dex.info.MethodInfo callee = jadx.core.dex.info.MethodInfo.fromRef(
                                root, payload != null
                                        ? (jadx.api.plugins.input.data.IMethodRef) payload
                                        : insn.getIndexAsMethod());
                        MethodNode node = root.resolveMethod(callee);
                        if (node != null) {
                            edges.add(caller + " -> " + node.getMethodInfo().getFullId());
                        }
                    });
                } catch (Throwable ignored) {
                    // counted by absence
                }
            }
        }
        return edges;
    }

    private static void report(String label, Set<String> cold, Set<String> warm) {
        List<String> lost = new ArrayList<>();
        List<String> gained = new ArrayList<>();
        for (String s : cold) {
            if (!warm.contains(s)) {
                lost.add(s);
            }
        }
        for (String s : warm) {
            if (!cold.contains(s)) {
                gained.add(s);
            }
        }
        System.out.printf("%-20s lost=%d gained=%d %s%n", label, lost.size(), gained.size(),
                lost.isEmpty() && gained.isEmpty() ? "STABLE" : "UNSTABLE");
        for (int i = 0; i < Math.min(3, lost.size()); i++) {
            System.out.println("        lost   " + lost.get(i));
        }
        for (int i = 0; i < Math.min(3, gained.size()); i++) {
            System.out.println("        gained " + gained.get(i));
        }
    }
}

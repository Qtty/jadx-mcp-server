package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. Reproduces the reported defects on the two name kinds that
 * never got the treatment methods got: FIELDS and CLASSES, plus the two
 * method-shaped leftovers (get_overrides warmth, get_callees external names).
 *
 * Two things this probe learned the hard way and now encodes:
 *
 *   - ORDER MATTERS. getFieldsOfClass calls JavaClass.load(), which decompiles.
 *     So every "cold" measurement has to run BEFORE the field pass, or it is
 *     measuring a warm tree and will report a reassuring zero.
 *   - THE FIELD LIST IS LATCHED. JavaClass.listsLoaded is set once and the list
 *     is never rebuilt, so asking twice in one JVM cannot show the drift. The
 *     answer depends on whether the first ask beat the decompiler, which is a
 *     BETWEEN-run difference: hence mode=cold / mode=warm writing a digest that
 *     two runs diff.
 *
 * Warm-up is by TOP-LEVEL class, deliberately: ClassNode.decompile() returns
 * ICodeInfo.EMPTY without doing any work when isInner(), so a warm-up that
 * counts inner classes toward its budget does far less than it claims.
 *
 *   java -cp ... NameKindReproProbe &lt;apk&gt; &lt;warmTopLevel&gt; &lt;cold|warm&gt; &lt;digestOut&gt;
 */
public class NameKindReproProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int warmCount = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        String mode = args.length > 2 ? args[2] : "cold";
        String digestOut = args.length > 3 ? args[3] : null;

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

        List<ClassNode> all = new ArrayList<>(root.getClasses());
        System.out.println("\n================ " + apk + " [" + mode + "] ================");
        System.out.println("classes in tree: " + all.size());

        List<ClassNode> topLevel = new ArrayList<>();
        for (ClassNode c : all) {
            if (!c.isInner()) {
                topLevel.add(c);
            }
        }
        System.out.println("top-level classes: " + topLevel.size());
        List<ClassNode> warmRange = topLevel.subList(0, Math.min(warmCount, topLevel.size()));

        // Probes: warm-range classes with fields, plus their inner classes,
        // which is where the JavaClass latch is expected to bite.
        List<ClassNode> probes = new ArrayList<>();
        for (ClassNode c : warmRange) {
            if (!c.getFields().isEmpty()) {
                probes.add(c);
            }
            for (ClassNode inner : c.getInnerClasses()) {
                if (!inner.getFields().isEmpty()) {
                    probes.add(inner);
                }
            }
        }
        System.out.println("probe classes (in warm range, have fields): " + probes.size());

        // ================= PRISTINE-TREE MEASUREMENTS FIRST =================
        classCollisions(all, jadx);

        Map<String, String> coldOverrides = overridesProbe(core, warmRange);
        List<String> coldExternalRoundTrip = calleeRoundTrip(core, root, warmRange);
        System.out.println("\n--- 5. getCallees EXTERNAL ROUND TRIP (pristine tree) ---");
        for (String s : coldExternalRoundTrip) {
            System.out.println("    " + s);
        }

        Map<FieldNode, String> fldPinHash = new HashMap<>();
        Map<FieldNode, String> fldPinIdent = new IdentityHashMap<>();
        for (ClassNode c : all) {
            for (FieldNode fl : c.getFields()) {
                fldPinHash.put(fl, fl.getFieldInfo().getAlias());
                fldPinIdent.put(fl, fl.getFieldInfo().getAlias());
            }
        }
        System.out.println("pinned fields: hash=" + fldPinHash.size()
                + " identity=" + fldPinIdent.size());

        // ================= THE MODE SPLIT =================
        // cold: ask fields before anything decompiles.
        // warm: decompile first, then ask. Same classes, same order, two runs.
        if ("warm".equals(mode)) {
            warmUp(warmRange);
        }

        Map<String, List<String>> fieldLists = new LinkedHashMap<>();
        for (ClassNode c : probes) {
            String name = c.getClassInfo().getAliasFullName();
            fieldLists.put(name, safeFields(core, name));
        }
        Map<String, String> fieldXref = fieldXrefProbe(core, fieldLists);

        if ("cold".equals(mode)) {
            warmUp(warmRange);
        }

        // ---- 2. field alias drift (after everything is warm, either way) ----
        int driftIdent = 0;
        int driftHash = 0;
        int hashMisses = 0;
        List<String> driftExamples = new ArrayList<>();
        for (ClassNode c : all) {
            for (FieldNode fl : c.getFields()) {
                String live = fl.getFieldInfo().getAlias();
                String pi = fldPinIdent.get(fl);
                if (pi != null && !pi.equals(live)) {
                    driftIdent++;
                    if (driftExamples.size() < 10) {
                        driftExamples.add(c.getClassInfo().getAliasFullName()
                                + ": " + pi + "  ->  " + live);
                    }
                }
                String ph = fldPinHash.get(fl);
                if (ph == null) {
                    hashMisses++;
                } else if (!ph.equals(live)) {
                    driftHash++;
                }
            }
        }
        System.out.println("\n--- 2. FIELD ALIAS DRIFT ---");
        System.out.println("drifted (identity-keyed): " + driftIdent + " / " + fldPinIdent.size());
        System.out.println("drifted (hash-keyed)    : " + driftHash
                + "   [hash lookup misses: " + hashMisses + "]");
        for (String e : driftExamples) {
            System.out.println("    " + e);
        }

        // ---- 1. shape of the field listing this run produced ----
        int unsorted = 0;
        int dexVsView = 0;
        for (ClassNode c : probes) {
            List<String> got = fieldLists.get(c.getClassInfo().getAliasFullName());
            List<String> sorted = new ArrayList<>(got);
            java.util.Collections.sort(sorted);
            if (!sorted.equals(got)) {
                unsorted++;
            }
            if (c.getFields().size() > got.size()) {
                dexVsView += c.getFields().size() - got.size();
            }
        }
        System.out.println("\n--- 1. getFieldsOfClass SHAPE (" + mode + ") ---");
        System.out.println("classes whose field list is NOT sorted     : "
                + unsorted + " / " + probes.size());
        System.out.println("fields the dex declares but the view drops : " + dexVsView);

        // ---- 4. overrides warmth ----
        Map<String, String> warmOverrides = overridesProbe(core, warmRange);
        int ovChanged = 0;
        List<String> ovExamples = new ArrayList<>();
        for (Map.Entry<String, String> e : coldOverrides.entrySet()) {
            String w = warmOverrides.get(e.getKey());
            if (!String.valueOf(e.getValue()).equals(String.valueOf(w))) {
                ovChanged++;
                if (ovExamples.size() < 6) {
                    ovExamples.add(e.getKey() + "\n        cold=" + e.getValue()
                            + "\n        warm=" + w);
                }
            }
        }
        System.out.println("\n--- 4. getOverrides WARMTH (within one run) ---");
        System.out.println("sampled methods whose override answer changed: "
                + ovChanged + " / " + coldOverrides.size());
        for (String e : ovExamples) {
            System.out.println("    " + e);
        }

        System.out.println("\n--- 5b. getCallees EXTERNAL ROUND TRIP (warm) ---");
        for (String s : calleeRoundTrip(core, root, warmRange)) {
            System.out.println("    " + s);
        }

        if (digestOut != null) {
            try (PrintWriter pw = new PrintWriter(digestOut)) {
                for (Map.Entry<String, List<String>> e : fieldLists.entrySet()) {
                    pw.println("FIELDS\t" + e.getKey() + "\t" + e.getValue());
                }
                for (Map.Entry<String, String> e : fieldXref.entrySet()) {
                    pw.println("XREF\t" + e.getKey() + "\t" + e.getValue());
                }
                for (Map.Entry<String, String> e : coldOverrides.entrySet()) {
                    pw.println("OVERRIDE\t" + e.getKey() + "\t" + e.getValue());
                }
            }
            System.out.println("\ndigest written: " + digestOut);
        }
    }

    private static void warmUp(List<ClassNode> warmRange) {
        int warmed = 0;
        for (ClassNode cls : warmRange) {
            try {
                cls.decompile();
            } catch (Throwable ignored) {
                // still counts as warmed
            }
            warmed++;
        }
        System.out.println(">>> decompiled " + warmed + " TOP-LEVEL classes <<<");
    }

    private static List<String> safeFields(JadxAnalyzerCore core, String name) {
        try {
            return new ArrayList<>(core.getFieldsOfClass(name));
        } catch (Throwable t) {
            return List.of("<<error:" + t.getClass().getSimpleName() + ">>");
        }
    }

    /** 3. Alias full names that denote more than one dex class. */
    private static void classCollisions(List<ClassNode> all, JadxDecompiler jadx) {
        Map<String, List<ClassNode>> byAlias = new TreeMap<>();
        for (ClassNode c : all) {
            byAlias.computeIfAbsent(c.getClassInfo().getAliasFullName(),
                    k -> new ArrayList<>()).add(c);
        }
        int collisions = 0;
        int unreachable = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, List<ClassNode>> e : byAlias.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            collisions++;
            JavaClass resolved = jadx.searchJavaClassByAliasFullName(e.getKey());
            ClassNode picked = resolved != null ? resolved.getClassNode() : null;
            for (ClassNode c : e.getValue()) {
                if (c != picked) {
                    unreachable++;
                }
            }
            if (examples.size() < 8) {
                List<String> raws = new ArrayList<>();
                for (ClassNode c : e.getValue()) {
                    raws.add(c.getClassInfo().getFullName()
                            + (c == picked ? " <== resolved" : ""));
                }
                examples.add(e.getKey() + "  covers " + e.getValue().size() + ": " + raws);
            }
        }
        System.out.println("\n--- 3. CLASS NAME COLLISIONS ---");
        System.out.println("alias names denoting >1 dex class: " + collisions);
        System.out.println("dex classes unreachable by name  : " + unreachable);
        for (String s : examples) {
            System.out.println("    " + s);
        }
    }

    /** 2b. Does a field name this server handed out resolve in get_xrefs_to_field? */
    private static Map<String, String> fieldXrefProbe(JadxAnalyzerCore core,
                                                      Map<String, List<String>> fieldLists) {
        Map<String, String> verdict = new LinkedHashMap<>();
        int budget = 400;
        for (Map.Entry<String, List<String>> e : fieldLists.entrySet()) {
            for (String entry : e.getValue()) {
                if (budget-- <= 0) {
                    return verdict;
                }
                if (entry.startsWith("@") || entry.startsWith("<<")) {
                    continue;
                }
                int sp = entry.lastIndexOf(' ');
                String fieldName = sp < 0 ? entry : entry.substring(sp + 1);
                String key = e.getKey() + "#" + fieldName;
                try {
                    verdict.put(key, "ok:" + core.getXrefsToField(e.getKey(), fieldName).size());
                } catch (Throwable t) {
                    verdict.put(key, "ERR:" + t.getMessage());
                }
            }
        }
        return verdict;
    }

    /** 4. getOverrides answers for a sample of methods inside the warm range. */
    private static Map<String, String> overridesProbe(JadxAnalyzerCore core,
                                                      List<ClassNode> warmRange) {
        Map<String, String> out = new LinkedHashMap<>();
        int budget = 150;
        for (ClassNode c : warmRange) {
            String cn = c.getClassInfo().getAliasFullName();
            for (MethodNode m : c.getMethods()) {
                if (budget-- <= 0) {
                    return out;
                }
                String mn = m.getMethodInfo().getAlias();
                try {
                    Map<String, Object> r = core.getOverrides(cn, mn, null, 0);
                    out.put(cn + "#" + mn, "attr=" + r.get("attributePresent")
                            + " base=" + r.get("baseMethods")
                            + " by=" + r.get("overriddenBy"));
                } catch (Throwable t) {
                    out.put(cn + "#" + mn, "ERR:" + t.getMessage());
                }
            }
        }
        return out;
    }

    /** 5. Every name getCallees hands out, fed back to a name-taking tool. */
    private static List<String> calleeRoundTrip(JadxAnalyzerCore core, RootNode root,
                                                List<ClassNode> warmRange) {
        int checked = 0;
        int rejected = 0;
        int classMissing = 0;
        int methodMissing = 0;
        int classIsInApk = 0;
        int budget = 120;
        Set<String> examples = new LinkedHashSet<>();
        for (ClassNode c : warmRange) {
            String cn = c.getClassInfo().getAliasFullName();
            for (MethodNode m : c.getMethods()) {
                if (budget-- <= 0) {
                    break;
                }
                Map<String, Object> r;
                try {
                    r = core.getCallees(cn, m.getMethodInfo().getAlias(), null, 0);
                } catch (Throwable t) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, String>> ext = (List<Map<String, String>>) r.get("external");
                for (Map<String, String> entry : ext) {
                    String ecn = entry.get("className");
                    String emn = entry.get("methodName");
                    if (ecn == null || emn == null) {
                        continue;
                    }
                    checked++;
                    try {
                        core.getCallees(ecn, emn, null, 0);
                    } catch (Throwable t) {
                        rejected++;
                        String msg = String.valueOf(t.getMessage());
                        if (msg.startsWith("Class not found")) {
                            classMissing++;
                        } else {
                            methodMissing++;
                            if (root.resolveClass(ArgType.object(ecn)) != null) {
                                classIsInApk++;
                                if (examples.size() < 8) {
                                    examples.add("IN-APK CLASS, INHERITED METHOD: "
                                            + ecn + "." + emn + "  ->  " + msg);
                                }
                            }
                        }
                    }
                }
            }
            if (budget <= 0) {
                break;
            }
        }
        List<String> out = new ArrayList<>();
        out.add("external entries fed back: " + checked
                + "   rejected: " + rejected
                + "   (class-not-found " + classMissing
                + ", method-not-found " + methodMissing
                + ", of which class IS in apk: " + classIsInApk + ")");
        out.addAll(examples);
        return out;
    }
}

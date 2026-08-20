package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. What get_type_hierarchy's own answers resolved to, before
 * and after the names in them were put on the display vocabulary.
 *
 * The old code emitted superClass, interfaces and superTypes as RAW dex names.
 * This reproduces the OLD lookup exactly -- the four tiers as they stood, from
 * jadx primitives, indexed so that the tier-3/4 findFirst() scans do not turn
 * into 141,461 iterations per name -- and asks, for every class the hierarchy
 * mentions: what verdict did the old spelling get, and what does the new one
 * get? "inApk:false for a class that is in the dex" is the interesting cell.
 *
 * Run: java -cp target/test-classes:target/classes:$(deps) \
 *          com.example.jadxmcpserver.core.TypeHierarchyVocabularyProbe [apk]
 */
public class TypeHierarchyVocabularyProbe {

    static RootNode root;
    static JadxDecompiler jadx;
    static ClspGraph clsp;
    static Map<String, ClassNode> byUnique = new HashMap<>(1 << 16);
    static Map<String, ClassNode> byAlias = new HashMap<>(1 << 16);
    static Map<String, ClassNode> byOrigFull = new HashMap<>(1 << 16);
    static Map<String, List<ClassNode>> ambiguousGroups = new HashMap<>();
    static Set<String> ambiguous = new HashSet<>();

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0]
                : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        jadx = (JadxDecompiler) f.get(core);
        root = jadx.getRoot();
        clsp = root.getClsp();
        Method resolveNodes =
                JadxAnalyzerCore.class.getDeclaredMethod("resolveClassNodes", String.class);
        resolveNodes.setAccessible(true);
        Method displayName =
                JadxAnalyzerCore.class.getDeclaredMethod("classDisplayName", ClassNode.class);
        displayName.setAccessible(true);

        List<ClassNode> classes = new ArrayList<>(root.getClasses());
        System.out.println("classes: " + classes.size());
        indexTheOldLookup(classes);
        System.out.println("ambiguous alias names: " + ambiguous.size());

        // ---- 1. the names the hierarchy hands out for a supertype/interface --
        Map<ClassNode, String> oldSpelling = new IdentityHashMap<>();
        Set<String> frameworkRaw = new HashSet<>();
        long mentions = 0;
        for (ClassNode c : classes) {
            List<ArgType> types = new ArrayList<>();
            if (c.getSuperClass() != null) {
                types.add(c.getSuperClass());
            }
            types.addAll(c.getInterfaces());
            for (ArgType t : types) {
                if (!t.isObject()) {
                    continue;
                }
                mentions++;
                ClassNode n = root.resolveClass(t);
                if (n != null) {
                    oldSpelling.putIfAbsent(n, t.getObject());
                } else {
                    frameworkRaw.add(t.getObject());
                }
            }
        }
        System.out.println("supertype/interface mentions: " + mentions);
        System.out.println("distinct classes IN THE APK named as a supertype or interface: "
                + oldSpelling.size());
        System.out.println("distinct types NOT in the APK named the same way: "
                + frameworkRaw.size());

        int falseNegative = 0;   // in the APK, old spelling said inApk:false
        int notFound = 0;        // in the APK, old spelling threw "Class not found"
        int wrongClass = 0;      // old spelling resolved to a DIFFERENT class
        int oldAmbiguous = 0;    // old spelling was refused as ambiguous
        int agreed = 0;
        int newBroken = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<ClassNode, String> e : oldSpelling.entrySet()) {
            ClassNode node = e.getKey();
            String raw = e.getValue();
            List<ClassNode> was = oldResolve(raw);
            if (was.size() > 1) {
                oldAmbiguous++;
            } else if (was.isEmpty()) {
                if (clsp.isClsKnown(raw)) {
                    falseNegative++;
                    if (examples.size() < 5) {
                        examples.add("inApk:false for " + raw + "  -> now "
                                + displayName.invoke(core, node));
                    }
                } else {
                    notFound++;
                }
            } else if (was.get(0) != node) {
                wrongClass++;
                if (examples.size() < 8) {
                    examples.add("WRONG CLASS: " + raw + " resolved to "
                            + was.get(0).getClassInfo().makeAliasRawFullName());
                }
            } else {
                agreed++;
            }
            @SuppressWarnings("unchecked")
            List<ClassNode> now = (List<ClassNode>) resolveNodes.invoke(
                    core, displayName.invoke(core, node));
            if (now.size() != 1 || now.get(0) != node) {
                newBroken++;
            }
        }
        System.out.println();
        System.out.println("VERDICT under the OLD spelling, for the "
                + oldSpelling.size() + " classes that ARE in the APK:");
        System.out.println("  same class, inApk:true      " + agreed);
        System.out.println("  inApk:FALSE (denied)        " + falseNegative);
        System.out.println("  \"Class not found\" (threw)   " + notFound);
        System.out.println("  answered about ANOTHER class " + wrongClass);
        System.out.println("  refused as ambiguous        " + oldAmbiguous);
        System.out.println("  new spelling not resolving back to itself: " + newBroken);
        for (String x : examples) {
            System.out.println("    e.g. " + x);
        }

        // ---- 2. every class, asked about by the raw '$' name it used to be
        // handed out under -- the direct form of "the tool denies the class".
        int rawDenied = 0;
        int rawNotFound = 0;
        int rawOk = 0;
        for (ClassNode c : classes) {
            // getRawName(), i.e. ArgType.getObject(): the exact spelling the
            // hierarchy handed out, not the reconstructed makeRawFullName().
            String raw = c.getClassInfo().getRawName();
            List<ClassNode> was = oldResolve(raw);
            if (was.size() == 1 && was.get(0) == c) {
                rawOk++;
            } else if (was.isEmpty()) {
                if (clsp.isClsKnown(raw)) {
                    rawDenied++;
                } else {
                    rawNotFound++;
                }
            }
        }
        System.out.println();
        System.out.println("asked by the RAW '$' name, over all " + classes.size() + " classes:");
        System.out.println("  resolved to itself   " + rawOk);
        System.out.println("  inApk:FALSE (denied) " + rawDenied);
        System.out.println("  not found            " + rawNotFound);

        // ---- 3. superTypes, whose clsp key was the '.' name -----------------
        int emptyBefore = 0;
        int nonEmptyAfter = 0;
        int inner = 0;
        long rawNamesInApk = 0;
        long superTypeNames = 0;
        for (ClassNode c : classes) {
            ClassInfo ci = c.getClassInfo();
            Set<String> was = clsp.getSuperTypes(ci.getFullName());
            Set<String> now = clsp.getSuperTypes(ci.getRawName());
            if (ci.isInner()) {
                inner++;
            }
            if (was.isEmpty() && !now.isEmpty()) {
                emptyBefore++;
            }
            if (!now.isEmpty()) {
                nonEmptyAfter++;
            }
            for (String st : now) {
                superTypeNames++;
                if (root.resolveRawClass(st) != null) {
                    rawNamesInApk++;
                }
            }
        }
        System.out.println();
        System.out.println("superTypes: inner classes " + inner
                + ", classes with a non-empty list " + nonEmptyAfter
                + ", of which EMPTY under the old '.' key " + emptyBefore);
        System.out.println("superTypes entries " + superTypeNames
                + ", of which name a class in this APK (raw, was un-resolvable) "
                + rawNamesInApk);

        // ---- 4. the smali header on the collision classes -------------------
        int headerWrong = 0;
        int headerFixed = 0;
        List<String> headerExamples = new ArrayList<>();
        for (Map.Entry<String, List<ClassNode>> g : new TreeMap<>(ambiguousGroups).entrySet()) {
            for (ClassNode c : g.getValue()) {
                JavaClass jc = (JavaClass) jadx.getJavaNodeByRef(c);
                String rawHeader = firstHeader(jc.getSmali());
                List<ClassNode> pointsAt = oldResolve(headerName(rawHeader));
                if (pointsAt.size() != 1 || pointsAt.get(0) != c) {
                    headerWrong++;
                    if (headerExamples.size() < 4) {
                        headerExamples.add(rawHeader + "  [asked for "
                                + c.getClassInfo().makeAliasRawFullName() + "]");
                    }
                }
                String fixedHeader = firstHeader(
                        core.getSmaliOfClass(c.getClassInfo().makeAliasRawFullName(), 0));
                @SuppressWarnings("unchecked")
                List<ClassNode> now = (List<ClassNode>) resolveNodes.invoke(
                        core, headerName(fixedHeader));
                if (now.size() == 1 && now.get(0) == c) {
                    headerFixed++;
                }
            }
        }
        int collisionClasses = 0;
        for (List<ClassNode> g : ambiguousGroups.values()) {
            collisionClasses += g.size();
        }
        System.out.println();
        System.out.println("smali header, over the " + collisionClasses
                + " classes in " + ambiguousGroups.size() + " collision groups:");
        System.out.println("  header named a different class (jadx's own): " + headerWrong);
        System.out.println("  header resolves to the class asked for, now:  " + headerFixed);
        for (String x : headerExamples) {
            System.out.println("    e.g. " + x);
        }

        // ---- 5. the acceptance run: cold, warm, and after a restart ---------
        List<String> probeNames = probeSample(classes, displayName, core);
        System.out.println();
        System.out.println("round trip over " + probeNames.size()
                + " classes drawn evenly across the dex tree");
        roundTrip(core, resolveNodes, displayName, probeNames, "cold");
        warmUp(core, probeNames);
        roundTrip(core, resolveNodes, displayName, probeNames, "warm");

        core.close();
        JadxAnalyzerCore restarted = new JadxAnalyzerCore(apk);
        restarted.loadApk();
        java.lang.reflect.Field f2 = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f2.setAccessible(true);
        jadx = (JadxDecompiler) f2.get(restarted);
        root = jadx.getRoot();
        clsp = root.getClsp();
        roundTrip(restarted, resolveNodes, displayName, probeNames, "restart");
        restarted.close();
    }

    /**
     * Probe classes spread evenly over root.getClasses(), not taken off the
     * front of it -- and top-level, because ClassNode.decompile() is a no-op on
     * an inner class, so warming an inner one warms nothing.
     */
    private static List<String> probeSample(List<ClassNode> classes, Method displayName,
            JadxAnalyzerCore core) throws Exception {
        List<String> names = new ArrayList<>();
        int step = Math.max(1, classes.size() / 40);
        for (int i = 0; i < classes.size() && names.size() < 40; i += step) {
            ClassNode c = classes.get(i);
            if (c.getClassInfo().isInner()) {
                continue;
            }
            names.add((String) displayName.invoke(core, c));
        }
        return names;
    }

    /** Decompile the probe classes themselves, which is what an analysis does. */
    private static void warmUp(JadxAnalyzerCore core, List<String> probeNames) {
        for (String n : probeNames) {
            try {
                core.getClassSource(n);
            } catch (Exception ignored) {
                // a class that will not decompile is still warmed by trying
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void roundTrip(JadxAnalyzerCore core, Method resolveNodes,
            Method displayName, List<String> probeNames, String phase) throws Exception {
        int names = 0;
        int inApkNames = 0;
        int badRoundTrip = 0;
        int deniedButPresent = 0;
        int threw = 0;
        int smaliChecked = 0;
        int smaliWrong = 0;
        List<String> bad = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : probeNames) {
            Map<String, Object> h = core.getTypeHierarchy(p);
            if (!p.equals(h.get("className")) || !Boolean.TRUE.equals(h.get("inApk"))) {
                bad.add(phase + ": " + p + " came back as " + h.get("className")
                        + " inApk=" + h.get("inApk"));
                badRoundTrip++;
            }
            for (String n : emittedNames(h)) {
                if (n == null || n.startsWith("@") || !seen.add(n)) {
                    continue;
                }
                names++;
                List<ClassNode> nodes = (List<ClassNode>) resolveNodes.invoke(core, n);
                if (nodes.size() == 1) {
                    inApkNames++;
                    String back = (String) displayName.invoke(core, nodes.get(0));
                    if (!n.equals(back)) {
                        badRoundTrip++;
                        if (bad.size() < 10) {
                            bad.add(phase + ": " + n + " resolves to " + back);
                        }
                    }
                    // The smali is the arbiter for "the same class".
                    if (smaliChecked < 15) {
                        smaliChecked++;
                        String head = firstHeader(core.getSmaliOfClass(n, 0));
                        if (!headerName(head).equals(n)) {
                            smaliWrong++;
                            if (bad.size() < 10) {
                                bad.add(phase + ": smali of " + n + " is headed " + head);
                            }
                        }
                    }
                } else if (nodes.size() > 1) {
                    badRoundTrip++;
                } else {
                    // Not in the APK by the lookup. The tool must agree, and
                    // must still answer rather than throw.
                    try {
                        Map<String, Object> fh = core.getTypeHierarchy(n);
                        if (Boolean.TRUE.equals(fh.get("inApk"))) {
                            deniedButPresent++;
                        }
                    } catch (RuntimeException e) {
                        threw++;
                        if (bad.size() < 10) {
                            bad.add(phase + ": " + n + " -> " + e.getMessage());
                        }
                    }
                }
            }
        }
        System.out.println("  " + phase + ": " + names + " distinct emitted names, "
                + inApkNames + " in the APK, " + badRoundTrip + " not resolving to themselves, "
                + threw + " rejected outright, " + deniedButPresent + " disagreeing with the lookup, "
                + smaliWrong + "/" + smaliChecked + " smali headers naming another class");
        for (String b : bad) {
            System.out.println("      " + b);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> emittedNames(Map<String, Object> h) {
        List<String> names = new ArrayList<>();
        for (String key : new String[] {"className", "superClass", "outerClass"}) {
            Object v = h.get(key);
            if (v instanceof String) {
                names.add((String) v);
            }
        }
        for (String key : new String[] {"interfaces", "superTypes", "innerClasses", "subClasses"}) {
            Object v = h.get(key);
            if (v instanceof List) {
                names.addAll((List<String>) v);
            }
        }
        return names;
    }

    private static String firstHeader(String smali) {
        if (smali == null) {
            return "";
        }
        int at = smali.indexOf("###### Class ");
        if (at < 0) {
            return "";
        }
        int eol = smali.indexOf('\n', at);
        return smali.substring(at, eol < 0 ? smali.length() : eol);
    }

    /** "###### Class p.C.D (p.C$D)" -> "p.C.D" */
    private static String headerName(String header) {
        String rest = header.replace("###### Class ", "");
        int at = rest.lastIndexOf(" (");
        return at < 0 ? rest : rest.substring(0, at);
    }

    private static void indexTheOldLookup(List<ClassNode> classes) {
        Map<String, Integer> aliasCounts = new HashMap<>(1 << 16);
        for (ClassNode c : classes) {
            aliasCounts.merge(c.getClassInfo().getAliasFullName(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : aliasCounts.entrySet()) {
            if (e.getValue() > 1) {
                ambiguous.add(e.getKey());
            }
        }
        for (ClassNode c : classes) {
            ClassInfo ci = c.getClassInfo();
            byUnique.putIfAbsent(ci.makeAliasRawFullName(), c);
            byAlias.putIfAbsent(ci.getAliasFullName(), c);
            byOrigFull.putIfAbsent(ci.getFullName(), c);
            if (ambiguous.contains(ci.getAliasFullName())) {
                ambiguousGroups.computeIfAbsent(ci.getAliasFullName(),
                        k -> new ArrayList<>()).add(c);
            }
        }
    }

    /** resolveClassNodes as it stood before this round: tiers 1-4, indexed. */
    private static List<ClassNode> oldResolve(String query) {
        ClassNode unique = byUnique.get(query);
        if (unique != null) {
            return Collections.singletonList(unique);
        }
        if (ambiguous.contains(query)) {
            List<ClassNode> g = ambiguousGroups.get(query);
            if (g != null && !g.isEmpty()) {
                return g;
            }
        }
        ClassNode alias = byAlias.get(query);
        if (alias != null) {
            return Collections.singletonList(alias);
        }
        ClassNode orig = byOrigFull.get(query);
        if (orig != null) {
            return Collections.singletonList(orig);
        }
        return Collections.emptyList();
    }
}

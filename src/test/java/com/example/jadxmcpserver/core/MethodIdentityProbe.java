package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY -- reproduces the three findings of the method-identity
 * recheck so each fix has a number to beat. Not run by surefire (no *Test).
 *
 * Two design notes, both learned by getting it wrong first:
 *
 *   - get_methods_of_class DECOMPILES the class it is asked about, so a cold
 *     pass over every class leaves nothing cold. Cold-vs-warm for this tool
 *     cannot be measured on one core at all: the first ask is the warming.
 *     So it is measured across TWO cores of the same APK -- one asked
 *     immediately, one asked after N unrelated classes were decompiled.
 *   - the disagreement runs both ways, so count what the tool MISSES and what
 *     it INVENTS separately; a single signed total hides one inside the other.
 *
 *   java -cp target/classes:target/test-classes:$(cat /tmp/cp.txt) \
 *        com.example.jadxmcpserver.core.MethodIdentityProbe <apk> <warmClasses> <probeClasses>
 */
public class MethodIdentityProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int warmCount = args.length > 1 ? Integer.parseInt(args[1]) : 400;
        int probeCount = args.length > 2 ? Integer.parseInt(args[2]) : 200;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        System.out.println("\n=== MethodIdentityProbe: " + apk + " ===");

        // ---------------- core A: cold ----------------
        JadxAnalyzerCore a = new JadxAnalyzerCore(apk);
        a.loadApk();
        RootNode rootA = rootOf(a);
        List<ClassNode> allA = new ArrayList<>(rootA.getClasses());
        System.out.println("root.getClasses()      : " + allA.size());
        System.out.println("probe classes          : " + probeCount + "   warm target: " + warmCount);

        List<ClassNode> probesA = tail(allA, probeCount);
        List<String> probeNames = new ArrayList<>();
        for (ClassNode c : probesA) {
            probeNames.add(c.getClassInfo().getAliasFullName());
        }

        finding3(rootA, "COLD");
        Map<String, String> aliasesBefore = snapshotAliases(rootA);
        Map<String, List<String>> dexCold = readDex(probesA);
        Map<String, List<String>> toolCold = askTool(a, probeNames);
        report1(rootA, "COLD", dexCold, toolCold);

        // ---------------- core B: warmed before being asked ----------------
        JadxAnalyzerCore b = new JadxAnalyzerCore(apk);
        b.loadApk();
        RootNode rootB = rootOf(b);
        List<ClassNode> allB = new ArrayList<>(rootB.getClasses());
        List<ClassNode> probesB = tail(allB, probeCount);
        int warmed = warm(allB, warmCount, probesB);
        System.out.println("\n>>> core B: decompiled " + warmed
                + " classes, none of them probe classes <<<");

        Map<String, List<String>> dexWarm = readDex(probesB);
        Map<String, List<String>> toolWarm = askTool(b, probeNames);
        report1(rootB, "WARM", dexWarm, toolWarm);

        diff("get_methods_of_class  cold core vs warm core", toolCold, toolWarm);
        diff("ClassNode.getMethods()  cold core vs warm core", dexCold, dexWarm);

        // ---------------- drift and collisions, measured on the warmed core ----------------
        finding2(rootB, aliasesBefore);
        finding3(rootB, "WARM");
    }

    static RootNode rootOf(JadxAnalyzerCore core) throws Exception {
        java.lang.reflect.Field f = JadxAnalyzerCore.class.getDeclaredField("jadx");
        f.setAccessible(true);
        return ((JadxDecompiler) f.get(core)).getRoot();
    }

    static List<ClassNode> tail(List<ClassNode> all, int n) {
        return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    static int warm(List<ClassNode> all, int n, List<ClassNode> exclude) {
        Set<ClassNode> skip = new LinkedHashSet<>(exclude);
        int done = 0;
        for (ClassNode cls : all) {
            if (done >= n) {
                break;
            }
            if (skip.contains(cls)) {
                continue;
            }
            try {
                cls.decompile();
            } catch (Throwable ignored) {
                // a class that will not decompile still counts as warmed
            }
            done++;
        }
        return done;
    }

    static Map<String, List<String>> askTool(JadxAnalyzerCore core, List<String> names) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, core.getMethodsOfClass(n));
        }
        return out;
    }

    static Map<String, List<String>> readDex(List<ClassNode> probes) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ClassNode c : probes) {
            List<String> names = new ArrayList<>();
            for (MethodNode m : c.getMethods()) {
                names.add(m.getMethodInfo().getAliasFullName());
            }
            out.put(c.getClassInfo().getAliasFullName(), names);
        }
        return out;
    }

    // ---------------------------------------------------------------- finding 1

    static void report1(RootNode root, String phase, Map<String, List<String>> dex,
                        Map<String, List<String>> tool) {
        System.out.println("\n--- 1. get_methods_of_class vs the dex (" + phase + ") ---");
        long dexTotal = 0;
        long toolTotal = 0;
        int classesShort = 0;
        int classesOver = 0;
        Map<String, Integer> missingByKind = new TreeMap<>();
        int extraTotal = 0;
        List<String> missingExamples = new ArrayList<>();
        List<String> extraExamples = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : dex.entrySet()) {
            Set<String> d = new LinkedHashSet<>(e.getValue());
            List<String> t = tool.getOrDefault(e.getKey(), List.of());
            dexTotal += d.size();
            toolTotal += t.size();

            Set<String> missing = new LinkedHashSet<>(d);
            missing.removeAll(t);
            Set<String> extra = new LinkedHashSet<>(t);
            extra.removeAll(d);

            if (!missing.isEmpty()) {
                classesShort++;
                for (String m : missing) {
                    String bare = m.substring(m.lastIndexOf('.') + 1);
                    missingByKind.merge(bare.startsWith("<") ? bare : "other", 1, Integer::sum);
                    if (missingExamples.size() < 6) {
                        missingExamples.add(m);
                    }
                }
            }
            if (!extra.isEmpty()) {
                classesOver++;
                extraTotal += extra.size();
                if (extraExamples.size() < 6) {
                    extraExamples.add(e.getKey() + "  ->  " + extra);
                }
            }
        }
        System.out.println("methods per the dex   : " + dexTotal);
        System.out.println("methods per the tool  : " + toolTotal);
        System.out.println("MISSING from the tool : " + missingByKind
                + "  in " + classesShort + " classes");
        for (String e : missingExamples) {
            System.out.println("    missing " + e);
        }
        System.out.println("EXTRA in the tool     : " + extraTotal
                + " in " + classesOver + " classes");
        for (String e : extraExamples) {
            System.out.println("    extra   " + e);
        }
    }

    static void diff(String what, Map<String, List<String>> a, Map<String, List<String>> b) {
        int changed = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : a.entrySet()) {
            List<String> other = b.get(e.getKey());
            if (!e.getValue().equals(other)) {
                changed++;
                if (examples.size() < 8) {
                    Set<String> lost = new LinkedHashSet<>(e.getValue());
                    lost.removeAll(other == null ? List.of() : other);
                    Set<String> gained = new LinkedHashSet<>(other == null ? List.of() : other);
                    gained.removeAll(e.getValue());
                    examples.add(e.getKey() + "\n        lost=" + lost + "\n        gained=" + gained);
                }
            }
        }
        System.out.println("CHANGED [" + what + "] : " + changed + " of " + a.size());
        for (String e : examples) {
            System.out.println("    " + e);
        }
    }

    // ---------------------------------------------------------------- finding 2

    static Map<String, String> snapshotAliases(RootNode root) {
        Map<String, String> out = new HashMap<>();
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                MethodInfo info = mth.getMethodInfo();
                out.put(info.getRawFullId(), info.getAlias());
            }
        }
        return out;
    }

    static void finding2(RootNode root, Map<String, String> before) {
        System.out.println("\n--- 2. alias drift ---");
        System.out.println("methods snapshotted   : " + before.size());

        int changed = 0;
        int appeared = 0;
        List<String> examples = new ArrayList<>();
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                MethodInfo info = mth.getMethodInfo();
                String was = before.get(info.getRawFullId());
                if (was == null) {
                    appeared++;
                } else if (!was.equals(info.getAlias())) {
                    changed++;
                    if (examples.size() < 12) {
                        examples.add(info.getRawFullId() + "\n        " + was
                                + "  ->  " + info.getAlias());
                    }
                }
            }
        }
        System.out.println("ALIASES CHANGED       : " + changed + "  ("
                + String.format("%.4f", 100.0 * changed / Math.max(1, before.size())) + "%)");
        System.out.println("methods that APPEARED : " + appeared);
        for (String e : examples) {
            System.out.println("    " + e);
        }
    }

    // ---------------------------------------------------------------- finding 3

    /**
     * Two kinds of shared name, and they are not the same problem:
     *
     *   OVERLOAD  same raw dex name, different signature -- java source has this
     *             too; a bare name genuinely cannot tell them apart
     *   COLLISION different raw dex names that deobfuscation mapped onto ONE
     *             alias -- two methods a reader would call unrelated, merged
     */
    static void finding3(RootNode root, String phase) {
        System.out.println("\n--- 3. shared method names (" + phase + ") ---");

        int overloadNames = 0;
        int overloadMethods = 0;
        int collisionNames = 0;
        int collisionMethods = 0;
        int collisionClasses = 0;
        List<String> collisionExamples = new ArrayList<>();

        for (ClassNode cls : root.getClasses()) {
            Map<String, List<MethodNode>> byAlias = new LinkedHashMap<>();
            for (MethodNode mth : cls.getMethods()) {
                byAlias.computeIfAbsent(mth.getMethodInfo().getAlias(), k -> new ArrayList<>())
                        .add(mth);
            }
            boolean clsHasCollision = false;
            for (Map.Entry<String, List<MethodNode>> e : byAlias.entrySet()) {
                List<MethodNode> group = e.getValue();
                if (group.size() < 2) {
                    continue;
                }
                Set<String> rawNames = new LinkedHashSet<>();
                for (MethodNode m : group) {
                    rawNames.add(m.getMethodInfo().getName());
                }
                if (rawNames.size() > 1) {
                    clsHasCollision = true;
                    collisionNames++;
                    collisionMethods += group.size();
                    if (collisionExamples.size() < 12) {
                        collisionExamples.add(describe(cls, e.getKey(), group));
                    }
                } else {
                    overloadNames++;
                    overloadMethods += group.size();
                }
            }
            if (clsHasCollision) {
                collisionClasses++;
            }
        }

        System.out.println("OVERLOAD  names=" + overloadNames + " methods=" + overloadMethods);
        System.out.println("COLLISION names=" + collisionNames + " methods=" + collisionMethods
                + " classes=" + collisionClasses);
        for (String e : collisionExamples) {
            System.out.println("  COLLISION " + e);
        }
    }

    static String describe(ClassNode cls, String alias, List<MethodNode> group) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getClassInfo().getAliasFullName()).append('.').append(alias);
        for (MethodNode m : group) {
            sb.append("\n              [").append(m.getMethodInfo().getName())
              .append(m.getMethodInfo().getShortId()).append(']');
        }
        return sb.toString();
    }
}

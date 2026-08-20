package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY, and deliberately hostile to the null result.
 *
 * A 3,000-class warm-up found zero field-alias drift and zero override
 * movement. Before concluding the recheck is wrong, this aims at the two places
 * where drift would actually live rather than at a prefix of the tree:
 *
 *   - ENUM classes. EnumVisitor is the ONLY pass that calls
 *     FieldInfo.setAlias, and it runs during decompilation, so any field-alias
 *     drift that exists is there. Sampling the front of getClasses() can miss
 *     every enum in the APK.
 *   - Methods that DO carry AType.METHOD_OVERRIDE, and the classes that
 *     implement them, rather than the first 150 methods in tree order.
 *
 * Also measures two things the repro probe could not:
 *   - whether a field name get_fields_of_class hands out is accepted verbatim
 *     by get_xrefs_to_field (the round-trip invariant, on the exact string)
 *   - how many classes declare two fields sharing one name, which is the field
 *     analogue of an overload set
 *
 *   java -cp ... FieldAliasAndOverrideProbe &lt;apk&gt; &lt;warmTopLevel&gt;
 */
public class FieldAliasAndOverrideProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int warmCount = args.length > 1 ? Integer.parseInt(args[1]) : 20000;

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
        System.out.println("\n============ " + apk + " ============");
        System.out.println("classes: " + all.size());

        // ---- enum classes, which is where field renaming lives ----
        List<ClassNode> enums = new ArrayList<>();
        for (ClassNode c : all) {
            if (c.getAccessFlags().isEnum()) {
                enums.add(c);
            }
        }
        System.out.println("enum classes: " + enums.size());

        // ---- round trip on the exact string get_fields_of_class emits ----
        verbatimRoundTrip(core, all);
        duplicateFieldNames(all);

        // ---- pin field aliases ----
        Map<FieldNode, String> pin = new IdentityHashMap<>();
        for (ClassNode c : all) {
            for (FieldNode fl : c.getFields()) {
                pin.put(fl, fl.getFieldInfo().getAlias());
            }
        }

        // ---- override answers for methods that actually have the attribute ----
        List<String[]> overrideTargets = new ArrayList<>();
        for (ClassNode c : all) {
            for (MethodNode m : c.getMethods()) {
                if (m.get(AType.METHOD_OVERRIDE) != null) {
                    overrideTargets.add(new String[] {
                            c.getClassInfo().getAliasFullName(),
                            m.getMethodInfo().getAlias() });
                }
                if (overrideTargets.size() >= 200) {
                    break;
                }
            }
            if (overrideTargets.size() >= 200) {
                break;
            }
        }
        System.out.println("methods carrying METHOD_OVERRIDE cold: " + overrideTargets.size());
        Map<String, String> coldOv = overrides(core, overrideTargets);

        // A second sample that does NOT have the attribute cold -- the direction
        // the report says flips (cold claims a method overrides nothing).
        List<String[]> blankTargets = new ArrayList<>();
        for (ClassNode c : enums.isEmpty() ? all : enums) {
            for (MethodNode m : c.getMethods()) {
                if (m.get(AType.METHOD_OVERRIDE) == null
                        && !m.getMethodInfo().isConstructor()) {
                    blankTargets.add(new String[] {
                            c.getClassInfo().getAliasFullName(),
                            m.getMethodInfo().getAlias() });
                }
                if (blankTargets.size() >= 200) {
                    break;
                }
            }
            if (blankTargets.size() >= 200) {
                break;
            }
        }
        Map<String, String> coldBlank = overrides(core, blankTargets);

        // ---- warm: every enum first, then tree order ----
        int warmed = 0;
        for (ClassNode c : enums) {
            ClassNode top = c.getTopParentClass();
            try {
                top.decompile();
            } catch (Throwable ignored) {
                // counts as warmed
            }
            warmed++;
        }
        System.out.println(">>> decompiled " + warmed + " enum top-parents <<<");
        int extra = 0;
        for (ClassNode c : all) {
            if (extra >= warmCount) {
                break;
            }
            if (!c.isInner()) {
                try {
                    c.decompile();
                } catch (Throwable ignored) {
                    // counts as warmed
                }
                extra++;
            }
        }
        System.out.println(">>> plus " + extra + " top-level classes <<<");

        // ---- drift ----
        int drift = 0;
        List<String> examples = new ArrayList<>();
        for (ClassNode c : all) {
            for (FieldNode fl : c.getFields()) {
                String p = pin.get(fl);
                String live = fl.getFieldInfo().getAlias();
                if (p != null && !p.equals(live)) {
                    drift++;
                    if (examples.size() < 12) {
                        examples.add(c.getClassInfo().getAliasFullName()
                                + ": " + p + "  ->  " + live
                                + (c.getAccessFlags().isEnum() ? "   [enum]" : ""));
                    }
                }
            }
        }
        System.out.println("\n--- FIELD ALIAS DRIFT ---");
        System.out.println("drifted: " + drift + " / " + pin.size());
        for (String e : examples) {
            System.out.println("    " + e);
        }

        // ---- override movement ----
        report("OVERRIDES, methods WITH the attribute cold", coldOv,
                overrides(core, overrideTargets));
        report("OVERRIDES, methods WITHOUT the attribute cold", coldBlank,
                overrides(core, blankTargets));

        // ---- round trip again, now warm ----
        verbatimRoundTrip(core, all);
    }

    private static void report(String title, Map<String, String> cold,
                               Map<String, String> warm) {
        int changed = 0;
        List<String> ex = new ArrayList<>();
        for (Map.Entry<String, String> e : cold.entrySet()) {
            String w = warm.get(e.getKey());
            if (!String.valueOf(e.getValue()).equals(String.valueOf(w))) {
                changed++;
                if (ex.size() < 5) {
                    ex.add(e.getKey() + "\n        cold=" + trim(e.getValue())
                            + "\n        warm=" + trim(w));
                }
            }
        }
        System.out.println("\n--- " + title + " ---");
        System.out.println("changed: " + changed + " / " + cold.size());
        for (String s : ex) {
            System.out.println("    " + s);
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static Map<String, String> overrides(JadxAnalyzerCore core, List<String[]> targets) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] t : targets) {
            try {
                Map<String, Object> r = core.getOverrides(t[0], t[1], null, 0);
                out.put(t[0] + "#" + t[1], "attr=" + r.get("attributePresent")
                        + " base=" + r.get("baseMethods")
                        + " by=" + r.get("overriddenBy"));
            } catch (Throwable e) {
                out.put(t[0] + "#" + t[1], "ERR:" + e.getMessage());
            }
        }
        return out;
    }

    /** Feed get_fields_of_class output straight back into get_xrefs_to_field. */
    private static void verbatimRoundTrip(JadxAnalyzerCore core, List<ClassNode> all) {
        int checked = 0;
        int verbatimOk = 0;
        int strippedOk = 0;
        List<String> ex = new ArrayList<>();
        for (ClassNode c : all) {
            if (c.getFields().isEmpty()) {
                continue;
            }
            String cn = c.getClassInfo().getAliasFullName();
            List<String> fields;
            try {
                fields = core.getFieldsOfClass(cn, 5);
            } catch (Throwable t) {
                continue;
            }
            for (String entry : fields) {
                if (entry.startsWith("@")) {
                    continue;
                }
                checked++;
                try {
                    core.getXrefsToField(cn, entry, 1);
                    verbatimOk++;
                } catch (Throwable t) {
                    if (ex.size() < 5) {
                        ex.add(cn + " | \"" + entry + "\" -> " + t.getMessage());
                    }
                }
                int sp = entry.lastIndexOf(' ');
                String bare = sp < 0 ? entry : entry.substring(sp + 1);
                try {
                    core.getXrefsToField(cn, bare, 1);
                    strippedOk++;
                } catch (Throwable ignored) {
                    // counted by omission
                }
            }
            if (checked >= 500) {
                break;
            }
        }
        System.out.println("\n--- get_fields_of_class -> get_xrefs_to_field ROUND TRIP ---");
        System.out.println("entries checked: " + checked
                + "   accepted VERBATIM (\"type name\"): " + verbatimOk
                + "   accepted with the type stripped: " + strippedOk);
        for (String s : ex) {
            System.out.println("    " + s);
        }
    }

    /** Two fields sharing one name is the field analogue of an overload set. */
    private static void duplicateFieldNames(List<ClassNode> all) {
        int classesWithDup = 0;
        int dupNames = 0;
        List<String> ex = new ArrayList<>();
        for (ClassNode c : all) {
            Map<String, Integer> byName = new LinkedHashMap<>();
            for (FieldNode fl : c.getFields()) {
                byName.merge(fl.getFieldInfo().getAlias(), 1, Integer::sum);
            }
            boolean any = false;
            for (Map.Entry<String, Integer> e : byName.entrySet()) {
                if (e.getValue() > 1) {
                    dupNames++;
                    any = true;
                    if (ex.size() < 6) {
                        ex.add(c.getClassInfo().getAliasFullName() + "." + e.getKey()
                                + " x" + e.getValue());
                    }
                }
            }
            if (any) {
                classesWithDup++;
            }
        }
        System.out.println("\n--- DUPLICATE FIELD NAMES IN ONE CLASS ---");
        System.out.println("classes with a duplicated field name: " + classesWithDup
                + "   distinct duplicated names: " + dupNames);
        for (String s : ex) {
            System.out.println("    " + s);
        }
    }
}

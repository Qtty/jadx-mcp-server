package com.example.jadxmcpserver.core;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. Does the SERVER now answer about the right class?
 *
 * {@link ClassCollisionProbe} measures jadx's own lookup, which still reports
 * 27 unreachable classes on a large production APK because that is a fact about
 * {@code searchJavaClassByAliasFullName}. This one goes through
 * JadxAnalyzerCore instead and checks the three things the fix owes:
 *
 *   1. every colliding class is REACHABLE under some spelling
 *   2. each spelling resolves to the class it names, not to its twin
 *   3. the shared spelling is never handed out by get_all_classes
 */
public class ClassCollisionServerProbe {

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

        Map<String, List<ClassNode>> byAlias = new TreeMap<>();
        for (ClassNode c : jadx.getRoot().getClasses()) {
            byAlias.computeIfAbsent(c.getClassInfo().getAliasFullName(),
                    k -> new ArrayList<>()).add(c);
        }

        List<String> listed = new ArrayList<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (!n.startsWith("@")) {
                listed.add(n);
            }
        }
        System.out.println("\n=== " + apk + " ===");
        System.out.println("classes listed by the server: " + listed.size());

        int groups = 0;
        int reachable = 0;
        int correct = 0;
        int sharedStillListed = 0;
        int shown = 0;
        for (Map.Entry<String, List<ClassNode>> e : byAlias.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            groups++;
            if (listed.contains(e.getKey())) {
                sharedStillListed++;
                System.out.println("  !! shared spelling still handed out: " + e.getKey());
            }
            for (ClassNode c : e.getValue()) {
                String spelling = c.getClassInfo().makeAliasRawFullName();
                boolean isListed = listed.contains(spelling);
                if (isListed) {
                    reachable++;
                }
                // Resolve it back and check we got THIS class, by comparing the
                // member counts the two twins differ on.
                Map<String, Object> details = core.getClassDetails(spelling);
                boolean right = details != null
                        && Integer.valueOf(c.getMethods().size()).equals(details.get("methodCount"))
                        && Integer.valueOf(c.getFields().size()).equals(details.get("fieldCount"));
                if (right) {
                    correct++;
                }
                if (shown < 8) {
                    System.out.println("  " + spelling
                            + "  listed=" + isListed
                            + "  resolvesToItself=" + right
                            + "  (dex says " + c.getMethods().size() + " methods, "
                            + c.getFields().size() + " fields; server says "
                            + (details == null ? "NOT FOUND"
                                    : details.get("methodCount") + " / " + details.get("fieldCount"))
                            + ")");
                    shown++;
                }
            }
        }
        System.out.println("\ncollision groups                 : " + groups);
        System.out.println("colliding classes listed by name : " + reachable
                + " / " + (groups * 2));
        System.out.println("spellings resolving to themselves: " + correct
                + " / " + (groups * 2));
        System.out.println("shared spellings still handed out: " + sharedStillListed);
    }
}

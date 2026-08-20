package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Names, not counts: what does get_callees stop reporting once the class is
 * decompiled? Run by hand.
 *
 * Usage: exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.CalleeEdgeDiffProbe
 *        -Dexec.args="<apk> <class> <method>"
 */
public final class CalleeEdgeDiffProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        String cls = args.length > 1 ? args[1] : "jakhar.aseem.diva.LogActivity";
        String mth = args.length > 2 ? args[2] : "checkout";

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        List<String> cold = flatten(core.getCallees(cls, mth));
        System.out.println("=== COLD " + cls + "#" + mth + " ===");
        cold.forEach(s -> System.out.println("  " + s));

        core.getClassSource(cls);

        List<String> warm = flatten(core.getCallees(cls, mth));
        System.out.println("=== WARM ===");
        warm.forEach(s -> System.out.println("  " + s));

        System.out.println("=== LOST ===");
        cold.stream().filter(s -> !warm.contains(s)).forEach(s -> System.out.println("  " + s));
        System.out.println("=== APPEARED ===");
        warm.stream().filter(s -> !cold.contains(s)).forEach(s -> System.out.println("  " + s));
    }

    @SuppressWarnings("unchecked")
    private static List<String> flatten(Map<String, Object> callees) {
        List<String> out = new ArrayList<>();
        for (String key : List.of("callees", "external")) {
            for (Map<String, String> e : (List<Map<String, String>>) callees.get(key)) {
                out.add(key + "  " + e.get("className") + "#" + e.get("signature"));
            }
        }
        out.add("callsSelf  " + callees.get("callsSelf"));
        return out;
    }
}

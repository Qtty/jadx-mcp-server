package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. Does DexReverseIndex answer differently depending on
 * WHEN it was built?
 *
 * The index is built once, lazily, and cached, so asking twice against one core
 * can never show this -- it needs two cores that build the index at different
 * points. That is exactly the difference a daemon sees: whichever request
 * happens to be first pays for the index, and every later answer inherits
 * whatever tree it was built from.
 *
 * This became visible only after get_fields_of_class stopped decompiling. It
 * used to warm ~3,000 classes as a side effect of being asked, so the index was
 * never actually built cold.
 *
 *   java -cp ... IndexBuildTimeProbe &lt;apk&gt; [maxClasses]
 */
public class IndexBuildTimeProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        int maxClasses = args.length > 1 ? Integer.parseInt(args[1]) : 100000;

        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        Map<String, String> cold = sample(apk, false, maxClasses);
        Map<String, String> warm = sample(apk, true, maxClasses);

        int changed = 0;
        int coldZeroWarmMore = 0;
        int coldMoreWarmZero = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String> e : cold.entrySet()) {
            String w = warm.get(e.getKey());
            if (w == null || w.equals(e.getValue())) {
                continue;
            }
            changed++;
            if ("0".equals(e.getValue())) {
                coldZeroWarmMore++;
            } else if ("0".equals(w)) {
                coldMoreWarmZero++;
            }
            if (examples.size() < 12) {
                examples.add(e.getKey() + "  cold=" + e.getValue() + "  warm=" + w);
            }
        }

        System.out.println("\n=== " + apk + " ===");
        System.out.println("fields compared          : " + cold.size());
        System.out.println("xref counts that changed : " + changed);
        System.out.println("  cold 0 -> warm >0      : " + coldZeroWarmMore);
        System.out.println("  cold >0 -> warm 0      : " + coldMoreWarmZero);
        for (String s : examples) {
            System.out.println("    " + s);
        }
    }

    /**
     * @param warmFirst decompile everything BEFORE the first index-building
     *                  query, which is what makes the index a warm one
     */
    private static Map<String, String> sample(String apk, boolean warmFirst, int maxClasses) {
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        List<String> classes = new ArrayList<>();
        for (String n : core.getAllClasses(null, 0)) {
            if (!n.startsWith("@")) {
                classes.add(n);
            }
            if (classes.size() >= maxClasses) {
                break;
            }
        }

        if (warmFirst) {
            for (String cn : classes) {
                try {
                    core.getClassSource(cn);
                } catch (Throwable ignored) {
                    // still counts as warmed
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        int budget = 4000;
        for (String cn : classes) {
            for (String entry : core.getFieldsOfClass(cn)) {
                if (entry.startsWith("@") || budget-- <= 0) {
                    continue;
                }
                try {
                    out.put(cn + "#" + entry,
                            String.valueOf(core.getXrefsToField(cn, entry, 0).size()));
                } catch (Throwable t) {
                    out.put(cn + "#" + entry, "ERR");
                }
            }
            if (budget <= 0) {
                break;
            }
        }
        System.out.println((warmFirst ? "warm" : "cold") + " sample: " + out.size() + " fields");
        return out;
    }
}

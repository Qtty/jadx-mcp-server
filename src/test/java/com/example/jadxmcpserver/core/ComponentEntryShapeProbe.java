package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MEASUREMENT ONLY. Do the two components tools emit one entry shape?
 *
 * Three census questions, all of them about what a caller can read
 * unconditionally:
 *
 *   1. get_deeplink_components -> intent_filters[].data[]. How many distinct key
 *      sets are there, and is any key present on every entry? This is the same
 *      question that was asked of the xref family; the answer there was "no",
 *      and the fix was to give every entry every key.
 *   2. the component object itself. permission and alias_of used to be written
 *      only when they had a value, so the top level was ragged for the same
 *      reason the nested entries were.
 *   3. get_exported_components against get_deeplink_components. They describe
 *      overlapping populations; this counts the overlap and diffs the key sets,
 *      so "two shapes for one concept" is a number rather than an impression.
 *
 * WHAT IT MEASURED, 2026-08-08, on a large production APK, before the fix and after:
 *
 * <pre>
 *   before   75 data entries in 9 key sets, no key on all of them
 *            scheme 60/75, host 46, autoVerify 39, pathPrefix 14, path 6,
 *            port 0, pathPattern 0, pathSuffix 0, mimeType 0
 *   after    75 data entries in 1 key set, all nine keys on every entry
 *
 *   component objects   2 key sets both before and after (permission on 7 of
 *                       47, alias_of on none) -- deliberate, see parseAllComponents
 *   exported vs deeplink  45 named by both, 0 exported-only, 2 deeplink-only,
 *                       and the entry keys went from disjoint to identical
 * </pre>
 *
 * DivaApplication.apk declares no {@code <data>} element at all, which is why
 * the shape claim has to be measured on the big APK.
 *
 * Run: java -cp target/test-classes:target/classes:$(cat /tmp/jadx-cp.txt) \
 *          com.example.jadxmcpserver.core.ComponentEntryShapeProbe [apk...]
 */
public class ComponentEntryShapeProbe {

    public static void main(String[] args) {
        String[] apks = args.length > 0 ? args : new String[] {
                "misc/DivaApplication.apk",
                "misc/DivaApplication.apk",
        };
        for (String apk : apks) {
            if (!new File(apk).exists()) {
                System.out.println("SKIP: no such apk: " + apk);
                continue;
            }
            JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
            core.loadApk();
            try {
                report(apk, core);
            } finally {
                core.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void report(String apk, JadxAnalyzerCore core) {
        List<Map<String, Object>> deeplink = core.getDeeplinkComponents();
        List<Map<String, Object>> exported = core.getExportedComponentsAsMap();

        System.out.println("=== " + apk);
        System.out.println("deeplink components: " + deeplink.size()
                + "   exported components: " + exported.size());

        // 1. the nested data entries
        Map<String, Integer> dataShapes = new TreeMap<>();
        Set<String> everKey = new LinkedHashSet<>();
        Map<String, Integer> keyCount = new TreeMap<>();
        int dataEntries = 0;
        for (Map<String, Object> c : deeplink) {
            Object filters = c.get("intent_filters");
            if (!(filters instanceof List)) {
                continue;
            }
            for (Map<String, Object> f : (List<Map<String, Object>>) filters) {
                for (Map<String, Object> d : (List<Map<String, Object>>) f.get("data")) {
                    dataEntries++;
                    dataShapes.merge(String.join(",", d.keySet()), 1, Integer::sum);
                    everKey.addAll(d.keySet());
                    for (String k : d.keySet()) {
                        keyCount.merge(k, 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("intent_filters[].data[] entries: " + dataEntries
                + " in " + dataShapes.size() + " distinct key sets");
        List<String> universal = new ArrayList<>();
        for (String k : everKey) {
            if (keyCount.getOrDefault(k, 0) == dataEntries) {
                universal.add(k);
            }
        }
        System.out.println("  keys present on EVERY data entry: "
                + (universal.isEmpty() ? "(none)" : String.join(",", universal)));
        System.out.println("  per-key presence: " + keyCount);
        for (Map.Entry<String, Integer> e : dataShapes.entrySet()) {
            System.out.println("  " + e.getValue() + "x  {" + e.getKey() + "}");
        }

        // 2. the component object
        Map<String, Integer> compKeys = new TreeMap<>();
        Map<String, Integer> compShapes = new TreeMap<>();
        for (Map<String, Object> c : deeplink) {
            compShapes.merge(String.join(",", c.keySet()), 1, Integer::sum);
            for (String k : c.keySet()) {
                compKeys.merge(k, 1, Integer::sum);
            }
        }
        System.out.println("component objects: " + compShapes.size() + " distinct key sets");
        for (Map.Entry<String, Integer> e : compShapes.entrySet()) {
            System.out.println("  " + e.getValue() + "x  {" + e.getKey() + "}");
        }
        System.out.println("  per-key presence (of " + deeplink.size() + "): " + compKeys);

        // 3. the two tools against each other
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> c : deeplink) {
            byName.put(String.valueOf(c.get("name")), c);
        }
        int both = 0;
        List<String> exportedOnly = new ArrayList<>();
        for (Map<String, Object> e : exported) {
            if (byName.containsKey(String.valueOf(e.get("name")))) {
                both++;
            } else {
                exportedOnly.add(String.valueOf(e.get("name")));
            }
        }
        Set<String> exportedNames = new LinkedHashSet<>();
        for (Map<String, Object> e : exported) {
            exportedNames.add(String.valueOf(e.get("name")));
        }
        List<String> deeplinkOnly = new ArrayList<>();
        for (String n : byName.keySet()) {
            if (!exportedNames.contains(n)) {
                deeplinkOnly.add(n);
            }
        }
        System.out.println("populations: " + both + " named by both, "
                + exportedOnly.size() + " exported-only, "
                + deeplinkOnly.size() + " deeplink-only");
        if (!exported.isEmpty()) {
            System.out.println("  exported entry keys: "
                    + new TreeMap<>(exported.get(0)).keySet());
        }
        if (!deeplink.isEmpty()) {
            System.out.println("  deeplink entry keys: "
                    + new TreeMap<>(deeplink.get(0)).keySet());
        }
        System.out.println("  exported-only names: " + head(exportedOnly));
        System.out.println("  deeplink-only names: " + head(deeplinkOnly));
        System.out.println();
    }

    private static String head(List<String> names) {
        if (names.size() <= 6) {
            return String.valueOf(names);
        }
        return names.subList(0, 6) + " ... +" + (names.size() - 6);
    }
}

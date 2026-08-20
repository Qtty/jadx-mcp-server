package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. Where do the two components tools disagree about the SAME
 * component, and what does each one's entry actually say?
 *
 * ComponentEntryShapeProbe counts the populations; this one prints the
 * disagreements, because "two shapes for one concept" is only worth acting on
 * if the two shapes also carry different facts. Checked per name: the exported
 * verdict, the type, and the permission.
 *
 * WHAT IT MEASURED, 2026-08-08, on a large production APK: two disagreements, both the same
 * kind and both in the same direction --
 * com.example.webview.DrawerStyleWebActivity and
 * com.example.push.DispatcherActivity carry a BROWSABLE filter and
 * declare android:exported="false", so they are link-shaped and not exported.
 * Nothing was exported-only. On DivaApplication.apk, zero disagreements.
 *
 * That is the evidence for "neither list contains the other": the populations
 * genuinely differ, so converging the two tools had to converge the SHAPE and
 * leave the keep-rules alone.
 *
 * Run: java -cp target/test-classes:target/classes:$(cat /tmp/jadx-cp.txt) \
 *          com.example.jadxmcpserver.core.ComponentDivergenceProbe [apk...]
 */
public class ComponentDivergenceProbe {

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

    private static void report(String apk, JadxAnalyzerCore core) {
        Map<String, Map<String, Object>> deeplink = new LinkedHashMap<>();
        for (Map<String, Object> c : core.getDeeplinkComponents()) {
            deeplink.put(String.valueOf(c.get("name")), c);
        }
        Map<String, Map<String, Object>> exported = new LinkedHashMap<>();
        for (Map<String, Object> c : core.getExportedComponentsAsMap()) {
            exported.put(String.valueOf(c.get("name")), c);
        }

        System.out.println("=== " + apk);
        List<String> disagree = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : deeplink.entrySet()) {
            Map<String, Object> other = exported.get(e.getKey());
            if (other == null) {
                disagree.add(e.getKey() + "\n    deeplink: exported=" + e.getValue().get("exported")
                        + " reason=" + e.getValue().get("exported_reason")
                        + " is_main=" + e.getValue().get("is_main")
                        + " is_browsable=" + e.getValue().get("is_browsable")
                        + " type=" + e.getValue().get("type")
                        + "\n    exported: ABSENT");
                continue;
            }
            List<String> diffs = new ArrayList<>();
            if (!String.valueOf(e.getValue().get("exported")).equals(String.valueOf(other.get("exported")))) {
                diffs.add("exported " + e.getValue().get("exported") + " vs " + other.get("exported"));
            }
            if (!String.valueOf(e.getValue().get("type")).equals(String.valueOf(other.get("type")))) {
                diffs.add("type " + e.getValue().get("type") + " vs " + other.get("type"));
            }
            String p1 = e.getValue().get("permission") == null ? "" : String.valueOf(e.getValue().get("permission"));
            String p2 = other.get("permission") == null ? "" : String.valueOf(other.get("permission"));
            if (!p1.equals(p2)) {
                diffs.add("permission [" + p1 + "] vs [" + p2 + "]");
            }
            if (!diffs.isEmpty()) {
                disagree.add(e.getKey() + "\n    " + String.join("\n    ", diffs));
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : exported.entrySet()) {
            if (!deeplink.containsKey(e.getKey())) {
                disagree.add(e.getKey() + "\n    deeplink: ABSENT\n    exported: " + e.getValue());
            }
        }
        System.out.println("disagreements: " + disagree.size());
        for (String d : disagree) {
            System.out.println("  " + d);
        }

        // What the two say about the same component, side by side, once.
        String sample = deeplink.keySet().stream().filter(exported::containsKey)
                .findFirst().orElse(null);
        if (sample != null) {
            System.out.println("side by side for " + sample);
            System.out.println("  deeplink: " + deeplink.get(sample));
            System.out.println("  exported: " + exported.get(sample));
        }
        System.out.println();
    }
}

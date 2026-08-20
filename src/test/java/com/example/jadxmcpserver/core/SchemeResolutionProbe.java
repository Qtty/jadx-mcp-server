package com.example.jadxmcpserver.core;

import java.util.List;
import java.util.Map;

/**
 * Is the "no scheme starts with @" assertion actually exercising resolution, or
 * passing because the referenced component never reaches the inventory?
 */
public final class SchemeResolutionProbe {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        try {
            String manifest = core.getAndroidManifest();
            System.out.println("manifest declares @string/ scheme: "
                    + manifest.contains("android:scheme=\"@string/"));
            int idx = manifest.indexOf("android:scheme=\"@string/");
            if (idx >= 0) {
                System.out.println("  raw: " + manifest.substring(idx, Math.min(idx + 60, manifest.length())));
            }

            List<Map<String, Object>> components = core.getDeeplinkComponents();
            System.out.println("components in inventory: " + components.size());

            int schemes = 0;
            int unresolved = 0;
            for (Map<String, Object> c : components) {
                for (Map<String, Object> f : (List<Map<String, Object>>) c.get("intent_filters")) {
                    for (Object d : (List<?>) f.get("data")) {
                        Object s = ((Map<?, ?>) d).get("scheme");
                        if (s == null) {
                            continue;
                        }
                        schemes++;
                        if (String.valueOf(s).startsWith("@")) {
                            unresolved++;
                            System.out.println("  UNRESOLVED on " + c.get("name") + ": " + s);
                        }
                        if (String.valueOf(c.get("name")).toLowerCase().contains("facebook")) {
                            System.out.println("  facebook component " + c.get("name") + " scheme=" + s);
                        }
                    }
                }
            }
            System.out.println("total scheme values: " + schemes + ", still unresolved: " + unresolved);
        } finally {
            core.close();
        }
    }
}

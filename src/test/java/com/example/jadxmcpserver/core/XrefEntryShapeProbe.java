package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. How often does a get_xrefs_to_class entry have no method
 * context, and does every entry still carry all three keys?
 *
 * A class-level reference -- a superclass or interface bound, which no method
 * encloses -- is reported as methodName "". Until 2026-08-08 it was reported
 * with no "signature" KEY either, so a caller reading entry["signature"] on
 * every entry, which is what the reference doc tells them to do, got a KeyError
 * rather than a value. The key is now always present and empty; this probe is
 * what says so, and what carries the frequency the doc quotes.
 *
 * Sample size is the thing to be careful with here, because reference counts
 * are heavy-tailed: over 501 classes the class-level share of entries reads
 * 5.5%, over 1,004 it reads 11.4%, and neither is wrong -- one widely-referenced
 * utility landing in the sample moves the denominator by more than the rest of
 * it combined. Pass "all" for the population figure rather than a sample of it.
 *
 * Run: java -cp target/test-classes:target/classes:$(deps) \
 *          com.example.jadxmcpserver.core.XrefEntryShapeProbe [apk] [all|N]
 */
public class XrefEntryShapeProbe {

    public static void main(String[] args) {
        String apk = args.length > 0 ? args[0]
                : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();

        List<String> all = core.getAllClasses(null, 0);
        int want = args.length > 1
                ? ("all".equals(args[1]) ? all.size() : Integer.parseInt(args[1]))
                : 500;
        int step = Math.max(1, all.size() / Math.max(1, want));
        long entries = 0;
        long noMethod = 0;
        long noSignature = 0;
        int classesAsked = 0;
        int classesWithAny = 0;
        for (int i = 0; i < all.size(); i += step) {
            String name = all.get(i);
            if (name.startsWith("@")) {
                continue;
            }
            List<Map<String, String>> xrefs;
            try {
                xrefs = core.getXrefsToClass(name, 0);
            } catch (RuntimeException e) {
                continue;
            }
            classesAsked++;
            boolean any = false;
            for (Map<String, String> x : xrefs) {
                if (x.get("className") == null) {
                    continue;   // bookkeeping entry
                }
                entries++;
                if ("".equals(x.get("methodName"))) {
                    noMethod++;
                    any = true;
                }
                if (!x.containsKey("signature") || !x.containsKey("methodName")) {
                    noSignature++;
                }
            }
            if (any) {
                classesWithAny++;
            }
        }
        System.out.println("classes asked: " + classesAsked);
        System.out.println("entries: " + entries);
        System.out.println("entries with methodName \"\": " + noMethod
                + " (" + pct(noMethod, entries) + "%)");
        System.out.println("entries missing a key: " + noSignature
                + " (" + pct(noSignature, entries) + "%)");
        System.out.println("classes with at least one: " + classesWithAny
                + " of " + classesAsked);
        core.close();
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "0" : String.format("%.1f", 100.0 * part / whole);
    }
}

package com.example.jadxmcpserver.core;

import jadx.core.clsp.ClspGraph;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/**
 * The measurement behind findSinkCallSites' matching rule. Not a test -- a
 * main() run by hand, same spirit as CalleeCostProbe.
 *
 * The question a sink sweep has to answer first is what "a call to
 * android.webkit.WebView.loadUrl" even means, because getUnresolvedUsed() gives
 * the receiver's *static type at the call*, not the class that declares the
 * method. Three rules were possible and this prints all three side by side:
 *
 *   exact     -- declaring class equals the sink's class
 *   subtype   -- declaring class is the sink's class or inherits from it
 *   name-only -- method name matches, receiver ignored
 *
 * On a large production APK (141461 classes, 721397 unresolved edges) they disagree enough
 * to decide it:
 *
 *   WebView.loadUrl            exact 85    subtype 91    name-only 91
 *   Context.startActivity      exact 553   subtype 696   name-only 701
 *   FileOutputStream.write     exact 149   subtype 149   name-only 1428
 *
 * Exact loses six loadUrl call sites, all of them obfuscated in-APK WebView
 * subclasses (com.facebook.ads.redexgen.X.SX and friends) -- precisely the
 * surface a WebView analysis exists to find. Name-only picks those up but drags in
 * 1279 writes to things that are not files. Subtype is the only rule that is
 * neither, and ClspGraph.isImplements answers it from a map load() already
 * built, so it costs nothing measurable.
 *
 * Usage: mvn exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.SinkSweepProbe
 *        -Dexec.args="<apk>"
 */
public final class SinkSweepProbe {

    static final String[] SINKS = {
            "android.webkit.WebView.loadUrl",
            "android.webkit.WebView.addJavascriptInterface",
            "android.content.Context.startActivity",
            "java.io.FileOutputStream.write",
            "java.lang.Runtime.exec",
            "android.content.Intent.parseUri",
    };

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        long t0 = System.nanoTime();
        core.loadApk();
        System.out.printf("load: %.1f s%n", (System.nanoTime() - t0) / 1e9);

        Field field = JadxAnalyzerCore.class.getDeclaredField("jadx");
        field.setAccessible(true);
        jadx.api.JadxDecompiler decompiler = (jadx.api.JadxDecompiler) field.get(core);
        List<ClassNode> classes = decompiler.getRoot().getClasses();
        ClspGraph clsp = decompiler.getRoot().getClsp();
        System.out.println("classes: " + classes.size());

        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Set<String>> subtypeOnly = new LinkedHashMap<>();
        for (String s : SINKS) {
            counts.put(s, new int[3]);
            subtypeOnly.put(s, new TreeSet<>());
        }

        long edges = 0;
        long start = System.nanoTime();
        for (ClassNode cls : classes) {
            for (MethodNode mth : cls.getMethods()) {
                for (MethodInfo callee : mth.getUnresolvedUsed()) {
                    edges++;
                    for (String sink : SINKS) {
                        int dot = sink.lastIndexOf('.');
                        if (!callee.getName().equals(sink.substring(dot + 1))) {
                            continue;
                        }
                        String want = sink.substring(0, dot);
                        String receiver = callee.getDeclClass().getFullName();
                        int[] c = counts.get(sink);
                        c[2]++;
                        if (receiver.equals(want)) {
                            c[0]++;
                            c[1]++;
                        } else if (clsp.isImplements(receiver, want)) {
                            c[1]++;
                            subtypeOnly.get(sink).add(receiver);
                        }
                    }
                }
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("%nswept %d unresolved edges in %d ms%n%n", edges, ms);
        System.out.printf("%-48s %8s %8s %10s%n", "sink", "exact", "subtype", "name-only");
        counts.forEach((sink, c) ->
                System.out.printf("%-48s %8d %8d %10d%n", sink, c[0], c[1], c[2]));

        System.out.println("\nreceivers only the subtype rule finds:");
        subtypeOnly.forEach((sink, recv) -> {
            if (!recv.isEmpty()) {
                System.out.printf("  %-45s <- %s%n", sink, recv);
            }
        });

        core.close();
    }
}

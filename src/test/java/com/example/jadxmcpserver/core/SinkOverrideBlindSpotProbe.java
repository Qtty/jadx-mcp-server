package com.example.jadxmcpserver.core;

import jadx.api.plugins.input.data.ICodeReader;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/**
 * How much of a sink's call surface does findSinkCallSites NOT see?
 *
 * Not a test -- a main() run by hand, same spirit as SinkSweepProbe.
 *
 * SinkSweepProbe settled the matching rule. This one measures the boundary of
 * the data source. getUnresolvedUsed() holds calls that leave the dex, so an
 * in-APK subclass that OVERRIDES the sink captures every call made through its
 * own static type into the resolved half, where the sweep never looks.
 *
 * Three passes, over the same APK:
 *   visible   -- exactly what findSinkCallSites does today
 *   overrides -- in-APK methods that override the sink, and their getUseIn()
 *   truth     -- every invoke instruction in the dex naming the sink on a
 *                subtype receiver, resolved or not, read from bytecode
 *
 * Usage: mvn exec:java -Dexec.classpathScope=test
 *        -Dexec.mainClass=com.example.jadxmcpserver.core.SinkOverrideBlindSpotProbe
 *        -Dexec.args="&lt;apk&gt; [Class.method ...]"
 */
public final class SinkOverrideBlindSpotProbe {

    static final String[] DEFAULT_SINKS = {
            "android.webkit.WebView.loadUrl",
            "android.webkit.WebView.addJavascriptInterface",
            "android.webkit.WebView.loadDataWithBaseURL",
            "android.content.Context.startActivity",
            "android.app.Activity.startActivityForResult",
            "android.content.Intent.parseUri",
            "java.io.FileOutputStream.write",
    };

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        String[] sinks = args.length > 1
                ? Arrays.copyOfRange(args, 1, args.length) : DEFAULT_SINKS;
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
        RootNode root = decompiler.getRoot();
        List<ClassNode> classes = root.getClasses();
        ClspGraph clsp = root.getClsp();
        System.out.println("classes: " + classes.size());

        for (String sink : sinks) {
            int dot = sink.lastIndexOf('.');
            String declClass = sink.substring(0, dot);
            String name = sink.substring(dot + 1);
            System.out.println("\n================ " + sink);

            // --- pass 1: what the sweep sees today -----------------------
            long t = System.nanoTime();
            Set<String> visible = new TreeSet<>();
            long unresolvedEdges = 0;
            for (ClassNode cls : classes) {
                for (MethodNode mth : cls.getMethods()) {
                    for (MethodInfo callee : mth.getUnresolvedUsed()) {
                        if (!callee.getName().equals(name)) {
                            continue;
                        }
                        String recv = callee.getDeclClass().getFullName();
                        if (recv.equals(declClass) || clsp.isImplements(recv, declClass)) {
                            unresolvedEdges++;
                            visible.add(id(mth));
                        }
                    }
                }
            }
            long visibleMs = (System.nanoTime() - t) / 1_000_000;

            // --- pass 2: in-APK overriders, and who calls them ------------
            t = System.nanoTime();
            Map<String, MethodNode> overrides = new TreeMap<>();
            for (ClassNode cls : classes) {
                String cn = cls.getClassInfo().getFullName();
                // isImplements(X, X) is false, so the sink's own declaring class
                // being in the dex needs the equality arm -- that is the
                // bundled-SDK case, same mechanism as an override.
                if (!cn.equals(declClass) && !clsp.isImplements(cn, declClass)) {
                    continue;
                }
                for (MethodNode mth : cls.getMethods()) {
                    if (mth.getMethodInfo().getName().equals(name)) {
                        overrides.put(mth.getMethodInfo().getFullId(), mth);
                    }
                }
            }
            long overrideScanMs = (System.nanoTime() - t) / 1_000_000;

            t = System.nanoTime();
            Set<String> viaOverride = new TreeSet<>();
            Map<String, Integer> perReceiver = new TreeMap<>();
            for (MethodNode ov : overrides.values()) {
                String owner = ov.getParentClass().getClassInfo().getFullName();
                int n = 0;
                for (MethodNode user : ov.getUseIn()) {
                    viaOverride.add(id(user));
                    n++;
                }
                perReceiver.merge(owner, n, Integer::sum);
            }
            long useInMs = (System.nanoTime() - t) / 1_000_000;

            Set<String> onlyViaOverride = new TreeSet<>(viaOverride);
            onlyViaOverride.removeAll(visible);
            System.out.println("  call sites only the override walk finds:");
            onlyViaOverride.stream().limit(12).forEach(s -> System.out.println("      " + s));

            // --- pass 3: bytecode ground truth ---------------------------
            t = System.nanoTime();
            long total = 0, inside = 0, leaves = 0;
            Set<String> truthCallers = new TreeSet<>();
            // Receivers carrying the method name that the subtype rule REJECTS --
            // if this is non-empty the sweep's predicate, not its data source, is
            // the thing losing call sites.
            Map<String, Integer> nonSubtypeReceivers = new TreeMap<>();
            for (ClassNode cls : classes) {
                for (MethodNode mth : cls.getMethods()) {
                    if (mth.isNoCode()) {
                        continue;
                    }
                    ICodeReader cr = mth.getCodeReader();
                    if (cr == null) {
                        continue;
                    }
                    long[] c = { 0, 0, 0 };
                    try {
                        cr.copy().visitInstructions(insn -> {
                            MethodInfo callee = calleeOf(root, insn, name);
                            if (callee == null || !callee.getName().equals(name)) {
                                return;
                            }
                            String recv = callee.getDeclClass().getFullName();
                            if (!recv.equals(declClass) && !clsp.isImplements(recv, declClass)) {
                                nonSubtypeReceivers.merge(recv, 1, Integer::sum);
                                return;
                            }
                            c[0]++;
                            if (root.resolveMethod(callee) != null) {
                                c[1]++;
                            } else {
                                c[2]++;
                            }
                        });
                    } catch (Exception ignored) {
                        // a probe, not a tool: an unreadable method is not the point
                    }
                    total += c[0];
                    inside += c[1];
                    leaves += c[2];
                    if (c[0] > 0) {
                        truthCallers.add(id(mth));
                    }
                }
            }
            long truthMs = (System.nanoTime() - t) / 1_000_000;
            System.out.printf("  name-matching receivers the subtype rule rejects: %d invokes over %d receivers%n",
                    nonSubtypeReceivers.values().stream().mapToInt(Integer::intValue).sum(),
                    nonSubtypeReceivers.size());
            nonSubtypeReceivers.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(6)
                    .forEach(e -> System.out.printf("      %-60s %d%n", e.getKey(), e.getValue()));

            System.out.printf("  invokes:      %d total | %d resolve INSIDE the dex | %d leave%n",
                    total, inside, leaves);
            System.out.printf("  callers:      %d truth | %d visible today | %d via overrides | %d override-ONLY%n",
                    truthCallers.size(), visible.size(), viaOverride.size(), onlyViaOverride.size());
            System.out.printf("  coverage:     visible+override = %d of %d truth callers%n",
                    union(visible, viaOverride).size(), truthCallers.size());
            Set<String> missed = new TreeSet<>(truthCallers);
            missed.removeAll(union(visible, viaOverride));
            System.out.printf("  still missed: %d%n", missed.size());
            int shown = 0;
            for (String m : missed) {
                if (shown++ >= 5) {
                    break;
                }
                System.out.println("      " + m);
            }
            System.out.printf("  in-APK overriders: %d method(s) on %d class(es)%n",
                    overrides.size(), perReceiver.size());
            perReceiver.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(8)
                    .forEach(e -> System.out.printf("      %-60s %d callers%n", e.getKey(), e.getValue()));
            System.out.printf("  cost: sweep %d ms | override scan %d ms | getUseIn %d ms | bytecode truth %d ms%n",
                    visibleMs, overrideScanMs, useInMs, truthMs);
            System.out.printf("  (unresolved edges matched: %d)%n", unresolvedEdges);
        }

        core.close();
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    private static String id(MethodNode mth) {
        return mth.getMethodInfo().getFullId();
    }

    private static MethodInfo calleeOf(RootNode root,
            jadx.api.plugins.input.insns.InsnData insn, String wantName) {
        if (insn.getOpcode() == jadx.api.plugins.input.insns.Opcode.UNKNOWN) {
            return null;
        }
        try {
            switch (insn.getIndexType()) {
                case METHOD_REF:
                    insn.decode();
                    jadx.api.plugins.input.insns.custom.ICustomPayload payload = insn.getPayload();
                    return MethodInfo.fromRef(root, payload != null
                            ? (jadx.api.plugins.input.data.IMethodRef) payload
                            : insn.getIndexAsMethod());
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}

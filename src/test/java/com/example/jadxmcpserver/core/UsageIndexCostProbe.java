package com.example.jadxmcpserver.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.usage.impl.InMemoryUsageInfoCache;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;

/**
 * Measurement harness for "The one trap to measure first" in the parity plan.
 *
 * Not a test — a main() run by hand against a real APK, because the numbers it
 * produces are what decide whether the xref index is built eagerly at load or
 * lazily on first query. Kept in the tree so the measurement can be repeated
 * when the jadx version moves.
 *
 * Usage: exec:java -Dexec.mainClass=...UsageIndexCostProbe -Dexec.args="<apk>"
 */
public final class UsageIndexCostProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
        boolean withCache = args.length > 1 && Boolean.parseBoolean(args[1]);

        File apkFile = new File(apk);
        if (!apkFile.exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }

        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(apkFile);
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);
        if (withCache) {
            jadxArgs.setUsageInfoCache(new InMemoryUsageInfoCache());
        }

        System.out.println("apk=" + apk + " sizeMB=" + (apkFile.length() / (1024 * 1024))
                + " usageInfoCache=" + (withCache ? "InMemory" : "Empty(default)"));

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            long t0 = System.nanoTime();
            jadx.load();
            long loadMs = ms(t0);
            int classCount = jadx.getRoot().getClasses().size();
            System.out.println("load: " + loadMs + " ms, classes=" + classCount);

            // Pick a class in the middle of the list; avoids accidentally
            // choosing something trivially unreferenced at either end.
            ClassNode probe = jadx.getRoot().getClasses().get(classCount / 2);
            System.out.println("probe class: " + probe.getClassInfo().getAliasFullName());

            long t1 = System.nanoTime();
            int classUses = probe.getUseIn().size();
            long firstMs = ms(t1);
            System.out.println("FIRST getUseIn(): " + firstMs + " ms  (uses=" + classUses + ")");

            long t2 = System.nanoTime();
            int again = probe.getUseIn().size();
            System.out.println("SECOND getUseIn() same class: " + ms(t2) + " ms  (uses=" + again + ")");

            // A different class: shows whether the first call built a global
            // index or only did per-class work.
            ClassNode other = jadx.getRoot().getClasses().get(classCount / 3);
            long t3 = System.nanoTime();
            int otherUses = other.getUseIn().size();
            System.out.println("getUseIn() on a DIFFERENT class: " + ms(t3) + " ms  (uses=" + otherUses + ")");

            // Method-level xref, which is what Task 2 actually needs.
            MethodNode mth = null;
            for (ClassNode c : jadx.getRoot().getClasses()) {
                if (!c.getMethods().isEmpty()) {
                    for (MethodNode m : c.getMethods()) {
                        if (!m.getMethodInfo().isConstructor()) {
                            mth = m;
                            break;
                        }
                    }
                }
                if (mth != null && !mth.getUseIn().isEmpty()) {
                    break;
                }
            }
            if (mth != null) {
                long t4 = System.nanoTime();
                int mUses = mth.getUseIn().size();
                System.out.println("MethodNode.getUseIn(): " + ms(t4) + " ms  (uses=" + mUses
                        + ") on " + mth.getMethodInfo().getAliasFullName());
            }

            // Total cost of walking every class's xrefs — the worst case an
            // eager index would pay up front.
            long t5 = System.nanoTime();
            long total = 0;
            for (ClassNode c : jadx.getRoot().getClasses()) {
                total += c.getUseIn().size();
            }
            System.out.println("walk ALL classes getUseIn(): " + ms(t5) + " ms  (total refs=" + total + ")");

            long t6 = System.nanoTime();
            jadx.getRoot().runPreDecompileStage();
            System.out.println("runPreDecompileStage(): " + ms(t6) + " ms");
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

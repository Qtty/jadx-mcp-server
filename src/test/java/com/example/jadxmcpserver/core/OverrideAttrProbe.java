package com.example.jadxmcpserver.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

import java.io.File;

/**
 * Second half of the parity plan's "measure first" step.
 *
 * The xref probe showed usage info is already live after load(). This one asks
 * the separate question Task 4 depends on: is AType.METHOD_OVERRIDE attached
 * after a plain load(), or does it need runPreDecompileStage() first — and if
 * so, is the per-class variant enough?
 */
public final class OverrideAttrProbe {

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0] : "misc/DivaApplication.apk";
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

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            long t0 = System.nanoTime();
            jadx.load();
            System.out.println("load: " + ms(t0) + " ms, classes=" + jadx.getRoot().getClasses().size());

            System.out.println("-- BEFORE runPreDecompileStage --");
            report(jadx);

            ClassNode sample = firstOnCreateOwner(jadx);
            if (sample != null) {
                long tc = System.nanoTime();
                jadx.getRoot().runPreDecompileStageForClass(sample);
                System.out.println("runPreDecompileStageForClass(" + sample.getClassInfo().getAliasFullName()
                        + "): " + ms(tc) + " ms");
                MethodNode m = onCreateOf(sample);
                if (m != null) {
                    System.out.println("  after per-class, that method's attr present: "
                            + (m.get(AType.METHOD_OVERRIDE) != null));
                }
            }

            long t1 = System.nanoTime();
            jadx.getRoot().runPreDecompileStage();
            System.out.println("runPreDecompileStage(): " + ms(t1) + " ms");

            long t2 = System.nanoTime();
            jadx.getRoot().runPreDecompileStage();
            System.out.println("runPreDecompileStage() AGAIN: " + ms(t2) + " ms");

            System.out.println("-- AFTER runPreDecompileStage --");
            report(jadx);
        }
    }

    /** How many methods carry the override attribute, and one worked example. */
    private static void report(JadxDecompiler jadx) {
        int withAttr = 0;
        int scanned = 0;
        String example = null;
        for (ClassNode cls : jadx.getRoot().getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                scanned++;
                MethodOverrideAttr attr = mth.get(AType.METHOD_OVERRIDE);
                if (attr != null) {
                    withAttr++;
                    if (example == null && !attr.getBaseMethods().isEmpty()) {
                        example = mth.getMethodInfo().getAliasFullName()
                                + " overrides=" + attr.getOverrideList().size()
                                + " related=" + attr.getRelatedMthNodes().size()
                                + " base=" + attr.getBaseMethods().size();
                    }
                }
            }
            if (scanned > 60000) {
                break; // enough signal on a huge APK
            }
        }
        System.out.println("  methods scanned=" + scanned + " withOverrideAttr=" + withAttr);
        System.out.println("  example: " + example);
    }

    private static ClassNode firstOnCreateOwner(JadxDecompiler jadx) {
        for (ClassNode cls : jadx.getRoot().getClasses()) {
            if (onCreateOf(cls) != null) {
                return cls;
            }
        }
        return null;
    }

    private static MethodNode onCreateOf(ClassNode cls) {
        for (MethodNode m : cls.getMethods()) {
            if (m.getMethodInfo().getAlias().equals("onCreate")) {
                return m;
            }
        }
        return null;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

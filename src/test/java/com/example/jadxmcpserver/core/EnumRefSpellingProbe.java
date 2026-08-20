package com.example.jadxmcpserver.core;

import java.io.File;
import java.util.List;

/**
 * MEASUREMENT ONLY. Which spelling does a REFERENCED enum constant get, and
 * does it depend on the request history?
 *
 * ClassSourceReproducibilityProbe found the case: in
 * com.google.android.gms.internal.ads.zzhuy, one argument reads
 * {@code zzhqb.ASYMMETRIC_PUBLIC} from one history and {@code zzhqb.zzd} from
 * another -- the deobfuscated enum-constant name against the raw dex field
 * name, in a class whose declared members, call multiset and try structure are
 * all identical between the two. It is a sixth thing that varies, and
 * the client's tool reference's "what varies" list has five.
 *
 * This probe isolates it: one process, one history, one answer, so the two
 * spellings can be attributed to the histories that produce them rather than
 * inferred from a pair of dumps.
 *
 * WHAT IT MEASURED, 2026-08-08, one process per history:
 *
 * <pre>
 *   cold        zzhqb.zzd                 684 bytes of source
 *   enumfirst   zzhqb.ASYMMETRIC_PUBLIC   698 bytes
 * </pre>
 *
 * So the mechanism is not mysterious: the constant's name is recovered from the
 * ENUM's own class, and a reference decompiled before that class has been
 * processed prints the raw dex field name. The two histories agree on
 * everything else in the class.
 *
 * The reassuring half, printed too: get_fields_of_class on the enum answers
 * "zzd" in BOTH histories, because it reads the dex. The identity guarantee
 * holds; it is the SOURCE TEXT that moves, which is why the client's tool reference tells a
 * worker to cite a constant from the field tools rather than from a decompiled
 * line.
 *
 * Run: java -cp target/test-classes:target/classes:$(cat /tmp/jadx-cp.txt) \
 *          com.example.jadxmcpserver.core.EnumRefSpellingProbe <history>
 * where history is one of:
 *   cold      -- read the referencing class and nothing else
 *   enumfirst -- read the ENUM's own source first, then the referencing class
 *   warm      -- decompile 2,000 unrelated classes first
 */
public class EnumRefSpellingProbe {

    static final String APK = "misc/DivaApplication.apk";
    static final String REFERRER = "com.google.android.gms.internal.ads.zzhuy";
    static final String ENUM = "com.google.android.gms.internal.ads.zzhqb";

    public static void main(String[] args) {
        String history = args.length > 0 ? args[0] : "cold";
        if (!new File(APK).exists()) {
            System.out.println("SKIP: no such apk: " + APK);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(APK);
        core.loadApk();
        try {
            switch (history) {
                case "cold":
                    break;
                case "enumfirst":
                    core.getClassSource(ENUM);
                    break;
                case "warm": {
                    List<String> all = core.getAllClasses(null, 0);
                    int n = 0;
                    for (int i = 0; i < all.size() && n < 2000; i += 71) {
                        try {
                            core.getClassSource(all.get(i));
                            n++;
                        } catch (RuntimeException ignored) {
                            // a class that will not decompile still counts as asked for
                        }
                    }
                    System.out.println("warm-up: " + n + " classes");
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown history: " + history);
            }

            String src = core.getClassSource(REFERRER);
            System.out.println("history=" + history + "  bytes=" + src.length());
            for (String line : src.split("\n")) {
                if (line.contains("zzhqb.")) {
                    System.out.println("  REF: " + line.trim());
                }
            }
            System.out.println("  deobfuscated spelling present: "
                    + src.contains("zzhqb.ASYMMETRIC_PUBLIC"));
            System.out.println("  raw dex spelling present:      "
                    + src.contains("zzhqb.zzd"));

            // What the enum's own source calls its constants, in this history.
            String enumSrc = core.getClassSource(ENUM);
            System.out.println("  enum declares: " + declaredConstants(enumSrc));
            System.out.println("  get_fields_of_class(enum): "
                    + core.getFieldsOfClass(ENUM, 20));
        } finally {
            core.close();
        }
    }

    private static String declaredConstants(String enumSrc) {
        for (String line : enumSrc.split("\n")) {
            String t = line.trim();
            if (t.contains("ASYMMETRIC_PUBLIC") || t.startsWith("zzd") || t.startsWith("zza")) {
                return t;
            }
        }
        return "(not found)";
    }
}

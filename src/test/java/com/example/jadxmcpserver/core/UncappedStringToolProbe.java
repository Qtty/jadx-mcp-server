package com.example.jadxmcpserver.core;

import jadx.api.plugins.input.data.ICodeReader;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The widest answer the two no-maxBytes string tools can give.
 *
 * The consumer doc's claim about them was measured over overload SETS, which is
 * a different population from "one (class, name) pair's whole answer". This
 * ranks every (class, name) group by the dex instructions behind it -- the only
 * cheap proxy for smali length -- and then runs the real tools on the top of
 * that ranking.
 *
 * A probe, not a test: it asserts nothing and prints. Run it by name.
 */
class UncappedStringToolProbe {

    static final String BIG_APK = "misc/DivaApplication.apk";
    static final int TOP = Integer.getInteger("probeTop", 40);

    @Test
    void findTheWidestSmaliAndSourceForOneMethodName() throws Exception {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        JadxAnalyzerCore core = new JadxAnalyzerCore(BIG_APK);
        core.loadApk();
        try {
            // (class, alias) -> summed instruction units across the overload set,
            // which is exactly what getSmaliOfMethod concatenates.
            Map<String, long[]> groups = new LinkedHashMap<>();
            Map<String, String[]> names = new LinkedHashMap<>();
            for (ClassNode cls : MethodIdentityProbe.rootOf(core).getClasses()) {
                for (MethodNode mth : cls.getMethods()) {
                    String cn = cls.getClassInfo().getAliasFullName();
                    String mn = mth.getMethodInfo().getAlias();
                    String key = cn + "#" + mn;
                    groups.computeIfAbsent(key, k -> new long[]{0, 0})[0] += insns(mth);
                    groups.get(key)[1]++;
                    names.putIfAbsent(key, new String[]{cn, mn});
                }
            }
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort(Comparator.comparingLong(k -> -groups.get(k)[0]));

            System.out.println("== getSmaliOfMethod, widest " + TOP
                    + " (class, name) groups by instruction units ==");
            List<Object[]> smaliRows = new ArrayList<>();
            for (String key : keys.subList(0, TOP)) {
                String[] n = names.get(key);
                try {
                    String s = core.getSmaliOfMethod(n[0], n[1]);
                    if (s == null) {
                        continue;
                    }
                    smaliRows.add(new Object[]{wire(s), key,
                            groups.get(key)[0], groups.get(key)[1], s.startsWith("@"),
                            jsonWire(s)});
                } catch (RuntimeException e) {
                    System.out.println("  (skipped " + key + ": " + e.getMessage() + ")");
                }
            }
            smaliRows.sort(Comparator.comparingLong(r -> -(Long) r[0]));
            for (Object[] r : smaliRows.subList(0, Math.min(12, smaliRows.size()))) {
                System.out.println("  " + r[0] + " raw / " + r[5] + " wire  " + r[1]
                        + "  units=" + r[2] + " overloads=" + r[3] + " marked=" + r[4]);
            }

            System.out.println("== getMethodByName, same groups ==");
            List<Object[]> srcRows = new ArrayList<>();
            for (String key : keys.subList(0, TOP)) {
                String[] n = names.get(key);
                try {
                    String s = core.getMethodByName(n[0], n[1]);
                    if (s == null) {
                        continue;
                    }
                    srcRows.add(new Object[]{wire(s), key, s.startsWith("@"), jsonWire(s)});
                } catch (RuntimeException e) {
                    System.out.println("  (skipped " + key + ": " + e.getMessage() + ")");
                }
            }
            srcRows.sort(Comparator.comparingLong(r -> -(Long) r[0]));
            for (Object[] r : srcRows.subList(0, Math.min(12, srcRows.size()))) {
                System.out.println("  " + r[0] + " raw / " + r[3] + " wire  " + r[1]
                        + " marked=" + r[2]);
            }

            // The overload-set population the doc's 42,862 came from, for contrast.
            List<String> byOverloads = new ArrayList<>(groups.keySet());
            byOverloads.sort(Comparator.comparingLong(k -> -groups.get(k)[1]));
            System.out.println("== widest overload SETS (count) ==");
            for (String key : byOverloads.subList(0, 5)) {
                String[] n = names.get(key);
                String s = core.getSmaliOfMethod(n[0], n[1]);
                System.out.println("  " + groups.get(key)[1] + " overloads  " + key
                        + "  " + (s == null ? -1 : wire(s)) + " bytes");
            }
        } finally {
            core.close();
        }
    }

    private static long wire(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The unit the consumer doc reports: the answer inside a JSON string, which
     * is what every @mcp.tool returns.
     */
    private static long jsonWire(String s) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(s).getBytes(StandardCharsets.UTF_8).length;
    }

    private static int insns(MethodNode m) {
        if (m.isNoCode()) {
            return 0;
        }
        ICodeReader reader = m.getCodeReader();
        return reader == null ? 0 : reader.getUnitsCount();
    }
}

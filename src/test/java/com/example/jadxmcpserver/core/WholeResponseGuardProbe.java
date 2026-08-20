package com.example.jadxmcpserver.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT ONLY. Can get_callees or get_overrides hand back a response over
 * the oversized line without either of its two lists crossing it?
 *
 * Both tools guard each list on its own and nothing weighs the whole answer --
 * unlike getTypeHierarchy and findSinkCallSites, whose responses are also
 * several lists and which do weigh the whole thing. So the shape of the hole is
 * two lists of 900 KB: a 1.8 MB response wearing no notice at all.
 *
 * The question this answers is whether that shape EXISTS on a real APK, which
 * decides whether closing it is a repair or a uniformity fix. Two sweeps:
 *
 *   overrides -- a candidate list of the widely-implemented types a tracer
 *                actually holds, including java.lang.Object's own methods,
 *                which are not in the dex but are known to the classpath graph
 *                and are therefore legal targets.
 *   callees   -- the 500 methods with the most dex instructions behind them,
 *                the same ranking get_smali_of_method's widest figure came
 *                from. getCallees dedupes, so instructions bound distinct call
 *                targets from above.
 *
 * WHAT IT MEASURED, 2026-08-08, on a large production APK, both tools at limit 0:
 *
 * <pre>
 *   get_overrides  widest whole  470,503 B  java.lang.Runnable.run
 *                                            (overriddenBy 470,379, baseMethods 2)
 *                  next          342,364    View$OnClickListener.onClick
 *                                190,796    Function1.invoke
 *   get_callees    widest whole   68,651 B  ServiceRegistry.&lt;clinit&gt;
 *                                            (callees 68,522, external 87)
 * </pre>
 *
 * The answer is therefore NO on this APK, twice over: the widest whole response
 * is 45% of the line, and in every row one list holds over 99% of the bytes, so
 * the shape the missing guard cannot see -- two comparable lists -- does not
 * occur. baseMethods is an override CHAIN and is structurally tiny (2 to 45
 * bytes across all nineteen targets). The guard is added for uniformity, and
 * this probe is what stops that being described as a repair.
 *
 * Run: java -cp target/test-classes:target/classes:$(cat /tmp/jadx-cp.txt) \
 *          com.example.jadxmcpserver.core.WholeResponseGuardProbe [apk] [topN]
 */
public class WholeResponseGuardProbe {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String[][] OVERRIDE_TARGETS = {
            {"java.lang.Object", "toString"},
            {"java.lang.Object", "equals"},
            {"java.lang.Object", "hashCode"},
            {"java.lang.Runnable", "run"},
            {"java.lang.Comparable", "compareTo"},
            {"java.lang.Iterable", "iterator"},
            {"java.io.Closeable", "close"},
            {"java.util.Comparator", "compare"},
            {"java.util.concurrent.Callable", "call"},
            {"kotlin.jvm.functions.Function0", "invoke"},
            {"kotlin.jvm.functions.Function1", "invoke"},
            {"kotlin.jvm.functions.Function2", "invoke"},
            {"android.view.View$OnClickListener", "onClick"},
            {"android.os.Parcelable$Creator", "createFromParcel"},
            {"android.app.Activity", "onCreate"},
            {"androidx.lifecycle.Observer", "onChanged"},
            {"io.reactivex.functions.Consumer", "accept"},
            {"java.lang.Thread", "run"},
            {"android.webkit.WebViewClient", "shouldOverrideUrlLoading"},
    };

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0]
                : "misc/DivaApplication.apk";
        int topN = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        if (!new File(apk).exists()) {
            System.out.println("SKIP: no such apk: " + apk);
            return;
        }
        JadxAnalyzerCore core = new JadxAnalyzerCore(apk);
        core.loadApk();
        try {
            overrides(core);
            callees(core, topN);
        } finally {
            core.close();
        }
    }

    private static void overrides(JadxAnalyzerCore core) throws Exception {
        System.out.println("== get_overrides, limit 0: each list against the whole answer ==");
        List<Object[]> rows = new ArrayList<>();
        for (String[] t : OVERRIDE_TARGETS) {
            Map<String, Object> r;
            try {
                r = core.getOverrides(t[0], t[1], null, 0);
            } catch (RuntimeException e) {
                System.out.println("  (skipped " + t[0] + "." + t[1] + ": " + e.getMessage() + ")");
                continue;
            }
            long whole = wire(r);
            long base = wire(r.get("baseMethods"));
            long over = wire(r.get("overriddenBy"));
            rows.add(new Object[]{whole, base, over, t[0] + "." + t[1],
                    size(r.get("overriddenBy"))});
        }
        rows.sort(Comparator.comparingLong(r -> -(Long) r[0]));
        for (Object[] r : rows) {
            System.out.printf("  whole %,10d   baseMethods %,9d   overriddenBy %,10d"
                            + "  (%,d entries)  %s%n",
                    r[0], r[1], r[2], r[4], r[3]);
        }
        System.out.println("  oversized line: " + JadxAnalyzerCore.OVERSIZED_RESPONSE_BYTES);
        System.out.println();
    }

    private static void callees(JadxAnalyzerCore core, int topN) throws Exception {
        System.out.println("== get_callees, limit 0: the " + topN
                + " methods with the most dex instructions ==");
        RootNode root = ClassSourceStabilityProbe.root(core);
        List<Object[]> ranked = new ArrayList<>();
        for (ClassNode cls : root.getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                int insns = mth.getInsnsCount();
                if (insns > 0) {
                    ranked.add(new Object[]{insns, cls.getClassInfo().getAliasFullName(),
                            mth.getMethodInfo().getAlias()});
                }
            }
        }
        ranked.sort(Comparator.comparingInt(r -> -(Integer) r[0]));
        System.out.println("  ranked " + ranked.size() + " methods with a body");

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            Object[] m = ranked.get(i);
            Map<String, Object> r;
            try {
                r = core.getCallees((String) m[1], (String) m[2], null, 0);
            } catch (RuntimeException e) {
                continue;
            }
            rows.add(new Object[]{wire(r), wire(r.get("callees")), wire(r.get("external")),
                    m[1] + "." + m[2], m[0]});
        }
        rows.sort(Comparator.comparingLong(r -> -(Long) r[0]));
        for (Object[] r : rows.subList(0, Math.min(10, rows.size()))) {
            System.out.printf("  whole %,10d   callees %,9d   external %,9d"
                            + "  (%,d insns)  %s%n",
                    r[0], r[1], r[2], r[4], r[3]);
        }
        System.out.println("  oversized line: " + JadxAnalyzerCore.OVERSIZED_RESPONSE_BYTES);
    }

    private static int size(Object value) {
        return value instanceof List ? ((List<?>) value).size() : -1;
    }

    private static long wire(Object value) throws Exception {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }
}

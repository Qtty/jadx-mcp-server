package com.example.jadxmcpserver.core;

import jadx.api.plugins.input.data.ICodeReader;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which target makes each list-returning tool as wide as it can be.
 *
 * A cap is only worth arguing about at the top of the distribution, and the top
 * is not guessable -- "the class with the most methods" on an obfuscated APK is
 * not one anybody would think to try. This finds it, so the before/after numbers
 * are the worst case rather than a convenient one.
 *
 * A probe, not a test: it asserts nothing and prints. Run it by name.
 */
class WidestListProbe {

    static final String BIG_APK = "misc/DivaApplication.apk";

    @Test
    void findTheWidestTargetForEveryUncappedList() throws Exception {
        assumeTrue(new File(BIG_APK).exists(), "big APK not present");
        JadxAnalyzerCore core = new JadxAnalyzerCore(BIG_APK);
        core.loadApk();
        try {
            List<ClassNode> classes =
                    new ArrayList<>(MethodIdentityProbe.rootOf(core).getClasses());

            top("methods declared", classes, c -> c.getMethods().size());
            top("fields declared", classes, c -> c.getFields().size());
            top("inner classes", classes, c -> c.getInnerClasses().size());
            top("interfaces", classes, c -> c.getInterfaces().size());

            if (Boolean.getBoolean("skipRefScan")) {
                return;
            }

            // The forward direction. getCallees dedupes, so the long methods are
            // not the wide ones -- 39,107 instructions of table-filling is one
            // callee. Rank by DISTINCT method refs, counted straight off the
            // code reader, then run the real tool on the top of that list.
            List<MethodNode> methods = new ArrayList<>();
            for (ClassNode c : classes) {
                methods.addAll(c.getMethods());
            }
            Map<MethodNode, Integer> refs = new java.util.IdentityHashMap<>();
            for (MethodNode m : methods) {
                refs.put(m, distinctRefs(m));
            }
            methods.sort(Comparator.comparingInt(m -> -refs.get(m)));
            System.out.println("\n== distinct method refs, whole APK ==");
            for (MethodNode m : methods.subList(0, 15)) {
                System.out.println("  " + refs.get(m) + "  "
                        + m.getMethodInfo().getFullId());
            }

            System.out.println("\n== callees, over the 25 widest methods ==");
            List<Object[]> byCallees = new ArrayList<>();
            for (MethodNode m : methods.subList(0, 25)) {
                try {
                    Map<String, Object> r = core.getCallees(
                            m.getParentClass().getClassInfo().getAliasFullName(),
                            m.getMethodInfo().getAlias(),
                            m.getMethodInfo().getShortId());
                    int in = ((List<?>) r.get("callees")).size();
                    int ex = ((List<?>) r.get("external")).size();
                    byCallees.add(new Object[] { in + ex,
                            m.getParentClass().getClassInfo().getAliasFullName()
                                    + "." + m.getMethodInfo().getAlias()
                                    + "   callees=" + in + " external=" + ex
                                    + " insns=" + insns(m) });
                } catch (RuntimeException e) {
                    System.out.println("  (skipped " + m.getMethodInfo().getFullId()
                            + ": " + e.getMessage() + ")");
                }
            }
            byCallees.sort(Comparator.comparingInt(a -> -(Integer) a[0]));
            for (Object[] row : byCallees) {
                System.out.println("  " + row[0] + "  " + row[1]);
            }
        } finally {
            core.close();
        }
    }

    private static int insns(MethodNode m) {
        if (m.isNoCode()) {
            return 0;
        }
        ICodeReader reader = m.getCodeReader();
        return reader == null ? 0 : reader.getUnitsCount();
    }

    /** scanCallees' upper bound: every method the bytecode names, deduped. */
    private static int distinctRefs(MethodNode m) {
        if (m.isNoCode()) {
            return 0;
        }
        ICodeReader reader = m.getCodeReader();
        if (reader == null) {
            return 0;
        }
        java.util.Set<Object> refs = new java.util.HashSet<>();
        reader.copy().visitInstructions(insn -> {
            if (insn.getIndexType()
                    == jadx.api.plugins.input.insns.InsnIndexType.METHOD_REF) {
                // getIndex() is 0 until decode() has run -- without this every
                // method scores 1 and the ranking is noise.
                insn.decode();
                refs.add(insn.getIndex());
            }
        });
        return refs.size();
    }

    private static void top(String what, List<ClassNode> classes,
                            java.util.function.ToIntFunction<ClassNode> size) {
        System.out.println("\n== " + what + " ==");
        List<ClassNode> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparingInt(c -> -size.applyAsInt(c)));
        for (int i = 0; i < Math.min(15, sorted.size()); i++) {
            ClassNode c = sorted.get(i);
            System.out.println("  " + size.applyAsInt(c) + "  "
                    + c.getClassInfo().getAliasFullName());
        }
    }
}

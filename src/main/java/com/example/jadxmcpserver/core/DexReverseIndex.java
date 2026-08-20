package com.example.jadxmcpserver.core;

import jadx.api.plugins.input.data.ICallSite;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IFieldRef;
import jadx.api.plugins.input.data.IMethodHandle;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationMethodParamsAttr;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.Opcode;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.ICodeNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.input.InsnDataUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Who references what, read out of the dex instead of out of jadx's usage graph.
 *
 * <h2>Why this exists</h2>
 *
 * jadx keeps reverse edges on the nodes themselves -- {@code MethodNode.getUseIn()},
 * {@code FieldNode.getUseIn()}, {@code ClassNode.getUseIn()/getUseInMth()} -- and
 * every one of them is REWRITTEN as classes are decompiled:
 *
 * <pre>
 *   UsageInfoVisitor.replaceMethodUsage(old, new)
 *        merges new's caller list into old's and then sets new's to EMPTY.
 *        Called from ClassModifier.modifySyntheticMethod.
 *   InlineMethods.updateUsageInfo
 *        drops the inlining caller from the inlined method's useIn.
 *   InlineMethods.replaceClsUsage
 *        safeReplace()s the inlined method for its caller in the target
 *        ClassNode's useInMth AND useIn.
 *   ProcessAnonymous
 *        safeAdd()s the outer class to an anonymous class's useIn.
 * </pre>
 *
 * The daemon is long-lived and shared, so "has this class been decompiled yet"
 * depends on some earlier, unrelated request, and every xref answer moved with
 * it. Measured on DivaApplication.apk, decompiling all 1,791 classes:
 *
 * <pre>
 *   MethodNode.getUseIn    988 methods moved, 1,343 edges lost, 380 gained,
 *                          692 left claiming NOTHING calls them
 *   FieldNode.getUseIn     983 fields moved,    389 lost,      2,175 gained
 *   ClassNode.getUseInMth    2 classes moved,     6 lost,          3 gained
 *   ClassNode.getUseIn      59 classes moved,     2 lost,        134 gained
 * </pre>
 *
 * and on a large production APK after only 3,000 of 141,461 classes were decompiled: 1,536
 * methods moved (1,800 lost, 722 gained, 883 emptied), 1,458 fields, 46 + 165
 * classes. The lost half is a false negative -- an agent concludes nothing calls
 * a sink when the bytecode plainly says otherwise -- which is the direction a
 * bug caller cannot afford.
 *
 * <h2>What it is built from</h2>
 *
 * Exactly what {@code UsageInfoVisitor} builds its forward pass from, inverted
 * and kept: {@code MethodNode.getCodeReader()} (a final, never-cleared copy of
 * the dex bytes), plus the declaration metadata -- supertypes, field types,
 * method signatures, annotations -- that jadx reads off the same dex. It is the
 * same walk {@link JadxAnalyzerCore#scanCallees} already does for the forward
 * direction, extended to TYPE_REF and FIELD_REF, so forward and reverse answers
 * agree by construction.
 *
 * Those inputs are immutable in the sense that matters, and that was measured
 * rather than assumed (DexTreeStabilityProbe): decompiling 1,791 of DIVA's 1,791
 * classes and 6,000 of a large production APK's 141,461 changed neither the class, method and
 * field enumeration nor a single one of the resolved caller->callee edges the
 * dex names -- 14,418 and 1,384,801, 0 lost and 0 gained. So it does not matter
 * WHEN this index is built.
 *
 * <h2>When it is built</h2>
 *
 * Lazily, on the first xref or sink-sweep call, once per APK, and then kept
 * forever -- the dex does not change, so there is nothing to invalidate. Eager
 * building at load was measured and rejected: it costs the same whenever it runs
 * (the line above is why), so the only question is who waits, and eagerly the
 * answer is every load, including the loads whose caller never asks for an xref.
 * On a large production APK that is 3.1 s against a 13.6 s load. See {@link #stats()} for
 * what one build costs, which /health reports.
 *
 * <h2>Concurrency</h2>
 *
 * Same shape as {@link AnalyzerRegistry}: a hit is a volatile read and never
 * touches the lock; only the first miss serialises, and the double-check inside
 * the monitor is what stops N parallel requests each building their own copy.
 *
 * <h2>Storage</h2>
 *
 * Adjacency is compacted to plain arrays once the build finishes. An
 * {@code ArrayList} per key costs ~40 bytes of header and slack on top of its
 * contents, which over a large production APK's 5,300,636 edges and 1,141,709 keys is most of
 * the footprint; arrays are exactly the edges plus one header per key. The
 * whole index retains 88 MB there -- 3% of what the warm APK itself costs and
 * 1.4% of the 6 GB {@link AnalyzerRegistry#PER_APK_BUDGET_MB} slot, so that
 * constant and the capacity it derives are left as they are.
 */
public final class DexReverseIndex {

    private final Map<MethodNode, MethodNode[]> methodCallers;
    private final Map<FieldNode, MethodNode[]> fieldUsers;
    private final Map<ClassNode, MethodNode[]> classUsersInMth;
    private final Map<ClassNode, ClassNode[]> classUsers;
    private final Map<String, Object> stats;

    private static final MethodNode[] NO_METHODS = new MethodNode[0];
    private static final ClassNode[] NO_CLASSES = new ClassNode[0];

    private DexReverseIndex(Builder b, long startNanos) {
        this.methodCallers = compact(b.methodCallers, NO_METHODS);
        this.fieldUsers = compact(b.fieldUsers, NO_METHODS);
        this.classUsersInMth = compact(b.classUsersInMth, NO_METHODS);
        this.classUsers = compact(b.classUsers, NO_CLASSES);

        Map<String, Object> s = new LinkedHashMap<>();
        // Timed to here, not to the end of the scan: compaction is part of what
        // the first caller waits for, and it was once most of it.
        s.put("buildMillis", (System.nanoTime() - startNanos) / 1_000_000);
        s.put("methodsScanned", b.methodsScanned);
        s.put("scanErrors", b.scanErrors);
        s.put("methodCallerEdges", edgeCount(methodCallers));
        s.put("fieldUserEdges", edgeCount(fieldUsers));
        s.put("classMethodUserEdges", edgeCount(classUsersInMth));
        s.put("classUserEdges", edgeCount(classUsers));
        this.stats = Collections.unmodifiableMap(s);
    }

    private static long edgeCount(Map<?, ? extends Object[]> m) {
        long total = 0;
        for (Object[] arr : m.values()) {
            total += arr.length;
        }
        return total;
    }

    /** Methods whose bytecode contains a call resolving to this method. */
    public List<MethodNode> callersOf(MethodNode mth) {
        return asList(methodCallers.get(mth));
    }

    /** Methods whose bytecode reads or writes this field. */
    public List<MethodNode> usersOf(FieldNode fld) {
        return asList(fieldUsers.get(fld));
    }

    /** Methods that name this class -- in an instruction, a signature or an annotation. */
    public List<MethodNode> methodUsersOf(ClassNode cls) {
        return asList(classUsersInMth.get(cls));
    }

    /** Classes that name this class, including those with no method context. */
    public List<ClassNode> classUsersOf(ClassNode cls) {
        return asList(classUsers.get(cls));
    }

    /** What the build cost and how big it is. Surfaced by /health. */
    public Map<String, Object> stats() {
        return stats;
    }

    /**
     * A view, not a copy -- the widest key on a large production APK has 61,910 users and
     * these are read on every xref call. Unmodifiable because the array behind
     * it is shared by every reader on every thread for the life of the APK, and
     * a bare {@code Arrays.asList} would let one caller's {@code set()} corrupt
     * the index for all of them.
     */
    private static <V> List<V> asList(V[] arr) {
        return arr == null ? List.of() : Collections.unmodifiableList(java.util.Arrays.asList(arr));
    }

    // ---------------------------------------------------------------- build

    /**
     * One pass over every class, field, method and instruction in the dex.
     *
     * Mirrors {@code UsageInfoVisitor.processClass/processMethod/processInsn}
     * edge for edge, minus the three forward maps ({@code clsDeps},
     * {@code mthUses}, {@code unresolvedMthUsage}) that nothing here reads --
     * get_callees answers the forward direction straight from the dex and does
     * not want an index at all.
     */
    public static DexReverseIndex build(RootNode root) {
        long t0 = System.nanoTime();
        Builder b = new Builder(root);
        for (ClassNode cls : root.getClasses()) {
            b.processClass(cls);
        }
        return new DexReverseIndex(b, t0);
    }

    /**
     * Dedupe once, at the end, keeping first-seen order.
     *
     * Deduping during the walk would need a Set per key alive for the whole
     * build; doing it here needs at most one Set alive at a time. The order is
     * the dex's own -- classes in {@code root.getClasses()} order, then methods
     * in declaration order -- so it is reproducible, which is what a caller
     * re-running a query needs. Every tool sorts its own output anyway.
     *
     * A Set PER KEY rather than one reused across keys, and a fast path for the
     * single-user case: an earlier version that reused one LinkedHashSet and
     * cleared it spent 9.0 s here against 2.2 s for the whole dex scan.
     * {@code clear()} walks the table, and the table had already grown to fit
     * the widest key in the APK -- 61,910 users on a large production APK -- so every one of
     * the 1,141,709 keys paid for it. 542,783 of those keys (48%) have exactly
     * one user and now allocate no Set at all.
     */
    private static <K, V> Map<K, V[]> compact(Map<K, List<V>> src, V[] empty) {
        Map<K, V[]> out = new HashMap<>(Math.max(16, (int) (src.size() / 0.7f) + 1));
        for (Map.Entry<K, List<V>> e : src.entrySet()) {
            List<V> values = e.getValue();
            if (values.size() > 1) {
                Set<V> seen = new LinkedHashSet<>(values);
                out.put(e.getKey(), seen.toArray(empty));
            } else {
                out.put(e.getKey(), values.toArray(empty));
            }
            // Drop the ArrayList as we go: the compacted copy and the growable
            // one must not both be live across a 5 M-edge APK.
            e.setValue(null);
        }
        src.clear();
        return out;
    }

    /**
     * The mutable half, alive only during {@link #build}.
     *
     * Kept separate so the finished index has no growable state and can be
     * published to other threads by a single volatile write.
     */
    private static final class Builder {
        final RootNode root;
        final Map<MethodNode, List<MethodNode>> methodCallers = new HashMap<>();
        final Map<FieldNode, List<MethodNode>> fieldUsers = new HashMap<>();
        final Map<ClassNode, List<MethodNode>> classUsersInMth = new HashMap<>();
        final Map<ClassNode, List<ClassNode>> classUsers = new HashMap<>();
        int methodsScanned;
        int scanErrors;

        Builder(RootNode root) {
            this.root = root;
        }

        void processClass(ClassNode cls) {
            clsUse(cls, cls.getSuperClass());
            for (ArgType type : cls.getInterfaces()) {
                clsUse(cls, type);
            }
            for (ArgType type : cls.getGenericTypeParameters()) {
                clsUse(cls, type);
            }
            for (FieldNode fld : cls.getFields()) {
                clsUse(cls, fld.getType());
                processAnnotations(fld);
            }
            processAnnotations(cls);
            for (MethodNode mth : cls.getMethods()) {
                processMethod(mth);
            }
        }

        void processMethod(MethodNode mth) {
            processAnnotations(mth);
            AnnotationMethodParamsAttr params = mth.get(JadxAttrType.ANNOTATION_MTH_PARAMETERS);
            if (params != null) {
                for (AnnotationsAttr attr : params.getParamList()) {
                    processAnnotationAttr(mth, attr);
                }
            }
            clsUse(mth, mth.getReturnType());
            for (ArgType type : mth.getArgTypes()) {
                clsUse(mth, type);
            }
            processInstructions(mth);
        }

        /**
         * The dex bytes, which is the whole point.
         *
         * copy() before visiting for the reason scanCallees copies:
         * visitInstructions moves the reader's position, MethodNode.load() reads
         * the same instance, and the daemon serves requests concurrently. The
         * copy is a ByteBuffer.duplicate(), not a data copy.
         *
         * A method whose instructions cannot be read is counted rather than
         * thrown on: one unreadable method must not cost the whole APK its
         * index, and the count lands in stats() so a silent hole is still a
         * visible one.
         */
        void processInstructions(MethodNode mth) {
            if (mth.isNoCode()) {
                return;
            }
            ICodeReader codeReader = mth.getCodeReader();
            if (codeReader == null) {
                return;
            }
            methodsScanned++;
            try {
                codeReader.copy().visitInstructions(insn -> processInsn(mth, insn));
            } catch (Exception e) {
                scanErrors++;
            }
        }

        void processInsn(MethodNode mth, InsnData insn) {
            if (insn.getOpcode() == Opcode.UNKNOWN) {
                return;
            }
            switch (insn.getIndexType()) {
                case TYPE_REF:
                    insn.decode();
                    clsUse(mth, ArgType.parse(insn.getIndexAsType()));
                    break;

                case FIELD_REF: {
                    insn.decode();
                    IFieldRef ref = insn.getIndexAsField();
                    FieldNode fld = root.resolveField(FieldInfo.fromRef(root, ref));
                    if (fld != null) {
                        fieldUse(mth, fld);
                    }
                    break;
                }

                case METHOD_REF: {
                    insn.decode();
                    // invoke-polymorphic carries its real target in the payload.
                    ICustomPayload payload = insn.getPayload();
                    IMethodRef ref = payload != null
                            ? (IMethodRef) payload
                            : insn.getIndexAsMethod();
                    methodRefUse(mth, MethodInfo.fromRef(root, ref));
                    break;
                }

                case CALL_SITE: {
                    // invoke-custom: the bootstrap's 5th argument is the method
                    // handle the lambda actually lands on.
                    insn.decode();
                    ICallSite callSite = InsnDataUtils.getCallSite(insn);
                    IMethodHandle handle = InsnDataUtils.getMethodHandleAt(callSite, 4);
                    IMethodRef ref = handle != null ? handle.getMethodRef() : null;
                    if (ref != null) {
                        methodRefUse(mth, MethodInfo.fromRef(root, ref));
                    }
                    break;
                }

                default:
                    break;
            }
        }

        /**
         * A call that resolves to nothing in this dex still names a type, but
         * jadx records no class usage for it -- unresolvedMethodUse only files
         * the MethodInfo. Matched here so the two answers stay comparable.
         */
        void methodRefUse(MethodNode caller, MethodInfo callee) {
            MethodNode node = root.resolveMethod(callee);
            if (node != null) {
                methodUse(caller, node);
            }
        }

        void methodUse(MethodNode caller, MethodNode callee) {
            clsUse(caller, callee.getParentClass());
            add(methodCallers, callee, caller);
            clsUse(caller, callee.getReturnType());
            for (ArgType type : callee.getMethodInfo().getArgumentsTypes()) {
                clsUse(caller, type);
            }
        }

        void fieldUse(MethodNode user, FieldNode fld) {
            clsUse(user, fld.getParentClass());
            add(fieldUsers, fld, user);
            clsUse(user, fld.getType());
        }

        // -------- class usage, the widest of the four and the fiddliest

        void clsUse(MethodNode mth, ArgType type) {
            processType(type, cls -> clsUse(mth, cls));
        }

        void clsUse(ClassNode cls, ArgType type) {
            processType(type, used -> clsUse(cls, used));
        }

        /** Annotation owners arrive as ICodeNode and can be any of the three. */
        void clsUse(ICodeNode node, ArgType type) {
            switch (node.getAnnType()) {
                case CLASS:
                    processType(type, used -> clsUse((ClassNode) node, used));
                    break;
                case METHOD:
                    processType(type, used -> clsUse((MethodNode) node, used));
                    break;
                case FIELD:
                    ClassNode owner = ((FieldNode) node).getParentClass();
                    processType(type, used -> clsUse(owner, used));
                    break;
                default:
                    break;
            }
        }

        void clsUse(MethodNode mth, ClassNode used) {
            ClassNode parent = mth.getParentClass();
            clsUse(parent, used);
            // A method naming its own class is not a cross-reference, and jadx
            // does not record one -- it would put every class in its own list.
            if (parent != used) {
                add(classUsersInMth, used, mth);
            }
        }

        void clsUse(ClassNode use, ClassNode used) {
            add(classUsers, used, use);
            // An inner class's use is also its top-level file's use: that is the
            // granularity a caller reading "which classes reference this" wants,
            // and it is what jadx records.
            add(classUsers, used, use.getTopParentClass());
        }

        /**
         * Unwrap a type down to the classes it actually names.
         *
         * Copy of {@code UsageInfo.processType} minus its one side effect (a
         * GradleInfoStorage flag for apache-http-legacy, which is codegen's
         * business, not an index's): arrays reduce to their root element,
         * objects resolve and then recurse through generic arguments and
         * extends-bounds, and anything else that is a wildcard recurses into
         * its bound. ArgType.OBJECT itself is skipped -- java.lang.Object is
         * named by everything and referenced by nothing in particular.
         *
         * The wildcard arm is not decoration. Dropping it lost exactly one edge
         * on DivaApplication.apk and it was a real one: CoordinatorLayout's
         * {@code @DefaultBehavior} declares {@code Class<? extends Behavior>
         * value()}, so the only thing naming Behavior there is the bound inside
         * the wildcard, and an agent asking who references Behavior wants it.
         */
        void processType(ArgType type, Consumer<ClassNode> consumer) {
            if (type == null || type == ArgType.OBJECT) {
                return;
            }
            if (type.isArray()) {
                processType(type.getArrayRootElement(), consumer);
                return;
            }
            if (!type.isObject()) {
                return;
            }
            ClassNode cls = root.resolveClass(type);
            if (cls != null) {
                consumer.accept(cls);
            }
            List<ArgType> generics = type.getGenericTypes();
            if (generics != null) {
                for (ArgType g : generics) {
                    processType(g, consumer);
                }
            }
            List<ArgType> extend = type.getExtendTypes();
            if (extend != null) {
                for (ArgType e : extend) {
                    processType(e, consumer);
                }
            }
            ArgType wildcard = type.getWildcardType();
            if (wildcard != null) {
                processType(wildcard, consumer);
            }
        }

        // -------- annotations

        void processAnnotations(ICodeNode node) {
            processAnnotationAttr(node, node.get(JadxAttrType.ANNOTATION_LIST));
        }

        void processAnnotationAttr(ICodeNode node, AnnotationsAttr attr) {
            if (attr == null || attr.isEmpty()) {
                return;
            }
            for (IAnnotation annotation : attr.getList()) {
                processAnnotation(node, annotation);
            }
        }

        void processAnnotation(ICodeNode node, IAnnotation annotation) {
            clsUse(node, ArgType.parse(annotation.getAnnotationClass()));
            for (EncodedValue value : annotation.getValues().values()) {
                processAnnotationValue(node, value);
            }
        }

        void processAnnotationValue(ICodeNode node, EncodedValue encodedValue) {
            Object value = encodedValue.getValue();
            EncodedType type = encodedValue.getType();
            if (type == EncodedType.ENCODED_TYPE) {
                clsUse(node, ArgType.parse((String) value));
            } else if (type == EncodedType.ENCODED_ENUM || type == EncodedType.ENCODED_FIELD) {
                FieldInfo info = null;
                if (value instanceof IFieldRef) {
                    info = FieldInfo.fromRef(root, (IFieldRef) value);
                } else if (value instanceof FieldInfo) {
                    info = (FieldInfo) value;
                }
                if (info != null) {
                    fieldUse(node, info);
                }
            } else if (type == EncodedType.ENCODED_ARRAY) {
                for (Object item : (List<?>) value) {
                    processAnnotationValue(node, (EncodedValue) item);
                }
            } else if (type == EncodedType.ENCODED_ANNOTATION) {
                processAnnotation(node, (IAnnotation) value);
            }
        }

        /**
         * An enum constant or field named inside an annotation. Only a method
         * owner produces a field xref -- a class owner records the declaring
         * type and nothing finer, because there is no method to point a tracer
         * at, and that is jadx's own rule.
         */
        void fieldUse(ICodeNode node, FieldInfo info) {
            FieldNode fld = root.resolveField(info);
            if (fld == null) {
                return;
            }
            switch (node.getAnnType()) {
                case CLASS:
                    clsUse((ClassNode) node, fld.getParentClass());
                    break;
                case METHOD:
                    fieldUse((MethodNode) node, fld);
                    break;
                default:
                    break;
            }
        }

        /**
         * One reverse edge, self-edges dropped.
         *
         * The drop is not a nicety, it is parity: jadx's own {@code UseSet.add}
         * opens with {@code if (key == value) return}, so a recursive method is
         * absent from its own caller list and a class is absent from its own
         * user list. Reference identity, as there, not equals -- the node
         * objects are canonical and that is the comparison being mirrored.
         *
         * It is also the right answer for the tools: "who calls this" answered
         * with "itself" tells a tracer nothing it did not already know, and
         * get_callees reports recursion separately under "callsSelf" for exactly
         * that reason.
         */
        private static <K, V> void add(Map<K, List<V>> map, K key, V value) {
            if (key == value) {
                return;
            }
            List<V> list = map.get(key);
            if (list == null) {
                // Most keys have one or two users; the default 10 is mostly slack.
                list = new ArrayList<>(2);
                map.put(key, list);
            }
            list.add(value);
        }
    }
}

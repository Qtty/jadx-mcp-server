package com.example.jadxmcpserver.core;

import com.example.jadxmcpserver.model.ExportedComponent;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
// JavaMethod is deliberately absent: it is the decompiled view of a method, and
// reading names from it was the defect getMethodsOfClass was fixed for. Method
// identity comes from the dex tree (ClassNode.getMethods) and PinnedMethodNames.
import jadx.api.ResourceFile;
import jadx.api.ICodeInfo;
import jadx.api.utils.CodeUtils;
// Raw dex reading: the bytecode a method actually holds, which no decompiler
// pass rewrites. See scanCallees.
import jadx.api.plugins.input.data.ICallSite;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodHandle;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnData;
import jadx.api.plugins.input.insns.Opcode;
import jadx.api.plugins.input.insns.custom.ICustomPayload;
import jadx.core.utils.input.InsnDataUtils;
// Dex-tree types: reading names from these avoids the decompilation that the
// jadx.api.* wrappers trigger on access.
import jadx.core.clsp.ClspClass;
import jadx.core.clsp.ClspGraph;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.xmlgen.ResContainer;
import jadx.zip.IZipEntry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Core JADX APK Analyzer - Provides analysis functionality without UI
 * This class handles all APK analysis operations including:
 * - APK loading and decompilation
 * - Manifest parsing and component extraction
 * - Class and method analysis
 * - Call graph generation
 * - String and code search functionality
 */
public class JadxAnalyzerCore {

    private static final java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger(JadxAnalyzerCore.class.getName());

    /**
     * How a response says it is not the whole answer.
     *
     * Three things can happen to a wide request -- it can be refused, it can be
     * cut short, or it can be answered in full at a size that is itself the
     * problem -- and there used to be three unrelated ways to find out. One of
     * them was reading the daemon's log file, which the party paying for the
     * response, an agent's context window, cannot do.
     *
     * One rule now, on every tool that can do any of the three:
     *
     *   - the notice is IN THE RESPONSE, never only in the log
     *   - it is a string beginning {@value #REJECTED_MARKER_PREFIX},
     *     {@value #TRUNCATION_MARKER_PREFIX} or {@value #OVERSIZED_MARKER_PREFIX},
     *     followed by ": " and a sentence
     *   - it names the real total, so what is missing is a number rather than a
     *     guess
     *   - in a list it is a trailing element; in a map it is the value -- a
     *     one-element list of the same string -- under the key of the same name
     *
     * So one check works everywhere: a string starting with '@' is bookkeeping,
     * and '@' is legal in neither a class name, a method name nor a catalogue
     * tag. Before this the shape depended on which tool you asked: a refusal was
     * a self-describing string in one search and a bare sentence under a key in
     * the other, so a caller needed two checks to ask one question.
     *
     * @see #marker(String, String)
     */
    public static final String TRUNCATION_MARKER_PREFIX = "@truncated";

    /** A query refused before it ran; see {@link #TRUNCATION_MARKER_PREFIX}. */
    public static final String REJECTED_MARKER_PREFIX = "@rejected";

    /**
     * An answer that covers more than one method, because the name it was asked
     * about names more than one.
     *
     * A bare method name cannot tell an overload set apart -- {@code
     * deliverResult(Cursor)} and {@code deliverResult(Object)} are two distinct
     * dex methods with one display name -- so a tool given a bare name answers
     * for all of them. That union is the only defensible answer to the question
     * as asked; reporting it AS a union is what was missing. Silently merged, a
     * caller reads a caller list as "these call the method I meant" when some of
     * them call the other overload, and nothing in the response contradicts
     * that.
     *
     * Counted on the two reference APKs: 803 such names over 1,838 methods on
     * DivaApplication.apk, 50,387 over 131,929 on a large production APK.
     *
     * The notice carries the count and the signatures, and every tool that emits
     * it also accepts a {@code signature} argument to pick one; see
     * {@link #resolveMethods}. Same '@' rule as the other markers, so one check
     * still finds all bookkeeping: in a map it is a key, in a list a trailing
     * one-key entry.
     */
    public static final String AMBIGUOUS_MARKER_PREFIX = "@ambiguous";

    /**
     * An answer whose size is the problem; see {@link #TRUNCATION_MARKER_PREFIX}.
     *
     * It answers a different question from {@value #TRUNCATION_MARKER_PREFIX}:
     * "is what you got a file dump", not "are you missing some". The two are
     * therefore not alternatives, and reading them as alternatives is what left
     * five holes in this guard over as many rounds:
     *
     *   - limit=0 / maxBytes=0. The deliberate act, and the case this marker was
     *     built for -- deliberate or not, 22 MB still lands in a context window.
     *   - a cap too wide to bite. getClassSource with maxBytes above the answer's
     *     size is not truncated and is still a dump; see
     *     {@link #withOversizedNotice}.
     *   - findSinkCallSites at the default limit, whose response is many lists:
     *     every sink can be cut and the total still cross the line.
     *   - THE TRUNCATION BRANCH ITSELF, on every count-capped tool. This was the
     *     one that stood longest, because the sentence that used to be here --
     *     "on every other tool a truncated answer is by construction under the
     *     line" -- is true only at the DEFAULT cap and was being read as a
     *     property of truncation. A cap is on the COUNT. A caller who names an
     *     explicit limit chooses the count and nothing chooses the bytes:
     *     measured on a large production APK, searchMethodByName("in", 100000) is 18,225,571
     *     bytes, getXrefsToClass(Intrinsics, 50000) is 9,518,250, and
     *     getAllClasses(null, 100000) is 6,084,879 -- each of them cut, each of
     *     them wearing {@value #TRUNCATION_MARKER_PREFIX} alone.
     *   - THE SUM OF SEVERAL LISTS, on getCallees and getOverrides. Both guard
     *     each of their two lists and neither weighed the response, so two lists
     *     just under the line are a response twice over it with nothing said.
     *     Unlike the four above this one is not reachable on either reference
     *     APK -- the widest measured whole answer is get_overrides on
     *     java.lang.Runnable.run at 470,503 bytes, 45% of the line -- and it is
     *     closed for uniformity rather than as a repair; see
     *     {@link #wholeResponseNotice}.
     *
     * So the rule now has no exception list: EVERY branch of every count-capped
     * guard weighs what it is handing back, every multi-list response is weighed
     * whole, and the two markers coexist wherever both facts are true. See
     * {@link #guardedNames}, {@link #guardedRefs}, {@link #cappedComponents},
     * {@link #getAllClasses}, {@link #searchClassesByKeyword},
     * {@link #searchMethodByName}, {@link #findSinkCallSites} and
     * {@link #wholeResponseNotice}.
     *
     * The BYTE-capped tools are the one family where a cut answer still carries
     * {@value #TRUNCATION_MARKER_PREFIX} alone, and that is not an exception:
     * their cap is denominated in the same unit the size question is asked in,
     * so {@link #textTruncationMarker} names maxBytes and the caller who set it
     * has been told the size of what they got. It is the count caps that hide a
     * size, because a count is not one. See {@link #guardedText}.
     */
    public static final String OVERSIZED_MARKER_PREFIX = "@oversized";

    /**
     * An answer that was a table of contents because the file did not fit.
     *
     * It answers a different question from the other three. {@value
     * #TRUNCATION_MARKER_PREFIX} says "some of this list is missing";
     * {@value #OVERSIZED_MARKER_PREFIX} says "what you got is a file dump";
     * this one says "you asked for a body and got the members, because the
     * body did not fit the budget you named".
     *
     * Only get_class_source carries it, and the reason is that truncating a
     * decompiled class is the one cut that answers nothing. Every other
     * byte-capped tool returns a list or a stream where the first N bytes are
     * a usable prefix of the whole -- resource bytes are still resource bytes,
     * smali is still smali. Cutting Java at a byte offset yields the top of a
     * file: the imports and two of forty methods, with no way to tell whether
     * the method you were tracing was among them. An outline names all forty
     * and tells you how to fetch the one you want.
     *
     * The cost this exists to remove was measured, not guessed. On a large
     * analysis run get_class_source was the dominant tool payload -- a majority
     * of every byte the run pulled -- at a median of 4,878 characters but a p90
     * of 25,824. Each of those bytes then sat in the caller's context and was
     * billed again on each of its remaining turns, and a long-running caller
     * takes many turns, so that re-read is the term that dominates the bill.
     * The budget attacks it directly.
     */
    public static final String OUTLINE_MARKER_PREFIX = "@outline";

    /**
     * The budget get_class_source applies when the caller names none.
     *
     * NOT a second runaway guard. {@link #DEFAULT_MAX_BYTES} is still that, and
     * still 1 MB; this is two orders of magnitude tighter because it is guarding
     * something else. A runaway guard asks "is this response absurd". This asks
     * "is this response worth what the caller will pay to carry it", and for a
     * decompiled class the answer is usually no: the caller wanted one method.
     *
     * 8192 is a measurement. Sweeping a large run's real get_class_source calls
     * against candidate caps, replacing each over-budget answer with an outline
     * costed at the median get_methods_of_class response:
     *
     *   cap      degraded   payload after   saving
     *   4,000         58%       5,978,926      82%
     *   6,000         43%       8,165,976      76%
     *   8,192         33%      10,180,131      70%
     *  12,000         22%      13,939,962      59%
     *  16,000         15%      17,319,416      49%
     *  24,000         11%      20,106,881      41%
     *
     * 4,000 saves more and is the wrong choice: degrading 58% of calls turns one
     * fetch into two often enough to spend the saving back in turns, and turns
     * are the term that is squared. 8192 takes 70% of the payload off while
     * leaving two calls in three untouched.
     *
     * Override per call with maxBytes; 0 or negative is uncapped and returns the
     * whole file, which is the escape hatch for the case an outline cannot serve
     * -- reading how several methods relate through fields and static state.
     */
    public static final int DEFAULT_SOFT_BYTES = 8192;

    /**
     * The cap a list-returning tool applies when the caller named none.
     *
     * A character floor on the query is not a size guard on the answer, which is
     * what the last attempt at this got wrong: with a two-character minimum in
     * place, search_method_by_name("in") still returned 22.3 MB, HTTP 200, and
     * nothing in the body said so. The floor rejects a query that can only be a
     * sweep; it does nothing about a real query that happens to be wide.
     *
     * 1000 is a measurement, not a taste. Measured on a large production APK,
     * on the order of 141k classes and 790k methods, it is the smallest round cap that
     * leaves the queries an analysis actually makes whole while cutting the ones
     * that are really "dump the APK":
     *
     *   whole at 1000            search_classes_by_keyword("WebView")     769
     *                            search_classes_by_keyword("Router")      735
     *                            search_classes_by_keyword("Bridge")      690
     *                            search_method_by_name("shouldOverrideUrlLoading") 73
     *   cut at 1000              search_classes_by_keyword("Service")    6090
     *                            search_method_by_name("onCreate")       2550
     *
     * A cap of 500 would cut the first three, which are a WebView-focused
     * analysis's own opening moves. The cost at the other end is bounded, and the bound is a
     * sweep rather than a guess -- but it is the sweep's bound, not the server's,
     * and this comment has twice said otherwise. WHAT WAS ACTUALLY MEASURED, on
     * a large production APK, in real serialised bytes: all 676 two-character method queries,
     * and 520 three-character ones formed by extending the ten widest pairs at
     * each end. The widest of those 1,196 is search_method_by_name("et") at
     * 429,418 bytes -- against 17,144,938 for the same call uncapped, and 41% of
     * {@link #OVERSIZED_RESPONSE_BYTES}, the line this file already calls a file
     * dump. Behind it, in order: "bda" and "mbd" 421,310, "amb" 419,839, "bd"
     * 419,553, "lam" 414,814, "yte" 399,139, "yt" 398,320, "se" 349,339. ("se"
     * stood here for three rounds as the maximum. It is ninth.)
     *
     * That is a bound on SHORT METHOD-NAME SEARCHES and on nothing else, which
     * is all a sweep of short method-name searches can establish. It is not the
     * widest response this server produces at its defaults, and the counterexample
     * is in this same file: get_class_source("com.example.app.R.id") at the
     * default {@link #DEFAULT_MAX_BYTES} is 1,048,578 bytes, 2.4 times wider.
     * Across the thirteen tools the LIST cap applies to, each on the widest
     * target that exists for it on this APK, "et" is the widest -- next are
     * get_xrefs_to_field(kotlin.Unit.INSTANCE) at 220,050 and
     * find_sink_call_sites() at 214,944 -- and that is the claim this number
     * supports.
     *
     * It is a DEFAULT, not a maximum: limit=0 still means uncapped, so a caller
     * that genuinely wants the whole list loses nothing but has to say so, and
     * gets an {@value #OVERSIZED_MARKER_PREFIX} notice when the whole list is a
     * file dump. Applied in JadxApkAnalyzerAPI, where a null limit means "the
     * caller did not choose", the same place DEFAULT_CALL_SITE_LIMIT is applied.
     * The core keeps 0-means-uncapped as its primitive, so the CLI and the
     * in-process callers that ask for everything on purpose are unaffected.
     *
     * @see #DEFAULT_MAX_BYTES the same bargain for a tool that returns one string
     */
    public static final int DEFAULT_RESULT_LIMIT = 1000;

    /**
     * The budget a tool that returns ONE STRING applies when the caller named none.
     *
     * The list tools were capped over three rounds and the string tools were
     * not, on an unexamined assumption that one class or one file is a narrow
     * scope. It is not. Measured on a large production APK through the MCP layer, as bytes
     * of tool result at maxBytes=0:
     *
     *   getResourceFile   lib/arm64-v8a/libapp.so        150,606,754 bytes
     *   getResourceFile   assets/audience_network.dex     11,879,027
     *   getClassSource    com.example.app.R.id           6,157,819
     *   getSmaliOfClass   kotlin...ArraysKt___ArraysKt     3,605,169
     *
     * The first two need no opt-in and this server built the path to them:
     * getAllResourceFileNames' own docstring says "Feed any entry to
     * get_resource_file", and its DEFAULT capped 1000-name listing contains
     * assets/audience_network.dex. Following our own instructions, with every
     * parameter left alone, reached the widest response the server could produce.
     *
     * Deliberately the same 1 MB as {@link #OVERSIZED_RESPONSE_BYTES}: that is
     * already the line this file calls a file dump, and a second number would be
     * a second rule. It is a measurement, not a taste. Re-taken 2026-08-08 in
     * the unit the budget now spends, over 500 classes on a large production APK with a fixed
     * seed -- 250 drawn from the WebView/Router/Scheme/Bridge/Activity
     * names an analysis actually opens, 250 uniformly at random:
     *
     *   analysis-shaped    median 9,796   p99 133,025   widest 173,703    0 over
     *   uniform random median 6,000   p99 613,325   widest 1,418,542  1 over
     *
     * So it does not bite on a class-source lookup an analysis makes -- none of the
     * 250 came close -- but "NOT ONE of 500 exceeded it", which is what stood
     * here, is false. One uniformly-drawn class does:
     * com.google.protobuf.DescriptorProtos.MessageOptions.C284551, 1,418,542
     * bytes, a generated protobuf builder. That is the same shape as the other
     * two things this bites on, the generated R table and binary assets, and it
     * is what the marker is for.
     *
     * Same clamp as {@link #DEFAULT_RESULT_LIMIT}: 0 or any negative number
     * means uncapped, so the whole file is one explicit argument away -- and,
     * since this round, an uncapped answer over the line announces itself
     * exactly as an uncapped list does. Applied in JadxApkAnalyzerAPI, where a
     * null means "the caller did not choose".
     *
     * The budget is spent in {@link #jsonCost}, so it bounds the ANSWER at this
     * many bytes. The tool result is the answer inside a JSON string, so it is
     * this plus the two quote characters: measured through the MCP layer, the
     * widest default response any of these three tools produces on a large production APK is
     * 1,048,578 bytes. Stated because the previous version of this comment
     * claimed a 1 MB bound on a response that measured 1,853,835.
     *
     * @see #guardedText
     */
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    /**
     * The shortest query searchClassesByKeyword and searchMethodByName will run.
     *
     * Both match on substring, so a shorter query is not a narrower question --
     * it is a wider one. Measured on a large production APK, on the order of 141k classes and 790k
     * methods:
     *
     *   methodName ""    every method in the APK      58.9 MB
     *   methodName "a"                                26.5 MB
     *   keyword    "a"   134,834 of 141,461 classes    7.5 MB
     *
     * That last one is as wide as the bare getAllClasses dump these tools are
     * documented as the narrow alternative to.
     *
     * Two rather than three, deliberately. A two-character method name is a real
     * thing in obfuscated code -- 10,855 of that APK's methods carry a
     * two-character alias -- and refusing it would break a legitimate search.
     * One character is different in kind: loadApk sets DeobfuscationMinLength(2),
     * so no alias in a loaded tree is one character long, and the shortest full
     * class name measured is five ("b3.a0"). A one-character query can therefore
     * never be a name. It can only be a sweep.
     *
     * This is a floor on the query, not a cap on the answer -- a two-character
     * query can still be wide ("co" matches 121,330 of those classes). The limit
     * parameter and warnIfOversized are what handle that.
     */
    public static final int MIN_QUERY_LENGTH = 2;

    /**
     * A response bigger than this is a file dump and says so in the log.
     *
     * Package-private rather than private so a test can assert against the line
     * itself instead of restating 1048576, which is how a constant and the test
     * that checks it drift apart.
     */
    static final long OVERSIZED_RESPONSE_BYTES = 1024L * 1024L;

    private JadxDecompiler jadx;
    private String apkPath;
    private List<ExportedComponent> exportedComponents;
    private String manifestContent;
    private String packageName;

    /**
     * Who references what, read out of the dex. Null until the first caller
     * needs it; see {@link #reverseIndex()}.
     */
    private volatile DexReverseIndex reverseIndex;
    private final Object reverseIndexLock = new Object();

    /**
     * The name every method had at load, which is the name every tool reports.
     *
     * Built eagerly in {@link #loadApk()} and never rebuilt: its whole value is
     * that it was taken while the tree was pristine. See {@link PinnedMethodNames}.
     */
    private PinnedMethodNames pinnedNames;

    /**
     * The name every field had at load, which is the name every tool reports.
     * Same discipline, same reason; see {@link PinnedFieldNames}.
     */
    private PinnedFieldNames pinnedFields;

    /**
     * Alias full names that denote more than one dex class.
     *
     * {@code ClassInfo.getAliasFullName()} joins an inner class to its outer one
     * with a '.', the same character that separates a package from a class, so
     * an inner class {@code dd$qtm} and a top-level class {@code dd.qtm} in
     * package {@code com.bytedance.adsdk.qtm} produce one identical string.
     * jadx's own lookup is a {@code findFirst()} over that string, so one of the
     * pair answers to the name and the other is reachable by no spelling at all.
     *
     * Measured: 27 such names on a large production APK, each covering exactly 2 classes,
     * every pair sharing an input dex; 0 on DivaApplication.apk. In all 27 the
     * class jadx picked was the empty synthetic stub -- 0 methods, 0 fields --
     * and the one it hid was the real class, up to 21 methods. find_sink_call_
     * sites reports a call site under that name, and feeding it back used to
     * return a confident answer about the wrong class.
     *
     * Stable across a restart, so it is a stable wrong answer, which is worse
     * than a flaky one: nothing ever signals it.
     *
     * This set is the whole fix's pivot. A class in it is REPORTED under
     * {@code makeAliasRawFullName()} instead -- same alias vocabulary, but '$'
     * for nesting, which is unique by construction because a package name
     * cannot contain one. So the server stops handing out a name that denotes
     * two classes, and both classes become reachable.
     *
     * Kept as the ambiguous names alone rather than a full name-to-class map:
     * it is 27 entries instead of 141,461, and the common case then costs one
     * hash lookup that misses.
     *
     * @see #classDisplayName(ClassNode)
     * @see #resolveClassNodes(String)
     */
    private Set<String> ambiguousClassNames = Collections.emptySet();

    /**
     * Every class by its unique spelling -- {@code makeAliasRawFullName()},
     * which is the alias vocabulary with '$' between an outer class and an
     * inner one.
     *
     * Built at load because the alternative is rebuilding it per lookup. The
     * first version of this fix scanned {@code getRoot().getClasses()} and
     * called {@code makeAliasRawFullName()} on each, which allocates a string
     * and recurses through the parent chain -- 141,461 of those per class
     * lookup, on the code path every single tool goes through. It was slow
     * enough to notice on a large production APK before any measurement was needed.
     */
    private Map<String, ClassNode> classesByUniqueName = Collections.emptyMap();

    public JadxAnalyzerCore(String apkPath) {
        this.apkPath = apkPath;
    }
    
    /**
     * Load and initialize the APK for analysis
     * @return true if loading was successful, false otherwise
     */
    public boolean loadApk() {
        File apkFile = new File(apkPath);
        
        if (!apkFile.exists()) {
            throw new RuntimeException("APK file not found: " + apkPath);
        }
        
        // Close previous instance if exists
        close();
        
        // Configure JADX
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(apkFile);
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);
        
        try {
            jadx = new JadxDecompiler(jadxArgs);
            jadx.load();

            // BEFORE anything else touches the tree. Every query decompiles
            // something, and decompilation is what moves aliases, so the
            // snapshot is only worth taking while nothing has run yet.
            pinnedNames = PinnedMethodNames.pin(jadx.getRoot());
            pinnedFields = PinnedFieldNames.pin(jadx.getRoot());
            ambiguousClassNames = findAmbiguousClassNames(jadx.getRoot());
            classesByUniqueName = indexByUniqueName(jadx.getRoot());

            // Load manifest
            loadManifest();

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error loading APK: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get basic APK information
     */
    public Map<String, Object> getApkInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("apkPath", apkPath);
        info.put("packageName", packageName);
        // Counts what getAllClasses lists, inner classes included -- not
        // getClasses(), which sees only top-level ones.
        info.put("totalClasses", jadx != null ? jadx.getRoot().getClasses().size() : 0);
        // The same count get_exported_components returns, from the same parse.
        info.put("exportedComponents", getExportedComponents().size());
        info.put("mainActivity", getMainActivityClass());
        return info;
    }
    
    /**
     * The decoded AndroidManifest.xml.
     *
     * Under the same fixed budget as the other string tools, and it has never
     * come near it -- 250,183 bytes on the largest APK measured, a quarter of
     * DEFAULT_MAX_BYTES. Guarded anyway for the reason the hierarchy lists are:
     * "no response exceeds the budget silently" is meant to be a property of the
     * server, and "it has never been big on an APK we tried" is not one.
     *
     * No maxBytes parameter. A manifest is bounded by what an app can plausibly
     * declare, and it is the one document an analysis has no narrower substitute for
     * -- so there is nothing useful for a caller to tune, and adding a knob
     * would imply otherwise.
     */
    public String getAndroidManifest() {
        return guardedText(manifestContent, DEFAULT_MAX_BYTES, "getAndroidManifest",
                "AndroidManifest.xml",
                "get_deeplink_components and get_exported_components read the"
                + " same declarations as structured data.");
    }
    
    /**
     * Get the package name from manifest
     */
    public String getPackageName() {
        return packageName;
    }
    
    /**
     * Get main activity class from AndroidManifest.xml
     */
    public String getMainActivityClass() {
        if (manifestContent == null) {
            return null;
        }
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            
            NodeList activities = doc.getElementsByTagName("activity");
            
            for (int i = 0; i < activities.getLength(); i++) {
                Element activity = (Element) activities.item(i);
                NodeList intentFilters = activity.getElementsByTagName("intent-filter");
                
                for (int j = 0; j < intentFilters.getLength(); j++) {
                    Element intentFilter = (Element) intentFilters.item(j);
                    NodeList actions = intentFilter.getElementsByTagName("action");
                    NodeList categories = intentFilter.getElementsByTagName("category");
                    
                    boolean hasMainAction = false;
                    boolean hasLauncherCategory = false;
                    
                    for (int k = 0; k < actions.getLength(); k++) {
                        Element action = (Element) actions.item(k);
                        if ("android.intent.action.MAIN".equals(action.getAttribute("android:name"))) {
                            hasMainAction = true;
                        }
                    }
                    
                    for (int k = 0; k < categories.getLength(); k++) {
                        Element category = (Element) categories.item(k);
                        if ("android.intent.category.LAUNCHER".equals(category.getAttribute("android:name"))) {
                            hasLauncherCategory = true;
                        }
                    }
                    
                    if (hasMainAction && hasLauncherCategory) {
                        String activityName = activity.getAttribute("android:name");
                        if (activityName.startsWith(".")) {
                            activityName = packageName + activityName;
                        } else if (!activityName.contains(".")) {
                            activityName = packageName + "." + activityName;
                        }
                        return activityName;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error finding main activity: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Get all classes in the APK.
     *
     * Includes inner classes. getClasses() returns only top-level ones -- 651 of
     * DIVA's 1791 -- so enumerating it would advertise a third of the APK while
     * the lookups below can resolve all of it.
     *
     * On the largest APK here that is 141,461 names and 7.7 MB. The complete
     * list is still what a no-argument call returns, because the CLI and the
     * agent files already depend on it, but see the overload for the narrower
     * ways to ask.
     */
    public List<String> getAllClasses() {
        return getAllClasses(null, 0);
    }

    /**
     * Class names, optionally narrowed.
     *
     * @param packagePrefix keep only names starting with this, case-insensitively;
     *                      null or empty keeps everything. It is matched against
     *                      the whole alias name, so it can be a package, part of
     *                      one, or the start of a class name.
     * @param limit         at most this many names. <b>0 or less means uncapped</b>
     *                      -- a non-positive limit is clamped to "no cap", not
     *                      rejected, so limit=-5 returns the whole APK. That is
     *                      the same rule the two searches and findSinkCallSites
     *                      use; it is stated rather than left to the reader
     *                      because a caller who computes a limit and gets 0 or a
     *                      negative out of the arithmetic gets the 7.7 MB answer,
     *                      and needs to know that here rather than from the size
     *                      of the response. A list that was cut short carries one
     *                      extra final element starting with
     *                      {@value #TRUNCATION_MARKER_PREFIX} which names the
     *                      real total -- a silent cap reads to a caller as "this
     *                      is the whole APK", which is worse than a big response.
     */
    public List<String> getAllClasses(String packagePrefix, int limit) {
        checkLoaded();

        String prefix = packagePrefix == null || packagePrefix.isEmpty()
                ? null
                : packagePrefix.toLowerCase();

        List<String> classNames = new ArrayList<>();
        for (ClassNode cls : jadx.getRoot().getClasses()) {
            String name = classDisplayName(cls);
            if (prefix == null || name.toLowerCase().startsWith(prefix)) {
                classNames.add(name);
            }
        }
        Collections.sort(classNames);

        String scope = prefix == null ? "no packagePrefix" : "packagePrefix=" + prefix;
        String advice = "Pass packagePrefix or limit, or use search-classes, which"
                + " answers the same question narrowly.";

        if (limit > 0 && classNames.size() > limit) {
            int total = classNames.size();
            List<String> capped = new ArrayList<>(classNames.subList(0, limit));
            capped.add(truncationMarker(limit, total, packagePrefix));
            // The cut branch weighs itself too; see guardedNames. limit=100000
            // on this APK is 6,084,879 bytes and used to say only that it had
            // cut 41,461 names off the end.
            String cutNotice = oversizedNotice("getAllClasses", limit, "class names",
                    jsonBytes(capped), scope, advice);
            if (cutNotice != null) {
                capped.add(cutNotice);
            }
            return capped;
        }

        String notice = oversizedNotice("getAllClasses", classNames.size(), "class names",
                jsonBytes(classNames), scope, advice);
        if (notice != null) {
            classNames.add(notice);
        }
        return classNames;
    }

    /**
     * One shape for every notice: the marker name, ": ", then a sentence.
     *
     * The prefix is repeated inside the text rather than left implied by the key
     * it sits under, because half of these arrive as a bare list element where
     * there is no key -- and a caller that had to know which case it was in
     * needed two checks to ask one question. See
     * {@link #TRUNCATION_MARKER_PREFIX}.
     */
    private static String marker(String prefix, String text) {
        return prefix + ": " + text;
    }

    private static String truncationMarker(int shown, int total, String packagePrefix) {
        String scope = packagePrefix == null || packagePrefix.isEmpty()
                ? ""
                : " under \"" + packagePrefix + "\"";
        return marker(TRUNCATION_MARKER_PREFIX, "showing " + shown + " of " + total
                + " classes" + scope + ". Raise limit, narrow packagePrefix, or use"
                + " search_classes_by_keyword for a name you already know part of.");
    }

    /**
     * What an uncapped, oversized response has to say about itself, or null when
     * it is neither.
     *
     * Roughly 55 bytes a name, so 141,461 class names is 7.7 MB into somebody's
     * context window and every method name is tens of MB.
     *
     * This used to be warnIfOversized and it only logged. The log is the right
     * place for an operator and the wrong place for the reader whose context is
     * being spent -- an agent cannot open a file on the daemon's disk, so the
     * one party the guard exists to protect was the one party it never reached.
     * It still logs, because an operator watching a daemon should see it too;
     * what changed is that the notice now also goes back with the data, in the
     * shape every other marker uses.
     *
     * @param tool   the call that produced this, so the notice names what to fix
     * @param count  how many things came back
     * @param unit   what they are, e.g. "class names"
     * @param bytes  the JSON estimate, from jsonBytes
     * @param scope  what was asked for, e.g. "keyword=ab"
     * @param advice the narrower call to make instead, written as a sentence:
     *               it is read both here, after a colon, and in the truncation
     *               notice, after a full stop, so it starts with a capital in
     *               both places rather than one convention per branch
     */
    private static String oversizedNotice(String tool, long count, String unit,
                                          long bytes, String scope, String advice) {
        if (bytes < OVERSIZED_RESPONSE_BYTES) {
            return null;
        }
        String text = tool + " returned " + count + " " + unit + " ("
                + (bytes / (1024 * 1024)) + " MB of JSON, " + scope
                + "). That is a file dump, not a lookup: " + advice;
        logger.warning(text);
        return marker(OVERSIZED_MARKER_PREFIX, text);
    }

    /** Rough JSON cost of a list of names: the name, its two quotes and a comma. */
    private static long jsonBytes(Collection<String> names) {
        long bytes = 0;
        for (String name : names) {
            bytes += jsonCostOf(name);
        }
        return bytes;
    }

    /**
     * The same estimate {@link #jsonBytes} and {@link #refBytes} make, for a
     * value of any shape.
     *
     * Those two are the flat cases -- a list of names, a list of
     * {className, methodName, signature} entries -- and they were the only
     * shapes the guard had to weigh until three tools needed weighing whose
     * answers nest: get_exported_components and get_deeplink_components return
     * objects with list-valued keys, and find_sink_call_sites returns a map of
     * lists. This is the general form of the same arithmetic, not a second
     * rule, and it is the one the other two now delegate to, so there is one
     * estimator to be right or wrong rather than three.
     *
     * A value's cost INCLUDES the comma that follows it, which is what makes
     * the two flat cases come out unchanged: a name costs length + 2 quotes +
     * comma, and a {@code "key":"value"} pair costs key + 2 quotes + colon +
     * value + 2 quotes + comma, which is the key + value + 6 refBytes charged.
     * The comma each element is charged for is what pays for the separator
     * between elements too, so there is deliberately no separate separator
     * term -- except for an EMPTY container, whose braces have no last element
     * to borrow a comma from and so cost 3 rather than 2. That one byte is why
     * the first version of this came out 51 bytes low on 47 deeplink
     * components: a component with no intent-filter data carries several empty
     * lists, and 51 of them were each a byte short. Verified against real
     * serialisation in the JSON-cost estimator.
     *
     * A STRING IS WEIGHED IN BYTES, NOT IN CHARACTERS, and this used to charge
     * {@code length() + 3}. Two errors came out of that, both one-sided in the
     * direction that makes a guard silent:
     *
     *   - a JSON escape. A quote or a newline is one char and two bytes. This
     *     is the -15 bytes the default sink sweep used to be out by; its
     *     truncation notices quote a sink name.
     *   - anything outside ASCII. A Latin-1 or CJK character is one char and
     *     two or three UTF-8 bytes, and the daemon writes UTF-8. On text that
     *     is entirely CJK the estimate came to 39% of the real size: a 1 MB cap
     *     passing a 2.7 MB response. Measured, neither reference APK contains
     *     any -- 0 of a large production APK's 141,461 class names, 0 of its 16,559 resource
     *     file names and 0 of the strings in its component records hold a
     *     non-ASCII character -- so this was wrong in principle rather than in
     *     practice. It is fixed anyway, because "no APK we have tried has a
     *     Chinese host in its manifest" is not a property of the server, and
     *     the exact arithmetic was already sitting in {@link #jsonCost(char)},
     *     used by the byte-capped string tools.
     *
     * So both families now weigh a string the same way, and the estimate is
     * exact rather than close: the only term left is the outermost value's
     * phantom trailing comma.
     */
    static long jsonCostOf(Object value) {
        if (value == null) {
            return 5;  // null,
        }
        if (value instanceof String) {
            return jsonCost((String) value) + 3;  // "…",
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                return 3;  // {},
            }
            long bytes = 2;  // { } -- the last pair's comma pays for the one after }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                bytes += jsonCost(String.valueOf(e.getKey())) + 3;  // "key":
                bytes += jsonCostOf(e.getValue());
            }
            return bytes;
        }
        if (value instanceof Collection) {
            Collection<?> items = (Collection<?>) value;
            if (items.isEmpty()) {
                return 3;  // [],
            }
            long bytes = 2;  // [ ]
            for (Object element : items) {
                bytes += jsonCostOf(element);
            }
            return bytes;
        }
        return String.valueOf(value).length() + 1;  // number, boolean: no quotes
    }

    /**
     * Why a substring search refused a query, or null if it did not.
     *
     * The refusal is returned as data rather than thrown, and it says why rather
     * than coming back empty. An empty answer reads as "this APK has no such
     * class", which is a different fact from "you asked for nothing" and is the
     * one a caller will act on -- searchClassesByKeyword used to return exactly
     * that for the empty keyword, and the silence was the bug next to the size.
     *
     * The {@value #REJECTED_MARKER_PREFIX} prefix is part of the string, built
     * here rather than by each caller. That is what the two searches disagreed
     * about: one returned "@rejected: keyword ..." as a list element and the
     * other put a bare "methodName ..." under an @rejected key, so the same fact
     * needed two different checks depending on which tool you had asked.
     *
     * @param param the caller's parameter name, so the message names their argument
     * @param query what they passed
     * @param cost  the tool-specific measurement of what running it would return
     */
    private static String shortQueryRejection(String param, String query, String cost) {
        if (query != null && query.strip().length() >= MIN_QUERY_LENGTH) {
            return null;
        }
        return marker(REJECTED_MARKER_PREFIX,
                param + " \"" + (query == null ? "" : query) + "\" is shorter than "
                + MIN_QUERY_LENGTH + " characters, and this search matches on"
                + " substring rather than on the whole name -- so it asks for "
                + cost + " Pass at least " + MIN_QUERY_LENGTH + " characters.");
    }

    /** What a one-character keyword would cost; see MIN_QUERY_LENGTH. */
    private static final String CLASS_SEARCH_COST =
            "most of the APK: on a 141,461-class target one character matched"
            + " 134,834 names and 7.5 MB, as wide as get_all_classes itself."
            + " Nothing is lost by refusing it -- the shortest class name"
            + " measured there is 5 characters, so one can never be a name."
            + " To enumerate a subtree, use get_all_classes with a packagePrefix.";

    /**
     * What an EMPTY methodName would cost; see MIN_QUERY_LENGTH.
     *
     * Kept apart from the one-character case, which is a different query with a
     * different reason and a different price. One string served both, so a
     * caller who passed "a" was told their query asked for "every method in the
     * APK ... because every name contains the empty string" -- which is the
     * reason the EMPTY query is refused, is not true of "a", and overstated the
     * cost by better than 2x. A refusal that misstates why is worse than a
     * terse one: it is the sentence the caller uses to decide what to try next.
     */
    private static final String EMPTY_METHOD_SEARCH_COST =
            "every method in the APK -- very many of them and 58.9 MB on a"
            + " 141,461-class target, because every name contains the empty"
            + " string.";

    /** What a ONE-CHARACTER methodName would cost; see MIN_QUERY_LENGTH. */
    private static final String ONE_CHAR_METHOD_SEARCH_COST =
            "a large fraction of the APK: one character measured 26.5 MB on a"
            + " 141,461-class target.";

    /** The half of the reason that is the same either way. */
    private static final String METHOD_SEARCH_FLOOR_REASON =
            " Nothing is lost by refusing it: loadApk deobfuscates with a"
            + " minimum name length of 2, so no method alias is 1 character.";

    /**
     * Get full source code of a given class.
     *
     * For an inner class this returns the enclosing top-level class's source.
     * jadx renders inner classes inside their parent, so getCode() on the inner
     * node yields "" -- which reads to a caller as "this class is empty" rather
     * than "look in the parent". Returning the parent's code gives the caller
     * the declaration they asked about, in context.
     */
    public String getClassSource(String className) {
        return getClassSource(className, 0);
    }

    /**
     * <h2>This answer depends on the request history, and the difference is not
     * always cosmetic</h2>
     *
     * Every other tool on this server has been made to answer out of the dex, so
     * that what has already been decompiled cannot change what it says. This one
     * cannot be: its answer IS the decompilation. jadx decompiles lazily into a
     * shared mutable tree, so the text a class produces depends on the state the
     * rest of the tree happened to be in when that class was generated.
     *
     * <h3>The worst case is a missing method, not a moved line</h3>
     *
     * On a large production APK, com.megvii.lv5.j3 comes back from one history with
     * m63755a(SurfaceTexture) as a real body -- a try block holding 21 calls --
     * and from another as
     *
     * <pre>
     *   throw new UnsupportedOperationException("Method not decompiled: ...");
     * </pre>
     *
     * 5,355 bytes against 2,898, same build, same APK, same class. A caller that
     * reads the second answer concludes the method does nothing. When a
     * decompiled body reads "Method not decompiled", that is a statement about
     * this process, not about the APK, and {@link #getSmaliOfMethod} answers
     * from the dex regardless.
     *
     * <h3>What is and is not reproducible</h3>
     *
     * Measured with {@link ClassSourceReproducibilityProbe}, fresh processes
     * throughout, both sides reading the same probe list in the same order.
     * Probes are every enum in the warmed range plus every 7th other class.
     *
     * <pre>
     *   history                              prefix   spread   random    Diva
     *                                        n=3,811  n=3,730  n=3,752   n=257
     *   identical call sequence (control)          0        0        0       0
     *   one get_all_classes(limit: 0) first        -        -        1       -
     *   ~4,940 classes decompiled first           22       35       25       0
     *   same probes, OPPOSITE order               53       64       39       0
     * </pre>
     *
     * Three warm-up SHAPES, because the shape is arbitrary and the figure this
     * javadoc used to publish depended on it: prefix is the first 3,000 of
     * RootNode.getClasses(), spread every 30th of the sorted name list, random a
     * seeded shuffle. The worst shape is 1.8x the best, and mere ORDER beats
     * every warm-up. The honest figure is therefore a range -- 0.6% to 1.7% of
     * classes on a 141,461-class APK, zero on a 1,791-class one -- and not the
     * single "4 of 2,810" that stood here, which was one arbitrary history's
     * result; the same shape re-measured is 22 of 3,811.
     *
     * Two things follow, and the second one contradicts what this javadoc said:
     *
     * <ul>
     *   <li>REPLAYING THE SAME CALLS DOES GET THE SAME TEXT BACK. Six fresh
     *       processes on one call sequence and four on another produced
     *       byte-identical source for all 3,752 probes.</li>
     *   <li>BUT THE HISTORY IS WIDER THAN "WHAT HAS BEEN DECOMPILED". Adding one
     *       {@link #getAllClasses} call -- which decompiles nothing and returns
     *       names -- in front of that sequence changes 1 of the 3,752, and it is
     *       the missing-method case above. Five processes with the call and six
     *       without, each group unanimous. It is NOT memory pressure (an
     *       explicit System.gc() and an equal-sized allocation burst change
     *       nothing), NOT stack depth (-Xss 1m through 64m are identical), and
     *       NOT the metadata reads (a hand-written walk over every class taking
     *       the same alias names, access flags and parent links changes
     *       nothing). So there is no short list of safe calls to publish. Treat
     *       every earlier call on this daemon as part of the history.</li>
     * </ul>
     *
     * <h2>What varies, worst first</h2>
     *
     * <ol>
     *   <li>WHETHER A CALL SITS INSIDE A try/catch -- and in the extreme,
     *       whether the method holding it decompiles at all. 1 of 25 and 1 of 64
     *       differing classes moved calls in or out of a protected region, up to
     *       21 call sites at once. This is the one that changes an analytical
     *       conclusion rather than a diff.</li>
     *   <li>{@code throws} clauses appear and disappear</li>
     *   <li>constants get hoisted into temporaries in {@code <clinit>} and in
     *       constructors ({@code new zzgd("INT64", 2, ...)} vs
     *       {@code final int i10 = 2; new zzgd("INT64", 2, ...)})</li>
     *   <li>local variable names shift ({@code i15} -&gt; {@code i16},
     *       {@code fieldSetClone} -&gt; {@code fieldSetM71325clone})</li>
     *   <li>{@code JADX INFO} / {@code JADX WARN} comments come and go</li>
     *   <li>and therefore LINE NUMBERS move -- a hoisted temporary shifts every
     *       line after it</li>
     * </ol>
     *
     * WHICH METHODS ARE CALLED does not vary: the multiset of called method
     * names is identical in 22 of 22, 35 of 35 and 24 of 25 diffs, the one
     * exception being the class whose method stopped decompiling. DECLARED
     * MEMBER NAMES do not vary either. That is the identity property the rest of
     * the server guarantees, and it survives here. A recorded reference should
     * name the class and the member, which are stable, and not the line, which
     * is not -- {@link #getMethodSource} exists so a caller never has to count
     * lines.
     *
     * <h2>Why it is not fixed here</h2>
     *
     * The mechanism is global, not local to the class asked about. Two halves,
     * both confirmed by disassembling jadx 1.5.6 -- and note that neither of
     * them explains the getAllClasses result above, which is the honest state of
     * the diagnosis rather than a closed one:
     *
     * <ul>
     *   <li>{@code MethodThrowsVisitor.visit} marks {@code MethodThrowsAttr}
     *       VISITED before it scans, and the scan returns immediately when
     *       {@code getBasicBlocks()} is null. It reaches ACROSS classes -- every
     *       INVOKE it meets makes it visit the callee -- so decompiling class A
     *       latches an empty throws set onto the methods of every unloaded class
     *       A calls.</li>
     *   <li>{@code ProcessClass.generateCode} runs the process passes over the
     *       target's DEPENDENCIES first, then over the target. A class reached
     *       only as somebody else's dependency therefore runs its own passes
     *       with ITS dependencies unprocessed -- and {@code process} then skips
     *       the pass loop entirely when the state is already PROCESS_COMPLETE,
     *       so that half-informed result is what its code is generated from.</li>
     * </ul>
     *
     * Two fixes were built and measured, and both are rejected:
     *
     * <ul>
     *   <li>Clearing {@code AType.METHOD_THROWS} on the target before generating
     *       moved 11 characters out of 87,449,943 and left both counts above
     *       unchanged at 4 and 41. It treats a symptom on the wrong node.</li>
     *   <li>A deep reload ({@code AFlag.CLASS_DEEP_RELOAD}) IS a fixed point --
     *       41 of 41 classes gave the same text after one reload and after two
     *       -- but not a history-independent one: 20 of those 41 still differed
     *       between a cold and a warm process. It also degrades the output,
     *       adding "Illegal instructions before constructor call" where the
     *       first pass printed a clean {@code super(...)} call.</li>
     * </ul>
     *
     * The only state that is a function of the APK alone is the fully-processed
     * one, which means decompiling everything before answering anything. That
     * was measured too, end to end: a large production APK's 89,861 top-level classes take
     * 103.8 s and retain 9,386 MB of heap (17.7 GB peak RSS) on top of a 14.2 s
     * load; DivaApplication.apk's 651 take 2.3 s and 172 MB. Turning a 14-second
     * load into a two-minute one and a 12 GB heap requirement, to remove drift
     * that touches under 2% of classes and no member name, is not a trade this
     * server should make silently -- so it is written down instead.
     *
     * @param maxBytes at most this many bytes of answer; 0 or less means
     *                 uncapped. It bites on generated code and effectively
     *                 nowhere else: com.example.app.R.id decompiles to
     *                 5,995,509 characters, 6,157,819 bytes of tool result,
     *                 every one of its 15,260 field declarations -- while its
     *                 FIELD LIST has been capped at 1000 since last round, so
     *                 the tool that had a guard and the tool that did not both
     *                 answered the same question about the same class.
     */
    public String getClassSource(String className, int maxBytes) {
        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        String source = codeOf(javaClass);

        // The one byte-capped tool that does not cut. See OUTLINE_MARKER_PREFIX
        // for why: the first N bytes of a resource are usable resource bytes,
        // but the first N bytes of a class are the imports and two of forty
        // methods, and the caller cannot tell whether the method they came for
        // was among them. An outline names all forty.
        if (maxBytes > 0 && jsonCost(source) > maxBytes) {
            return outlineOf(className, source, maxBytes);
        }
        return guardedText(source, maxBytes, "getClassSource",
                "\"" + className + "\"",
                "A class this size is generated -- a resource table or a Kotlin"
                + " stdlib facade. Use get_methods_of_class or"
                + " get_fields_of_class to see what it declares, or"
                + " get_method_by_name for the one member you want.");
    }

    /**
     * What a class declares, for when its source did not fit {@code maxBytes}.
     *
     * The outline is itself guarded, at the same budget, because a class big
     * enough to need one can also declare more members than the budget holds:
     * com.example.app.R.id has 15,260 fields, and an unguarded outline of it
     * would have re-created the dump it was built to avoid in a different
     * shape. That is why the member lists are fetched with an explicit cap
     * rather than uncapped -- and why what comes back can wear
     * {@value #TRUNCATION_MARKER_PREFIX} under the outline marker. Both facts
     * are true in that case, and both are said.
     */
    private String outlineOf(String className, String source, int maxBytes) {
        List<String> fields = getFieldsOfClass(className, DEFAULT_RESULT_LIMIT);
        List<String> methods = getMethodsOfClass(className, DEFAULT_RESULT_LIMIT);

        StringBuilder body = new StringBuilder();
        body.append("fields (").append(fields.size()).append("):\n");
        for (String field : fields) {
            body.append("  ").append(field).append('\n');
        }
        body.append("\nmethods (").append(methods.size()).append("):\n");
        for (String method : methods) {
            body.append("  ").append(method).append('\n');
        }

        String notice = marker(OUTLINE_MARKER_PREFIX, "getClassSource returned the"
                + " outline of \"" + className + "\" instead of its source: the"
                + " source is " + source.length() + " characters, over maxBytes="
                + maxBytes + ". What it declares follows. Call"
                + " get_method_by_name(className, methodName) for a body, or"
                + " get_class_source with maxBytes=0 for the whole file.")
                + "\n\n";

        return notice + guardedText(body.toString(), budgetLeftAfter(notice, maxBytes),
                "getClassSource", "the outline of \"" + className + "\"",
                "A class declaring this many members is generated -- an R table"
                + " or a Kotlin stdlib facade -- so the member you want is"
                + " better found with search_method_by_name.");
    }

    /** Source for a class, following inner classes up to whoever owns the code. */
    private static String codeOf(JavaClass javaClass) {
        String code = javaClass.getCode();
        if (code != null && !code.isEmpty()) {
            return code;
        }
        JavaClass parent = javaClass.getTopParentClass();
        return parent != null && parent != javaClass ? parent.getCode() : code;
    }
    
    /**
     * Search for classes by name (supports partial matching)
     */
    public List<JavaClass> searchClasses(String className) {
        List<JavaClass> matches = new ArrayList<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            String fullName = javaClass.getFullName();
            String simpleName = javaClass.getName();
            
            // Check for matches (exact, partial, or case-insensitive)
            if (fullName.equals(className) || 
                simpleName.equals(className) ||
                fullName.endsWith("." + className) ||
                fullName.toLowerCase().contains(className.toLowerCase()) ||
                simpleName.toLowerCase().contains(className.toLowerCase())) {
                matches.add(javaClass);
            }
        }
        
        return matches;
    }
    
    /**
     * Get detailed information about a class
     */
    public Map<String, Object> getClassDetails(String className) {
        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        
        Map<String, Object> details = new HashMap<>();
        details.put("fullName", javaClass.getFullName());
        details.put("package", javaClass.getPackage());
        List<String> methods = getMethodsOfClass(className);
        List<String> fields = getFieldsOfClass(className);
        // Both counted from the very list this record ships, not from
        // JavaClass. The view disagrees with the dex in both directions -- it
        // hides constructors and static initialisers, and it filters whichever
        // synthetic fields the decompiler happened to have flagged by the time
        // it was first asked -- and a record whose count contradicts its own
        // list is worse than either number alone.
        details.put("methodCount", methods.size());
        details.put("fieldCount", fields.size());
        details.put("methods", methods);
        details.put("fields", fields);
        details.put("sourceCode", javaClass.getCode());
        
        return details;
    }
    
    /**
     * List all methods in a specific class.
     *
     * Alias-aware, like the class-name side already is. JavaMethod.getFullName()
     * is MethodInfo.getFullName(), which is the RAW dex name -- on an obfuscated
     * target that is "h" where getCallees, getXrefsToMethod, getOverrides and
     * get_class_source all say "m30110h", two names with no spelling in common.
     * This is the tool an agent uses to find out what a class has before asking
     * anything about it, so the mismatch sat directly across the workflow: 85 of
     * 748 names in a 150-class sample of the a large production APK APK were rejected by the
     * next call. See resolveMethods.
     *
     * Read off the dex tree, not through JavaClass.getMethods(). That was the
     * last tool still going through the decompiled view, and the view is not the
     * dex: JavaClass.getMethods() calls load() -> loadLists(), which filters
     * AFlag.DONT_GENERATE -- a flag ClassModifier SETS as a class decompiles. So
     * it silently dropped methods the dex plainly declares, and the drop was
     * heaviest on exactly the kind other tools hand out most:
     *
     * <pre>
     *   DivaApplication.apk   19 methods over 200 sampled classes
     *                         (15 &lt;init&gt;, 3 &lt;clinit&gt;, 1 ordinary)
     *   a large production APK             77 methods over 300 sampled classes
     *                         (69 &lt;init&gt;, 4 &lt;clinit&gt;, 4 ordinary)
     * </pre>
     *
     * find_sink_call_sites reports 163 &lt;init&gt;/&lt;clinit&gt; call sites on
     * a large production APK uncapped (90 at the default limit), and get_method_by_name reads
     * them, so "&lt;init&gt;" was a name the rest of the server handed out and
     * traded in while this tool said the class had none.
     *
     * Two smaller things the dex tree fixes at the same time. Decompilation
     * RE-SORTS ClassNode.getMethods(), so the old answer changed order on its
     * own between two identical calls; sorting here makes the answer a set with
     * a stated order rather than whatever the last pass left behind. And the
     * names are pinned, so they do not move when a bridge is removed.
     *
     * Overloads are kept, not deduplicated: three constructors are three
     * methods, and a class listing that showed one would be the same silent
     * merge {@link #AMBIGUOUS_MARKER_PREFIX} exists to end. They are told apart
     * by their signature, so that is what distinguishes them here -- an entry is
     * the plain pinned name when the class has only one method of that name, and
     * carries the argument and return types when it has several. Both forms
     * resolve; see {@link #resolveMethods}.
     */
    public List<String> getMethodsOfClass(String className) {
        return getMethodsOfClass(className, 0);
    }

    /**
     * @param limit at most this many names; 0 or less means uncapped. A class is
     *              already a narrow scope, so this bites rarely -- but "rarely"
     *              is not "never": kotlin.collections.ArraysKt___ArraysKt
     *              declares 1,668 methods and 155,936 bytes of them on
     *              a large production APK, and nothing in the answer used to say so.
     */
    public List<String> getMethodsOfClass(String className, int limit) {
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            return new ArrayList<>();
        }

        // How many methods share each pinned name, so only the ambiguous ones
        // pay for the signature.
        Map<String, Integer> byName = new HashMap<>();
        for (MethodNode mth : cls.getMethods()) {
            byName.merge(pinnedNames.aliasOf(mth), 1, Integer::sum);
        }

        String clsName = classDisplayName(cls);
        List<String> methods = new ArrayList<>();
        for (MethodNode mth : cls.getMethods()) {
            String alias = pinnedNames.aliasOf(mth);
            methods.add(clsName + "." + (byName.get(alias) > 1
                    ? pinnedNames.signatureOf(mth)
                    : alias));
        }

        // Deterministic, for the reason every other list here is sorted: the
        // same question has to give the same answer in the same order, and the
        // order decompilation happens to leave is not one.
        Collections.sort(methods);
        return guardedNames(methods, limit, "getMethodsOfClass", "methods",
                "className=" + className, MEMBER_ADVICE);
    }

    /** What to do when a class declares more members than the cap allows. */
    private static final String MEMBER_ADVICE =
            "A class this wide is usually generated -- an R table or a Kotlin"
            + " stdlib facade -- so the name you want is better found with"
            + " search_method_by_name.";

    /** Every field a class declares, uncapped; see the overload. */
    public List<String> getFieldsOfClass(String className) {
        return getFieldsOfClass(className, 0);
    }

    /**
     * Fields declared by a class.
     *
     * Read off the dex tree, not through JavaClass.getFields(). That was the
     * last member listing still going through the decompiled view, and it fails
     * worse than the method one did, because two jadx details combine:
     *
     *   - {@code JavaClass.load()} is LATCHED. It sets {@code listsLoaded} and
     *     never rebuilds the list, so the answer is whatever the tree looked
     *     like the FIRST time anyone asked.
     *   - {@code ClassNode.decompile()} returns {@code ICodeInfo.EMPTY} without
     *     doing any work when {@code isInner()}. So asking about an inner class
     *     cold latches the PRISTINE list, while asking after its outer class
     *     decompiled latches one filtered by {@code AFlag.DONT_GENERATE}.
     *
     * Which of those happened is decided by whatever unrelated request the
     * daemon served first. Measured by running one probe twice, once asking
     * before the warm-up and once after:
     *
     * <pre>
     *   DivaApplication.apk    276 of   890 probed classes answered differently
     *   a large production APK            1,016 of 2,954, after only 3,000 of 89,861
     *                        top-level classes were decompiled
     * </pre>
     *
     * It moves in BOTH directions, so "decompile first" is not a fix:
     * NavigationMenuPresenter$1 LOSES its {@code this$0} once warm, while
     * FloatingActionButtonEclairMr1$1 GAINS a {@code val$listener}. Each answer
     * is stable across a restart, which makes it a confident wrong answer
     * rather than a flaky one.
     *
     * Sorted, for the reason every other list here is sorted -- 1,160 of those
     * 2,954 came back in whatever order the dex or the last pass left. The sort
     * key is the NAME, not the printed string, because the name is the part a
     * caller feeds back; same-named fields sort together by type, which is
     * exactly where a reader needs to see them side by side.
     *
     * Field aliases ARE pinned, the way method ones are, and the entries below
     * are spelled in the pinned vocabulary. This paragraph used to say they
     * were not, and cite "0 of 9,643 and 0 of 861,374 moved" for it -- a
     * measurement taken over a prefix of the tree, which on a large production APK holds
     * almost no enums, and which {@link PinnedFieldNames} retracts: warm every
     * enum first and 1,958 of 861,374 move. See {@link PinnedFieldNames}.
     *
     * @param limit at most this many; 0 or less means uncapped. This is the
     *              widest per-class list there is, because generated resource
     *              tables live here: com.example.app.R.id declares 15,260
     *              fields and 411,485 bytes of them on a large production APK, which used to
     *              arrive whole with nothing saying so.
     */
    public List<String> getFieldsOfClass(String className, int limit) {
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            return new ArrayList<>();
        }

        List<FieldNode> declared = declaredFields(cls);
        declared.sort(Comparator
                .comparing((FieldNode f) -> pinnedFields.aliasOf(f))
                .thenComparing(JadxAnalyzerCore::fieldTypeName));

        List<String> fields = new ArrayList<>();
        for (FieldNode fld : declared) {
            fields.add(fieldEntry(fld));
        }

        return guardedNames(fields, limit, "getFieldsOfClass", "fields",
                "className=" + className, MEMBER_ADVICE);
    }

    /**
     * The fields of a class as the DEX declares them -- the one field population
     * this server speaks about, on the way out and on the way in.
     *
     * Decompiling an enum makes jadx ADD FieldNodes for the constants it
     * recovers out of the {@code <clinit>}, so {@code ClassNode.getFields()}
     * grows -- 24 to 26 on NetworkConnectionInfo$MobileSubtype, measured on
     * a large production APK -- and the extras alias to names existing constants already
     * carry. Those are jadx's model of the enum, not the dex's field table.
     *
     * This filter used to be applied to the LISTING only. The resolver read
     * {@code getFields()} raw, so a name the server itself emitted resolved to
     * one field cold and to two once the class decompiled -- and the second
     * answer arrived with an {@code @ambiguous} notice whose advice, "pass the
     * whole type name entry to ask about one", could not be followed: both
     * candidates print the same type and the same name. One population, both
     * directions.
     *
     * @see PinnedFieldNames#wasDeclaredAtLoad
     */
    private List<FieldNode> declaredFields(ClassNode cls) {
        List<FieldNode> declared = new ArrayList<>();
        for (FieldNode fld : cls.getFields()) {
            if (pinnedFields.wasDeclaredAtLoad(fld)) {
                declared.add(fld);
            }
        }
        return declared;
    }

    /**
     * The one spelling of a field this server reports, "type name" -- the same
     * string JavaField produced, so no caller sees the move off the view.
     *
     * @see #resolveFields for the input side, which accepts this whole string
     */
    private String fieldEntry(FieldNode fld) {
        return fieldTypeName(fld) + " " + pinnedFields.aliasOf(fld);
    }

    /**
     * Alias-aware type name. JavaField.getType() ran the type through
     * {@code ArgType.tryToResolveClassAlias}, so a field of an obfuscated type
     * printed the deobfuscated one; reading FieldNode.getType() raw would
     * silently change every such entry.
     */
    private static String fieldTypeName(FieldNode fld) {
        return String.valueOf(ArgType.tryToResolveClassAlias(fld.root(), fld.getType()));
    }
    
    /**
     * Source of a method, cut out of the decompiled class by jadx's own metadata.
     *
     * This used to be a regex over the class text looking for a modifier keyword
     * followed by the name and an open paren. Three things were wrong with that,
     * and all three were silent:
     *
     *   - A constructor renders as the class name and a static initializer as a
     *     bare `static {`, so <init> and <clinit> -- names that getCallees,
     *     getXrefsToMethod and findSinkCallSites all hand out, 163 of them in an
     *     uncapped sink sweep of the a large production APK APK and 90 in a default one --
     *     matched nothing at all.
     *   - The name was matched as a substring, so a short name could land on a
     *     longer method that merely ended with it.
     *   - `[^{]+` cannot cross a brace, so an inner class's methods sat behind
     *     one and were unreachable under any name.
     *
     * MethodNode.getCodeStr() asks jadx where the method it just printed starts
     * and ends (CodeUtils.extractMethodCode over the class's code metadata), so
     * none of those cases are special any more.
     *
     * A name can name several methods -- overloads, and a class's constructors
     * are all called <init> -- and the caller has only the name. All of them come
     * back, separated by a blank line, in declaration order. Same choice
     * getCallees and getXrefsToMethod make for the same reason.
     *
     * A method the decompiler prints nothing for gets a note saying so rather
     * than a not-found error, because those are different answers and the caller
     * acts on them differently: the name was good, the body is simply not in the
     * Java. jadx drops implicit default constructors and folds a field-initialiser
     * <clinit> into the field declarations, so "no body" is the honest and common
     * answer for exactly those two.
     *
     * It is rarely the WHOLE answer for the ones that matter, which is a weaker
     * claim than the one that stood here and is the one that measures true. Over
     * the 163 <init>/<clinit> call sites in an uncapped default-catalogue sink
     * sweep of a large production APK, 160 come back with every block decompiled. Three do
     * not -- OmegaHelper, ReflectionHelper.RecordSupportedHelper and
     * AccessibilityViewEmbedder.ReflectionAccessors -- and in each of those the
     * class declares several constructors, the real one decompiles, and exactly
     * one sibling is the note. So the note is something a caller reading <init>
     * output has to expect ALONGSIDE a body, not only instead of one, and it is
     * why the ambiguity notice leading this response is load-bearing rather than
     * decorative. ("All 161 have real decompiled bodies" stood here for a round.
     * It was measured with a test that looked at the FRONT of the response,
     * where the ambiguity notice now sits, so the three it missed are the three
     * only a mixed answer could hide.)
     */
    public String getMethodByName(String className, String methodName) {
        return getMethodByName(className, methodName, null);
    }

    /**
     * @param signature optional, narrowing an overloaded name to one method;
     *                  see {@link #resolveMethods(ClassNode, String, String)}
     */
    public String getMethodByName(String className, String methodName, String signature) {
        JavaClass targetClass = findClass(className);
        if (targetClass == null) {
            return null;
        }

        List<MethodNode> targets =
                resolveMethods(targetClass.getClassNode(), methodName, signature);

        StringBuilder code = new StringBuilder();
        // This tool prints each overload's own text, so the merge was never
        // invisible here the way it was in a caller list -- but saying how many
        // bodies follow costs one line and spares the reader counting them.
        //
        // Bare, not behind a "// ". The comment prefix kept the result valid
        // Java and cost exactly the property the notice was for: the documented
        // check is "a string beginning '@'", and "// @ambiguous: ..." does not
        // begin with '@', so a caller walking the response found nothing. No
        // consumer compiles this text. See guardedText.
        if (targets.size() > 1) {
            code.append(ambiguityNotice(methodName, targets)).append("\n\n");
        }
        for (MethodNode target : targets) {
            String src = methodCode(target);
            if (code.length() > 0) {
                code.append("\n\n");
            }
            code.append(src == null || src.isEmpty() ? notDecompiled(target) : src);
        }
        if (code.length() == 0) {
            return null;
        }
        return guardedText(code.toString(), DEFAULT_MAX_BYTES, "getMethodByName",
                "\"" + className + "." + methodName + "\"",
                "Pass signature to ask about one overload.");
    }

    /**
     * A method's own text: its declaration, its body, and the annotations and
     * javadoc sitting directly above it -- and nothing else.
     *
     * MethodNode.getCodeStr() is the obvious call and is right about where a
     * method ends, but it starts the cut at the previous blank line
     * (CodeUtils.getMethodStart). For the first member of a class that blank line
     * is above the class declaration, so the first method of every class came
     * back with the class header glued to its front while the second did not.
     * Walking up over annotation and comment lines instead keeps the part a
     * reader needs -- an @JavascriptInterface above a bridge method is the whole
     * finding -- and stops at the member boundary either way.
     *
     * A null here means jadx printed no body for this method; getMethodByName
     * says so rather than pretending the name was bad.
     */
    private static String methodCode(MethodNode mth) {
        ICodeInfo info = mth.getTopParentClass().getCode();
        int end = CodeUtils.getMethodEnd(mth, info);
        if (end < 0) {
            return null;
        }
        String code = info.getCodeStr();
        int start = CodeUtils.getLineStartForPos(code, mth.getDefPosition());
        while (start > 1) {
            int lineEnd = start - 1;  // the newline closing the line above
            int prev = CodeUtils.getLineStartForPos(code, lineEnd - 1);
            String above = code.substring(prev, lineEnd).trim();
            if (!(above.startsWith("@") || above.startsWith("//")
                    || above.startsWith("/*") || above.startsWith("*"))) {
                break;
            }
            start = prev;
        }
        return end <= start ? null : code.substring(start, end);
    }

    private String notDecompiled(MethodNode mth) {
        MethodInfo info = mth.getMethodInfo();
        return "// " + classDisplayName(info.getDeclClass()) + "." + pinnedNames.signatureOf(mth)
                + " is declared in the dex but the decompiler prints no body for it"
                + " -- jadx omits implicit default constructors and folds a"
                + " field-initialiser <clinit> into the field declarations."
                + " get_smali_of_method has the bytecode.";
    }

    /**
     * The one method-name lookup. Everything that takes a methodName goes through
     * here, for the same reason everything that takes a className goes through
     * findClass: a name a tool hands back has to be a name every tool accepts.
     *
     * Five vocabularies were in circulation before this existed --
     * getMethodsOfClass emitted raw dex names, searchMethodByName emitted
     * fully-qualified aliases, getCallees/getXrefsToMethod/getOverrides took bare
     * aliases, getSmaliOfMethod took bare raw names, and getMethodByName matched
     * decompiled text -- so the natural discover-then-query workflow failed on
     * 11.4% of a 748-method sample of the a large production APK APK.
     *
     * Accepted, in this order:
     *   fully-qualified   com.x.Y.m30110h   a Java method name cannot contain a
     *                     dot, so everything before the last one is a class
     *                     prefix and drops off without ambiguity
     *   alias             m30110h           what get_class_source renders and
     *                                       what every xref/callee entry reports
     *   raw dex name      h                 what get_smali_of_class prints
     *
     * Every overload of a matched name is returned: an overload set is exactly
     * the thing a bare name cannot tell apart, and the caller has a bare name.
     * Alias is tried across the whole class before raw, so a class where one
     * method's alias collides with another's raw name resolves to the alias --
     * the vocabulary everything else reports in.
     */
    private List<MethodNode> resolveMethods(ClassNode cls, String methodName) {
        return resolveMethods(cls, methodName, null);
    }

    /**
     * The same lookup, optionally narrowed to one overload by its signature.
     *
     * Input stays LENIENT, and deliberately more so than before. Names recorded
     * by earlier runs are sitting in workspaces on disk, and a name an agent
     * reads out of a warm get_class_source is whatever jadx renamed it to, so a
     * method answers to every name it has ever been reported under:
     *
     *   pinned alias    onCanceled2   what this server reports, always
     *   current alias   onCanceled    what a decompiled class prints once
     *                                 ClassModifier has removed the bridge
     *   raw dex name    onCanceled    what get_smali_of_class prints
     *
     * These are three ordered TIERS, not one pooled match, and the order is
     * what makes the answer independent of how warm the daemon is. Pooling
     * pinned and current together looks more generous and quietly breaks the
     * property this class exists for: cold, "onCanceled" on CursorLoader names
     * only the synthetic bridge; warm, the real method's live alias has become
     * "onCanceled" too, so a pooled match would answer for two methods where it
     * used to answer for one, and get_xrefs_to_method would return a different
     * caller list before and after some unrelated request decompiled that
     * class. Measured while building this: exactly the 18 drifted methods of
     * DivaApplication.apk changed their xref answer that way.
     *
     * With tiers, the pinned pass decides whenever it matches anything, so
     * every name this server hands out resolves to the same methods forever.
     * The lower tiers can only turn a "method not found" into an answer, never
     * one answer into a different one.
     *
     * @param signature optional, and accepted in any of the forms this server
     *                  prints one: the bare descriptor "(Landroid/database/Cursor;)V",
     *                  or a full signature with either the pinned, current or
     *                  raw name in front of it. Null or blank means "do not
     *                  narrow", which is not the same as "no match" -- see the
     *                  ambiguity notice on the tools that call this.
     */
    private List<MethodNode> resolveMethods(ClassNode cls, String methodName, String signature) {
        List<MethodNode> targets = new ArrayList<>();
        if (cls == null || methodName == null) {
            return targets;
        }

        // A signature can also arrive glued to the name, because that is how
        // get_methods_of_class prints an overload and how an agent will paste
        // it back. Split before stripping the class prefix: a descriptor holds
        // no '.', so the two cannot be confused, but doing it in this order
        // keeps that from being load-bearing.
        String query = methodName;
        String sig = signature;
        int paren = query.indexOf('(');
        if (paren >= 0) {
            if (sig == null || sig.isBlank()) {
                sig = query.substring(paren);
            }
            query = query.substring(0, paren);
        }

        int dot = query.lastIndexOf('.');
        String name = dot < 0 ? query : query.substring(dot + 1);

        // Tier 1: the pinned name -- the only vocabulary this server reports in,
        // so this is the pass that answers every name it ever handed out.
        for (MethodNode mth : cls.getMethods()) {
            if (pinnedNames.aliasOf(mth).equals(name)) {
                targets.add(mth);
            }
        }
        // Tier 2: whatever jadx has since renamed it to, for a name read out of
        // a warm get_class_source or recorded by an older server.
        if (targets.isEmpty()) {
            for (MethodNode mth : cls.getMethods()) {
                if (mth.getMethodInfo().getAlias().equals(name)) {
                    targets.add(mth);
                }
            }
        }
        // Tier 3: the raw dex name, which is what get_smali_of_class prints.
        if (targets.isEmpty()) {
            for (MethodNode mth : cls.getMethods()) {
                if (mth.getMethodInfo().getName().equals(name)) {
                    targets.add(mth);
                }
            }
        }

        if (sig != null && !sig.isBlank()) {
            targets = narrowBySignature(targets, sig);
        }
        return targets;
    }

    /**
     * Keep the methods whose signature the caller named.
     *
     * Matched on the descriptor -- the "(args)ret" tail -- rather than the whole
     * string, so all four spellings of the same signature work: the bare
     * descriptor, and the pinned, current or raw name in front of it. The
     * descriptor is the part that actually tells overloads apart; the name in
     * front of it is the part that does not.
     */
    private List<MethodNode> narrowBySignature(List<MethodNode> targets, String signature) {
        String wanted = signature.trim();
        int paren = wanted.indexOf('(');
        String wantedDescriptor = paren < 0 ? wanted : wanted.substring(paren);

        List<MethodNode> narrowed = new ArrayList<>();
        for (MethodNode mth : targets) {
            if (PinnedMethodNames.descriptorOf(mth.getMethodInfo()).equals(wantedDescriptor)) {
                narrowed.add(mth);
            }
        }
        return narrowed;
    }

    /**
     * The notice a merged answer carries, or null when the answer covers
     * exactly one method and there is nothing to disclose.
     *
     * Names every signature, because the caller's next move is to pick one and
     * the signature is the argument that does it.
     */
    private String ambiguityNotice(String methodName, List<MethodNode> targets) {
        if (targets.size() < 2) {
            return null;
        }
        List<String> signatures = new ArrayList<>();
        for (MethodNode mth : targets) {
            signatures.add(pinnedNames.signatureOf(mth));
        }
        Collections.sort(signatures);
        return marker(AMBIGUOUS_MARKER_PREFIX, "\"" + methodName + "\" names "
                + targets.size() + " methods in this class and this answer covers"
                + " all of them: " + String.join(", ", signatures)
                + ". Pass signature to ask about one.");
    }

    /**
     * "Method not found", worded so the caller can tell WHICH half of the query
     * missed. A signature that matches nothing is a different mistake from a
     * name that matches nothing, and an empty result would be a third meaning
     * again -- for the xref tools an empty list already means "nothing calls
     * this", so a bad signature must never be spelled that way.
     *
     * There is a third mistake, and it used to be reported as the first.
     * get_callees splits its answer by RESOLUTION, not by package, so a call to
     * an inherited framework method is listed under the in-APK subclass the
     * bytecode names -- LogActivity.findViewById, not Activity.findViewById.
     * That is the honest receiver, and it is also a name no name-taking tool
     * accepted, because LogActivity does not DECLARE findViewById. The
     * rejection said "Method not found", which reads as "no such method" when
     * the truth is "not here, one level up". Measured on DivaApplication.apk:
     * 51 of 173 external callee entries fed back, i.e. every rejection whose
     * class was in the APK at all.
     *
     * So before giving up, walk the superclass chain and say where the method
     * actually lives. That turns a dead end into the next query.
     */
    private RuntimeException noSuchMethod(ClassNode cls, String methodName, String signature) {
        if (signature != null && !signature.isBlank()) {
            List<MethodNode> byName = resolveMethods(cls, methodName, null);
            if (!byName.isEmpty()) {
                List<String> available = new ArrayList<>();
                for (MethodNode mth : byName) {
                    available.add(pinnedNames.signatureOf(mth));
                }
                Collections.sort(available);
                return new RuntimeException("No method " + methodName + " with signature "
                        + signature + " in class " + classDisplayName(cls)
                        + ". It declares: " + String.join(", ", available));
            }
        }
        String inherited = declaringAncestor(cls, methodName, signature);
        if (inherited != null) {
            return new RuntimeException("Method not found: " + methodName
                    + " is not declared by " + classDisplayName(cls)
                    + "; it inherits it from " + inherited
                    + ". Ask about that class instead.");
        }
        return new RuntimeException("Method not found: " + methodName
                + " in class " + classDisplayName(cls));
    }

    /**
     * The nearest ancestor that declares this method, or null if none does.
     *
     * Walks superclasses and then interfaces, in-APK first so the answer is a
     * class this server can actually answer about. A framework ancestor is
     * still worth naming -- it tells the caller the method is real and that the
     * dex is simply not where it lives -- so ClspGraph's view is used as the
     * fallback, marked as outside the APK.
     */
    private String declaringAncestor(ClassNode cls, String methodName, String signature) {
        Set<ClassNode> seen = new HashSet<>();
        Deque<ClassNode> queue = new ArrayDeque<>();
        pushSuperTypes(cls, queue, seen);
        while (!queue.isEmpty()) {
            ClassNode next = queue.poll();
            if (!resolveMethods(next, methodName, signature).isEmpty()) {
                return classDisplayName(next);
            }
            pushSuperTypes(next, queue, seen);
        }
        // Nothing in the dex declares it. The common case by far -- a call to an
        // inherited FRAMEWORK method -- lives here, so ask jadx's class-path
        // graph which ancestor has it. Confirmed, not guessed: naming the
        // superclass just because it exists would turn a plain typo into a
        // confident and wrong "you inherit it from AppCompatActivity".
        ClspGraph clsp = jadx.getRoot().getClsp();
        // Keyed by ClassInfo.getRawName() -- ClspGraph.addClass indexes on
        // clsType.getObject(), which spells an inner class with '$'. Passing
        // getFullName() here silently found nothing for every inner class.
        Set<String> superTypes = new LinkedHashSet<>(
                clsp.getSuperTypes(cls.getClassInfo().getRawName()));
        superTypes.addAll(clsp.getSuperTypes(cls.getClassInfo().getFullName()));
        List<String> ordered = new ArrayList<>(superTypes);
        Collections.sort(ordered);
        for (String superType : ordered) {
            if (declaresByName(clsp, superType, methodName)) {
                return superType + " (outside this APK)";
            }
        }
        return null;
    }

    /**
     * Does this class-path type declare a method of this bare name?
     *
     * ClspClass keys its methods by shortId -- "getPaddingTop()I" -- and a
     * caller holding a callee entry has only the name, so the match is on the
     * name plus the open paren that always follows it. That cannot collide with
     * a longer name, which a plain prefix test would.
     */
    private static boolean declaresByName(ClspGraph clsp, String clsName, String methodName) {
        int dot = methodName == null ? -1 : methodName.lastIndexOf('.');
        String bare = dot < 0 ? methodName : methodName.substring(dot + 1);
        if (bare == null || bare.isEmpty()) {
            return false;
        }
        ClspClass details = clsp.getClsDetails(ArgType.object(clsName));
        if (details == null || details.getMethodsMap() == null) {
            return false;
        }
        String prefix = bare + "(";
        for (String shortId : details.getMethodsMap().keySet()) {
            if (shortId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Superclass then interfaces, skipping anything already queued. */
    private void pushSuperTypes(ClassNode cls, Deque<ClassNode> queue, Set<ClassNode> seen) {
        RootNode root = jadx.getRoot();
        List<ArgType> types = new ArrayList<>();
        if (cls.getSuperClass() != null) {
            types.add(cls.getSuperClass());
        }
        types.addAll(cls.getInterfaces());
        for (ArgType type : types) {
            ClassNode node = root.resolveClass(type);
            if (node != null && seen.add(node)) {
                queue.add(node);
            }
        }
    }
    
    /**
     * Search for methods by name across all classes.
     *
     * Reads names straight from the dex tree. The obvious implementation --
     * iterating jadx.getClasses() and calling JavaClass.getMethods() -- decompiles
     * every class in the APK, because getMethods() calls load(). That cost 71.6s
     * on a large APK and pulled in third-party libraries to answer a
     * name lookup. Method names are metadata; matching them needs no decompilation.
     *
     * Names are alias-aware because loadApk() enables deobfuscation, so callers
     * get the same names get_class_source reports.
     */
    public Map<String, List<String>> searchMethodByName(String methodName) {
        return searchMethodByName(methodName, 0);
    }

    /**
     * Methods matching a name, optionally capped.
     *
     * The size guard getAllClasses has, on the tool that needed it more. This
     * matches on substring and had no floor at all -- name.contains("") is true
     * for every method that exists -- so search_method_by_name(methodName="")
     * returned every method and tens of MB, many times the whole-APK class dump
     * the marker and the warning were written to prevent.
     *
     * @param limit at most this many CLASSES; 0 or less means uncapped, clamped
     *              rather than rejected, as in getAllClasses. The map is keyed by
     *              class, so that is the axis a cap can act on; the marker names
     *              both totals, classes and methods, since either can be the one
     *              that surprised the caller. A capped map carries one extra
     *              entry under {@value #TRUNCATION_MARKER_PREFIX} -- a silent cap
     *              reads as "this is the whole APK", which is worse than a big
     *              response. Truncation takes the first N in class-name order, so
     *              it is reproducible rather than a ranking.
     */
    public Map<String, List<String>> searchMethodByName(String methodName, int limit) {
        checkLoaded();

        // The price depends on WHICH short query this is: the empty string
        // matches every name, one character matches many. Same refusal, and it
        // has to give the caller the reason that applies to what they typed.
        boolean empty = methodName == null || methodName.strip().isEmpty();
        String rejection = shortQueryRejection("methodName", methodName,
                (empty ? EMPTY_METHOD_SEARCH_COST : ONE_CHAR_METHOD_SEARCH_COST)
                + METHOD_SEARCH_FLOOR_REASON);
        if (rejection != null) {
            Map<String, List<String>> refused = new LinkedHashMap<>();
            refused.put(REJECTED_MARKER_PREFIX, List.of(rejection));
            return refused;
        }

        // Sorted by class name, for the same reason findSinkCallSites sorts:
        // truncation has to cut a deterministic order to mean anything.
        Map<String, List<String>> results = new TreeMap<>();
        long matched = 0;

        for (ClassNode cls : jadx.getRoot().getClasses()) {
            // Deduplicated: the dex tree exposes synthetic bridge methods that the
            // decompiled view hides, and a covariant-return bridge carries the same
            // name as the method it forwards to. Distinct signatures, identical
            // display name, so they would otherwise show up as repeated entries.
            Set<String> methods = new LinkedHashSet<>();

            for (MethodNode mth : cls.getMethods()) {
                // Matched on the pinned name ALONE, and reported under it, so
                // the same search returns the same set however warm the daemon
                // is. Also matching the live alias would widen the result set
                // as classes decompiled, which is the drift this change exists
                // to remove, only spread across every class in the APK.
                //
                // Nothing is lost by leaving the live alias out, because this
                // matches on SUBSTRING and drift shortens rather than rewrites:
                // jadx frees "onCanceled" by renaming it from "onCanceled2",
                // and "onCanceled" is a substring of "onCanceled2". A search for
                // what warm source prints still finds the method.
                if (pinnedNames.aliasOf(mth).contains(methodName)) {
                    methods.add(pinnedNames.fullNameOf(mth));
                }
            }

            if (!methods.isEmpty()) {
                results.put(classDisplayName(cls), new ArrayList<>(methods));
                matched += methods.size();
            }
        }

        String advice = "Pass limit, or a longer methodName.";
        if (limit > 0 && results.size() > limit) {
            Map<String, List<String>> capped = new LinkedHashMap<>();
            long cappedMethods = 0;
            for (Map.Entry<String, List<String>> e : results.entrySet()) {
                if (capped.size() == limit) {
                    break;
                }
                capped.put(e.getKey(), e.getValue());
                cappedMethods += e.getValue().size();
            }
            capped.put(TRUNCATION_MARKER_PREFIX, List.of(marker(TRUNCATION_MARKER_PREFIX,
                    "showing " + limit + " of " + results.size() + " classes ("
                    + matched + " methods) matching \"" + methodName + "\". Raise"
                    + " limit, or use a longer methodName -- this matches anywhere"
                    + " in the name, so one more character narrows it sharply.")));
            // The cap here is on CLASSES and the payload is their methods, so a
            // count cap bounds the response even less tightly than elsewhere:
            // limit=100000 on "in" is 18,225,571 bytes, the widest thing this
            // server produced with nothing in the body admitting it. See
            // guardedNames for the rule; the map shape puts the notice under a
            // key rather than at the end of a list.
            String cutNotice = oversizedNotice("searchMethodByName", cappedMethods,
                    "method names", bytesOfMethodMap(capped),
                    "methodName=" + methodName, advice);
            if (cutNotice != null) {
                capped.put(OVERSIZED_MARKER_PREFIX, List.of(cutNotice));
            }
            return capped;
        }

        String notice = oversizedNotice("searchMethodByName", (int) matched,
                "method names", bytesOfMethodMap(results),
                "methodName=" + methodName, advice);
        if (notice != null) {
            // TreeMap, so a '@' key would sort in among the class names; the
            // notice belongs at the end where the other tools put it.
            Map<String, List<String>> annotated = new LinkedHashMap<>(results);
            annotated.put(OVERSIZED_MARKER_PREFIX, List.of(notice));
            return annotated;
        }
        return results;
    }
    
    
    /**
     * What searchMethodByName's answer costs: its class-name keys and every
     * method name filed under them, including the bookkeeping entries, which
     * are part of what the caller is handed.
     */
    private static long bytesOfMethodMap(Map<String, List<String>> results) {
        long bytes = jsonBytes(results.keySet());
        for (List<String> methods : results.values()) {
            bytes += jsonBytes(methods);
        }
        return bytes;
    }

    /**
     * Search for classes whose name contains a keyword, case-insensitively.
     *
     * Reads names from the dex tree for the same reason searchMethodByName does:
     * jadx.getClasses() returns JavaClass wrappers whose accessors call load(),
     * and matching a name must not decompile anything.
     *
     * Names are alias-aware, so a result can be fed straight back into
     * getClassSource -- which rejects raw dex names once deobfuscation is on.
     */
    public List<String> searchClassesByKeyword(String keyword) {
        return searchClassesByKeyword(keyword, 0);
    }

    /**
     * Classes matching a keyword, optionally capped.
     *
     * The empty keyword was already refused here, with a comment reasoning about
     * "a 4 MB response to a query that asked for nothing" -- but that guard was
     * one character wide and silent. One character over it still returned 134,834
     * of a large production APK's 141,461 classes and 7.5 MB, which is the whole-APK dump this
     * tool is documented as the narrow alternative to.
     *
     * @param limit at most this many names; 0 or less means uncapped, clamped
     *              rather than rejected, as in getAllClasses. A cut-short list ends
     *              with one extra entry starting {@value #TRUNCATION_MARKER_PREFIX}
     *              naming the real total, exactly as getAllClasses does.
     */
    public List<String> searchClassesByKeyword(String keyword, int limit) {
        checkLoaded();

        String rejection = shortQueryRejection("keyword", keyword, CLASS_SEARCH_COST);
        if (rejection != null) {
            List<String> refused = new ArrayList<>();
            refused.add(rejection);
            return refused;
        }

        String needle = keyword.toLowerCase();
        // Inner classes appear under both getClasses() and their parent, so the
        // same alias can be reached twice.
        Set<String> matches = new TreeSet<>();

        for (ClassNode cls : jadx.getRoot().getClasses()) {
            String name = classDisplayName(cls);
            if (name.toLowerCase().contains(needle)) {
                matches.add(name);
            }
        }

        List<String> hits = new ArrayList<>(matches);
        String advice = "Pass limit, or a longer keyword.";
        if (limit > 0 && hits.size() > limit) {
            List<String> capped = new ArrayList<>(hits.subList(0, limit));
            capped.add(marker(TRUNCATION_MARKER_PREFIX, "showing " + limit + " of "
                    + hits.size() + " classes matching \"" + keyword + "\". Raise"
                    + " limit, or use a longer keyword -- this matches anywhere in"
                    + " the name, so one more character narrows it sharply."));
            String cutNotice = oversizedNotice("searchClassesByKeyword", limit,
                    "class names", jsonBytes(capped), "keyword=" + keyword, advice);
            if (cutNotice != null) {
                capped.add(cutNotice);
            }
            return capped;
        }

        String notice = oversizedNotice("searchClassesByKeyword", hits.size(),
                "class names", jsonBytes(hits), "keyword=" + keyword, advice);
        if (notice != null) {
            hits.add(notice);
        }
        return hits;
    }

    /**
     * The reverse edges every xref tool answers from, built once and kept.
     *
     * Not {@code getUseIn()}. That list is jadx's record of the Java it intends
     * to print, and decompiling a class rewrites it in place -- see
     * {@link DexReverseIndex} for the four passes that do it and the measured
     * damage. This daemon is long-lived and shared, so reading it made every
     * xref answer depend on what some earlier, unrelated request happened to
     * decompile, and mostly by losing edges.
     *
     * Lazy, not eager. The build is one pass over the dex and costs the same
     * whenever it runs -- the inputs do not move -- so the choice is only about
     * who waits: eagerly it lands on every load(), including the loads whose
     * caller never asks for an xref, and this daemon's load is already 14 s on a
     * large APK. Lazily one request pays it once. Measured on a large production APK: 3.1 s
     * to build, against a 13.6 s load, and 88 MB retained afterwards -- 1.4% of
     * the 6 GB AnalyzerRegistry budgets per warm APK, which is why that constant
     * did not have to move.
     *
     * Lock-free on a hit, exactly as {@link AnalyzerRegistry#get} is and for the
     * same reason: the build holds the monitor for seconds and blocking every
     * other in-flight request behind it would be worse than the bug. The
     * double-check inside the monitor is what stops sixteen concurrent clients
     * building sixteen indexes.
     */
    private DexReverseIndex reverseIndex() {
        DexReverseIndex index = reverseIndex;
        if (index != null) {
            return index;
        }
        synchronized (reverseIndexLock) {
            if (reverseIndex == null) {
                logger.info("Building dex reverse index for " + apkPath);
                DexReverseIndex built = DexReverseIndex.build(jadx.getRoot());
                logger.info("Dex reverse index ready: " + built.stats());
                reverseIndex = built;
            }
            return reverseIndex;
        }
    }

    /** What the reverse index cost and how big it is; null until one is built. */
    public Map<String, Object> reverseIndexStats() {
        DexReverseIndex index = reverseIndex;
        return index == null ? null : index.stats();
    }

    /**
     * Classes and methods that reference a class.
     *
     * Read from {@link #reverseIndex()} rather than from
     * {@code ClassNode.getUseInMth()/getUseIn()}: InlineMethods.replaceClsUsage
     * safeReplace()s an inlined method for its caller in both lists and
     * ProcessAnonymous adds to the second, so both moved as classes were
     * decompiled -- 46 classes and 165 classes respectively on a large production APK after
     * 3,000 of 141,461.
     */
    public List<Map<String, String>> getXrefsToClass(String className) {
        return getXrefsToClass(className, 0);
    }

    /**
     * @param limit at most this many entries; 0 or less means uncapped, clamped
     *              rather than rejected, as everywhere else here. A cut-short
     *              list ends with one extra entry holding
     *              {@value #TRUNCATION_MARKER_PREFIX} and the real total.
     */
    public List<Map<String, String>> getXrefsToClass(String className, int limit) {
        checkLoaded();
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            throw new RuntimeException("Class not found: " + className);
        }

        DexReverseIndex index = reverseIndex();
        // The method-context list gives the enclosing method, which the
        // class-level one does not. A bare class name is not actionable for a
        // tracer following a chain.
        List<Map<String, String>> xrefs = new ArrayList<>();
        for (MethodNode mth : index.methodUsersOf(cls)) {
            xrefs.add(refEntry(mth));
        }
        // Classes that mention the type without a method context -- e.g. a
        // superclass or interface reference -- are still worth reporting.
        //
        // All three keys, and the one with no value is present and empty rather
        // than absent. This is the one entry in the whole {className,
        // methodName, signature} family that is not built by refEntry, and it
        // used to carry two keys: a caller who read entry["signature"] on every
        // entry -- which is what this server's own reference doc tells them to
        // do -- got a KeyError rather than a value.
        //
        // Not a corner. Measured over every one of a large production APK's 141,461 classes
        // by XrefEntryShapeProbe, 154,307 of 1,930,800 xref entries (8.0%) are
        // class-level, and 30,754 classes (21.7%) return at least one -- so
        // roughly one target in five was a KeyError waiting for a tracer that
        // walked its whole answer.
        //
        // An absent key is a second shape for "no method context" that only a
        // caller who already knew about it could handle; an empty string is the
        // same fact in the shape every other entry has, and it is a fact worth
        // having: an empty methodName means the reference is the class's own --
        // an extends or implements bound, or a type in a member signature --
        // rather than something wrong with the entry.
        Set<String> covered = new HashSet<>();
        for (Map<String, String> x : xrefs) {
            covered.add(x.get("className"));
        }
        for (ClassNode user : index.classUsersOf(cls)) {
            String name = classDisplayName(user);
            if (covered.add(name)) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("className", name);
                entry.put("methodName", "");
                entry.put("signature", "");
                xrefs.add(entry);
            }
        }
        return guardedRefs(sortedByClassThenMethod(xrefs), limit, "getXrefsToClass",
                "references", "className=" + className, XREF_ADVICE);
    }

    /**
     * What to do instead when an xref list is too wide to read.
     *
     * There is no narrower form of "who references this" -- the question has one
     * answer and its size is a property of the target. So the advice is to ask a
     * narrower question rather than to phrase this one better: 53,181 references
     * to kotlin.jvm.internal.Intrinsics is a fact about Kotlin's null checks,
     * not a lead, and the caller who reached it was tracing something else.
     */
    private static final String XREF_ADVICE =
            "A target this widely referenced is usually a utility rather than"
            + " the thing being traced -- ask about the caller you actually came"
            + " from instead.";

    /**
     * Methods that call a method.
     *
     * The tool this whole index was built for. See {@link #reverseIndex()}.
     */
    public List<Map<String, String>> getXrefsToMethod(String className, String methodName) {
        return getXrefsToMethod(className, methodName, null);
    }

    /**
     * @param signature optional, narrowing an overloaded name to one method;
     *                  see {@link #resolveMethods(ClassNode, String, String)}
     */
    public List<Map<String, String>> getXrefsToMethod(String className, String methodName,
                                                      String signature) {
        return getXrefsToMethod(className, methodName, signature, 0);
    }

    /**
     * @param limit at most this many callers; 0 or less means uncapped. The
     *              truncation entry counts callers only, so it is unaffected by
     *              whether an {@value #AMBIGUOUS_MARKER_PREFIX} entry is also
     *              present -- both are trailing bookkeeping.
     */
    public List<Map<String, String>> getXrefsToMethod(String className, String methodName,
                                                      String signature, int limit) {
        checkLoaded();
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            throw new RuntimeException("Class not found: " + className);
        }

        List<MethodNode> targets = resolveMethods(cls, methodName, signature);
        if (targets.isEmpty()) {
            throw noSuchMethod(cls, methodName, signature);
        }

        // Overloads share a display name, and a caller of one is a caller of
        // "that method" as far as the asking agent is concerned. The union is
        // still the right answer; it just has to say that is what it is, or a
        // caller list reads as the callers of one method when some of the
        // entries call a different one.
        DexReverseIndex index = reverseIndex();
        List<Map<String, String>> xrefs = new ArrayList<>();
        for (MethodNode target : targets) {
            for (MethodNode user : index.callersOf(target)) {
                xrefs.add(refEntry(user));
            }
        }
        List<Map<String, String>> result = guardedRefs(
                sortedByClassThenMethod(dedupe(xrefs)), limit, "getXrefsToMethod",
                "callers", className + "." + methodName, XREF_ADVICE);

        String ambiguity = ambiguityNotice(methodName, targets);
        if (ambiguity != null) {
            // Trailing entry, the shape the marker convention uses in a list.
            // One key, and an '@' one, so it cannot be read as a caller: every
            // real entry has a className and this does not.
            result.add(Map.of(AMBIGUOUS_MARKER_PREFIX, ambiguity));
        }
        return result;
    }

    /**
     * Methods that read or write a field.
     *
     * Same index, same reason: {@code FieldNode.getUseIn()} moved for 983 of
     * DivaApplication.apk's 9,643 fields once the APK was decompiled, and unlike
     * the method case it moved mostly by GAINING edges -- InlineMethods re-points
     * a folded accessor's field accesses at whoever it was folded into, so a
     * field ends up naming callers whose own bytecode never touches it.
     */
    public List<Map<String, String>> getXrefsToField(String className, String fieldName) {
        return getXrefsToField(className, fieldName, 0);
    }

    /** @param limit at most this many users; 0 or less means uncapped. */
    public List<Map<String, String>> getXrefsToField(String className, String fieldName,
                                                     int limit) {
        checkLoaded();
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            throw new RuntimeException("Class not found: " + className);
        }

        List<FieldNode> targets = resolveFields(cls, fieldName);
        if (targets.isEmpty()) {
            throw noSuchField(cls, fieldName);
        }

        List<Map<String, String>> xrefs = new ArrayList<>();
        for (FieldNode target : targets) {
            for (MethodNode user : reverseIndex().usersOf(target)) {
                xrefs.add(refEntry(user));
            }
        }
        List<Map<String, String>> result = guardedRefs(
                sortedByClassThenMethod(dedupe(xrefs)), limit,
                "getXrefsToField", "users", className + "." + fieldName, XREF_ADVICE);

        String ambiguity = fieldAmbiguityNotice(fieldName, targets);
        if (ambiguity != null) {
            result.add(Map.of(AMBIGUOUS_MARKER_PREFIX, ambiguity));
        }
        return result;
    }

    /**
     * The fields a caller's spelling names, in ordered tiers -- the field half
     * of {@link #resolveMethods}, and it exists for the same reason.
     *
     * get_fields_of_class prints "type name" and this matched the bare alias,
     * so the round-trip invariant -- a name this server hands out is a name this
     * server accepts -- failed on EVERY field: 504 of 504 sampled entries on
     * DivaApplication.apk were rejected when pasted back verbatim, and the
     * rejection said "Field not found", which reads as "no such field" rather
     * than "wrong spelling". That particular failure was not about drift: it
     * was two vocabularies, one spoken on the way out and a different one
     * required on the way in, and it failed warm and cold alike.
     *
     * Field aliases DO move, which is why tier 2 exists two paragraphs below.
     * This comment used to say the opposite in the same breath as the tier that
     * handles it -- see {@link PinnedFieldNames}, which measures 1,958 of
     * 861,374 a large production APK fields renamed by EnumVisitor once every enum has
     * decompiled.
     *
     * The tiers are ORDERED rather than pooled, the distinction commit 3ad692f
     * settled for methods: a lower tier can only turn "not found" into an
     * answer, never one answer into a different one.
     *
     * @param fieldName the printed "type name" entry, or the bare alias, or the
     *                  raw dex name that get_smali_of_class prints
     */
    private List<FieldNode> resolveFields(ClassNode cls, String fieldName) {
        List<FieldNode> targets = new ArrayList<>();
        if (cls == null || fieldName == null) {
            return targets;
        }

        // "type name" splits on the last space; neither half can contain one.
        // A type may itself be spelled with spaces ("V extends android.view.View
        // mLayout"), which is why this takes the LAST space rather than the
        // first.
        String query = fieldName.trim();
        String wantedType = null;
        int sp = query.lastIndexOf(' ');
        if (sp >= 0) {
            wantedType = query.substring(0, sp).trim();
            query = query.substring(sp + 1);
        }
        // A qualified spelling, the shape the method side also accepts.
        int dot = query.lastIndexOf('.');
        String name = dot < 0 ? query : query.substring(dot + 1);

        // The same population get_fields_of_class lists, for the reason given on
        // declaredFields: a name this server emitted must resolve to the field
        // it emitted it for, and to exactly one, whatever has decompiled since.
        List<FieldNode> fields = declaredFields(cls);

        // Tier 1: the pinned alias -- the only vocabulary this server reports
        // in, so this is the pass that answers every name it ever handed out.
        for (FieldNode fld : fields) {
            if (pinnedFields.aliasOf(fld).equals(name)) {
                targets.add(fld);
            }
        }
        // Tier 2: whatever jadx has since renamed it to -- an enum constant read
        // out of a warm get_class_source, or recorded by an older server.
        if (targets.isEmpty()) {
            for (FieldNode fld : fields) {
                if (fld.getFieldInfo().getAlias().equals(name)) {
                    targets.add(fld);
                }
            }
        }
        // Tier 3: the raw dex name, which is what get_smali_of_class prints.
        if (targets.isEmpty()) {
            for (FieldNode fld : fields) {
                if (fld.getFieldInfo().getName().equals(name)) {
                    targets.add(fld);
                }
            }
        }

        // One class CAN declare two fields of one name -- the dex tells them
        // apart by type, and FieldInfo.equals agrees -- so the type half of the
        // printed entry is the disambiguator, exactly as a signature is for an
        // overload set. Narrowing only ever applies when the caller supplied it.
        if (wantedType != null && !wantedType.isEmpty() && targets.size() > 1) {
            List<FieldNode> narrowed = new ArrayList<>();
            for (FieldNode fld : targets) {
                if (fieldTypeName(fld).equals(wantedType)) {
                    narrowed.add(fld);
                }
            }
            if (!narrowed.isEmpty()) {
                return narrowed;
            }
        }
        return targets;
    }

    /**
     * The notice a merged field answer carries, or null when the answer covers
     * exactly one field. Names every type, because the type is the argument
     * that picks one.
     */
    private String fieldAmbiguityNotice(String fieldName, List<FieldNode> targets) {
        if (targets.size() < 2) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (FieldNode fld : targets) {
            entries.add(fieldEntry(fld));
        }
        Collections.sort(entries);
        return marker(AMBIGUOUS_MARKER_PREFIX, "\"" + fieldName + "\" names "
                + targets.size() + " fields in this class and this answer covers"
                + " all of them: " + String.join(", ", entries)
                + ". Pass the whole \"type name\" entry to ask about one.");
    }

    /**
     * "Field not found", listing what the class does declare. The old message
     * named only what was asked for, which is the least useful half when the
     * cause is a spelling the tool does not accept.
     */
    private RuntimeException noSuchField(ClassNode cls, String fieldName) {
        List<String> available = new ArrayList<>();
        // What get_fields_of_class would list, not what getFields() holds: a
        // "did you mean" that offers a name the listing never emits, and that
        // the resolver above no longer accepts, is worse than none.
        for (FieldNode fld : declaredFields(cls)) {
            available.add(fieldEntry(fld));
        }
        // Sorted first, then cut: an R table has 15,260 fields and the first 20
        // of a sorted list is a usable sample, while the first 20 of dex order
        // is an arbitrary one. The ellipsis is appended after the sort so it
        // stays at the end rather than sorting to the front.
        Collections.sort(available);
        if (available.size() > 20) {
            available = new ArrayList<>(available.subList(0, 20));
            available.add("...");
        }
        String declares = available.isEmpty()
                ? " It declares no fields."
                : " It declares: " + String.join(", ", available) + ".";
        return new RuntimeException("Field not found: " + fieldName + " in class "
                + classDisplayName(cls) + "." + declares);
    }

    /**
     * jadx skips this one call in every constructor, so we do too -- an
     * "external" list that led with Object.<init> for every <init> would be
     * noise. Matches UsageInfo.unresolvedMethodUse.
     */
    private static final String IMPLICIT_OBJECT_CTOR = "java.lang.Object.<init>()V";

    /** What to do when one of getCallees' two lists is cut short. */
    private static final String CALLEE_ADVICE =
            "A method this wide is usually a dependency-injection constructor or"
            + " a service registry, not a hop worth following one call at a time.";

    /**
     * The methods a method calls -- the direction the xref tools do not have.
     *
     * Mirror of getXrefsToMethod: same target resolution, same entry shape,
     * opposite edge direction.
     *
     * The split is the reason to have it:
     *   "callees"  -- calls that resolve to a MethodNode in this dex, i.e. the
     *                 next hop a tracer follows
     *   "external" -- calls that resolve to nothing in the dex. A call that
     *                 leaves the app is a call into the framework, and a call
     *                 into the framework is what a sink is, so this list IS the
     *                 method's sink list, pre-filtered.
     *
     * "external" is defined by resolution, not by package: an invoke on an
     * inherited framework method still names the in-APK subclass as its
     * declaring type (Activity.findViewById arrives as
     * jakhar.aseem.diva.LogActivity.findViewById), because no MethodNode
     * declares it here. That is the honest answer -- it is the receiver the
     * bytecode names -- and it is still not a hop this dex can follow.
     *
     * Recursion is reported separately, never inside "callees", because a
     * forward walk needs it as a termination guard rather than as a hop.
     *
     * Read from the dex, not from jadx's usage graph -- see scanCallees for why.
     */
    public Map<String, Object> getCallees(String className, String methodName) {
        return getCallees(className, methodName, null);
    }

    /**
     * @param signature optional, narrowing an overloaded name to one method;
     *                  see {@link #resolveMethods(ClassNode, String, String)}
     */
    public Map<String, Object> getCallees(String className, String methodName,
                                          String signature) {
        return getCallees(className, methodName, signature, 0);
    }

    /**
     * @param limit at most this many entries in EACH of "callees" and "external";
     *              0 or less means uncapped. Per list rather than across both,
     *              because they answer different questions -- the next hop and
     *              the sink list -- and a shared budget would let a method with
     *              200 framework calls hide its one in-APK hop.
     *
     *              Measured, this cap does not bite on either reference APK: the
     *              widest of a large production APK's 872,119 methods is
     *              com.example.spi.ServiceRegistry.&lt;clinit&gt;
     *              at 540 callees and 1 external, 68,663 bytes. getCallees
     *              dedupes, so length is distinct call targets rather than call
     *              instructions, and the 39,107-instruction methods in that APK
     *              are table-fillers with one callee. It is here so that the
     *              guarantee holds without a per-tool exception, not because a
     *              measurement demanded it.
     */
    public Map<String, Object> getCallees(String className, String methodName,
                                          String signature, int limit) {
        checkLoaded();
        ClassNode cls = findClassNode(className);
        if (cls == null) {
            throw new RuntimeException("Class not found: " + className);
        }

        List<MethodNode> targets = resolveMethods(cls, methodName, signature);
        if (targets.isEmpty()) {
            throw noSuchMethod(cls, methodName, signature);
        }

        // Overloads share a display name, and the caller has only that name to
        // give, so the answer is their union -- the same choice getXrefsToMethod
        // makes for the reverse direction, and disclosed the same way.
        List<Map<String, String>> internal = new ArrayList<>();
        List<Map<String, String>> external = new ArrayList<>();
        boolean[] callsSelf = { false };
        for (MethodNode target : targets) {
            scanCallees(target, internal, external, callsSelf);
        }

        String scope = className + "." + methodName;
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> callees = guardedRefs(
                sortedByClassThenMethod(dedupe(internal)),
                limit, "getCallees", "callees", scope, CALLEE_ADVICE);
        List<Map<String, String>> externals = guardedRefs(
                sortedByClassThenMethod(dedupe(external)),
                limit, "getCallees", "external calls", scope, CALLEE_ADVICE);
        result.put("callees", callees);
        result.put("external", externals);
        result.put("callsSelf", callsSelf[0]);
        String ambiguity = ambiguityNotice(methodName, targets);
        if (ambiguity != null) {
            result.put(AMBIGUOUS_MARKER_PREFIX, List.of(ambiguity));
        }
        wholeResponseNotice(result, "getCallees",
                callees.size() + externals.size(), "calls", scope, CALLEE_ADVICE);
        return result;
    }

    /**
     * Every call instruction in a method's dex code, split into resolved and
     * unresolved, read straight from the bytecode.
     *
     * The obvious implementation is MethodNode.getUsed() / getUnresolvedUsed(),
     * which jadx fills in during load(). It is wrong for this tool, and the way
     * it is wrong is silent.
     *
     * jadx's usage graph describes the Java it intends to PRINT, not the dex.
     * Decompiling a class rewrites that graph in place: InlineMethods folds
     * synthetic accessors into their callers and re-points the edges
     * (InlineMethods.updateUsageInfo), ClassModifier does the same for synthetic
     * constructors (modifySyntheticMethod -> UsageInfoVisitor.replaceMethodUsage),
     * and MethodNode.getUsed() then drops on read -- via removeInvalidMethodsUsed
     * -- every callee whose getUseIn() no longer names this method. Measured on
     * DivaApplication.apk: decompiling all 651 top-level classes made 699 methods
     * report fewer callees, 963 edges gone, 374 of them left with none at all.
     * getUnresolvedUsed() is untouched by this; only the resolved half moves.
     *
     * The daemon is long-lived and shared, so whether a get_callees answer is
     * complete would depend on what some earlier, unrelated request happened to
     * decompile. A tracer walking forward loses a hop and reports no path -- a
     * false negative, the expensive direction for a bug caller. And the dropped
     * hop is real: FloatingActionButtonEclairMr1$ElevateToTranslationZAnimation
     * .getTargetShadowSize is two invoke-static instructions in the smali, and
     * an agent has to follow both to reach what they read.
     *
     * MethodNode.getCodeReader() is a private copy of the dex bytes, final and
     * never cleared, so this survives anything the decompiler does. The walk is
     * UsageInfoVisitor.processInsn's own METHOD_REF / CALL_SITE handling, minus
     * the field and type bookkeeping -- same resolution, same Object.<init>
     * skip, same self-call exclusion, so a cold answer is unchanged.
     *
     * copy() before visiting: visitInstructions moves the reader's position, the
     * daemon serves requests concurrently, and MethodNode.load() reads the same
     * instance. The copy is a ByteBuffer.duplicate(), not a data copy.
     */
    private void scanCallees(MethodNode mth, List<Map<String, String>> internal,
            List<Map<String, String>> external, boolean[] callsSelf) {
        if (mth.isNoCode()) {
            return;
        }
        ICodeReader codeReader = mth.getCodeReader();
        if (codeReader == null) {
            return;
        }
        RootNode root = mth.root();
        try {
            codeReader.copy().visitInstructions(insn -> {
                MethodInfo callee = calleeOf(root, insn);
                if (callee == null) {
                    return;
                }
                MethodNode calleeNode = root.resolveMethod(callee);
                if (calleeNode == null) {
                    if (!IMPLICIT_OBJECT_CTOR.equals(callee.getRawFullId())) {
                        external.add(refEntry(callee));
                    }
                } else if (calleeNode == mth) {
                    callsSelf[0] = true;
                } else {
                    internal.add(refEntry(calleeNode));
                }
            });
        } catch (Exception e) {
            // Loud rather than short: a partial callee list read as complete is
            // exactly the false negative this method exists to prevent.
            throw new RuntimeException("Failed to read call instructions of "
                    + mth.getMethodInfo().getFullId() + ": " + e.getMessage(), e);
        }
    }

    /** The method a call instruction names, or null if the instruction is not a call. */
    private static MethodInfo calleeOf(RootNode root, InsnData insn) {
        if (insn.getOpcode() == Opcode.UNKNOWN) {
            return null;
        }
        switch (insn.getIndexType()) {
            case METHOD_REF:
                insn.decode();
                // invoke-polymorphic carries its real target in the payload.
                ICustomPayload payload = insn.getPayload();
                return MethodInfo.fromRef(root,
                        payload != null ? (IMethodRef) payload : insn.getIndexAsMethod());

            case CALL_SITE:
                // invoke-custom: the bootstrap's 5th argument is the method
                // handle the lambda actually lands on.
                insn.decode();
                ICallSite callSite = InsnDataUtils.getCallSite(insn);
                IMethodHandle handle = InsnDataUtils.getMethodHandleAt(callSite, 4);
                IMethodRef ref = handle != null ? handle.getMethodRef() : null;
                return ref == null ? null : MethodInfo.fromRef(root, ref);

            default:
                return null;
        }
    }

    /** Call sites returned per sink before truncation kicks in; see findSinkCallSites. */
    public static final int DEFAULT_CALL_SITE_LIMIT = 100;

    /**
     * Key under which a sweep names the in-APK types it resolved a sink through.
     * Same '@' escape as {@link #TRUNCATION_MARKER_PREFIX}, same reason: '@' is
     * not legal in a Java class name, a method name or a catalogue tag, so a
     * bookkeeping key can never collide with a sink the caller asked for.
     *
     * Not a truncation notice -- this one is a lead, and it is present on a
     * perfectly complete answer.
     */
    private static final String RESOLVED_IN_APK_KEY = "@resolved_in_apk";

    /**
     * The sinks a sweep runs when the caller names none.
     *
     * These are the analysis client's own impact_category tags, from its
     * references/sink-catalog.md, mapped to the platform calls that identify
     * each one. Keying by tag rather than by API is the point: a call site that
     * comes back under "intent_parse" is already filed the way a finding will be
     * filed, and the agent does not have to learn a second vocabulary.
     *
     * Four catalogue rows are deliberately absent, and each absence is a
     * measurement rather than an oversight (a large production APK, 141461 classes):
     *
     *   data_exfil -- android.util.Log.* is 1473 call sites, 27% of everything
     *       the full catalogue matches. A log call is not a sink until something
     *       tainted reaches it, so this would spend the entire cap on noise.
     *       Ask for "android.util.Log.e" explicitly if that is the question.
     *   ota_bundle_overwrite / signature_permission_bypass -- neither is a
     *       distinct call. The first is file_write aimed at a path that gets
     *       executed; the second is intent_launch aimed at a guarded component.
     *       Both are decided by the argument, which a call-site sweep cannot see.
     *
     * intent_launch carries Fragment.startActivity/startActivityForResult on
     * both the platform and the androidx class, and they are not redundant with
     * the Context and Activity rows: a Fragment is neither, so the subtype rule
     * rightly refuses to fold them together, and without their own rows every
     * fragment-driven launch went unswept. Measured on a large production APK: 34
     * startActivity and 62 startActivityForResult invokes through androidx's
     * Fragment, 91 distinct call sites, none of them reachable any other way.
     *
     * external_browser_load stays out for a different reason than it used to.
     * The old note said CustomTabsIntent.launchUrl "structurally cannot fire"
     * because the class ships inside the APK -- resolveInApkImplementations has
     * since removed that limit, so it would now fire. It is still absent because
     * the catalogue itself calls the row "never a finding on its own": a handoff
     * that leaves the process. Ask for
     * "androidx.browser.customtabs.CustomTabsIntent.launchUrl" when that is the
     * question.
     */
    private static final Map<String, List<String>> DEFAULT_SINKS = defaultSinks();

    private static Map<String, List<String>> defaultSinks() {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("webview_loadurl", List.of(
                "android.webkit.WebView.loadUrl",
                "android.webkit.WebView.postUrl"));
        s.put("html_injection_loaddata", List.of(
                "android.webkit.WebView.loadDataWithBaseURL",
                "android.webkit.WebView.loadData"));
        s.put("js_bridge_call", List.of(
                "android.webkit.WebView.evaluateJavascript"));
        s.put("webview_settings", List.of(
                "android.webkit.WebSettings.setJavaScriptEnabled",
                "android.webkit.WebView.addJavascriptInterface",
                "android.webkit.WebSettings.setAllowFileAccess",
                "android.webkit.WebSettings.setAllowFileAccessFromFileURLs",
                "android.webkit.WebSettings.setAllowUniversalAccessFromFileURLs",
                "android.webkit.WebSettings.setJavaScriptCanOpenWindowsAutomatically"));
        // The credential-attachment calls behind the catalogue's row: a cookie
        // seeded for the destination is what turns a redirect into an ATO.
        s.put("token_exfil_via_webview", List.of(
                "android.webkit.CookieManager.setCookie",
                "android.webkit.CookieManager.getCookie"));
        s.put("file_chooser_abuse", List.of(
                "android.webkit.ValueCallback.onReceiveValue"));
        s.put("intent_launch", List.of(
                "android.content.Context.startActivity",
                "android.content.Context.startActivities",
                "android.app.Activity.startActivityForResult",
                // A Fragment is not a Context and not an Activity, so neither
                // row above reaches these. androidx's Fragment is in the dex,
                // which is why they only became findable once the sweep stopped
                // being limited to calls that leave it.
                "android.app.Fragment.startActivity",
                "android.app.Fragment.startActivityForResult",
                "androidx.fragment.app.Fragment.startActivity",
                "androidx.fragment.app.Fragment.startActivityForResult",
                "android.content.Context.startService",
                "android.content.Context.startForegroundService",
                "android.content.Context.bindService",
                "android.content.Context.sendBroadcast",
                "android.content.Context.sendOrderedBroadcast",
                "android.app.PendingIntent.getActivity",
                "android.app.PendingIntent.getBroadcast",
                "android.app.PendingIntent.getService"));
        s.put("intent_parse", List.of(
                "android.content.Intent.parseUri",
                "android.content.Intent.getIntent",
                "android.content.Intent.getIntentOld"));
        s.put("parcelable_extra_injection", List.of(
                "android.content.Intent.getParcelableExtra",
                "android.content.Intent.getSerializableExtra",
                "android.os.Bundle.getParcelable",
                "android.os.Bundle.getSerializable"));
        s.put("file_read", List.of(
                "java.io.FileInputStream.<init>",
                "android.content.Context.openFileInput",
                "android.content.ContentResolver.openInputStream"));
        s.put("file_write", List.of(
                "java.io.FileOutputStream.<init>",
                "java.io.FileOutputStream.write",
                "android.content.Context.openFileOutput",
                "java.nio.file.Files.write",
                "android.content.ContentResolver.openOutputStream"));
        s.put("content_provider_write", List.of(
                "android.content.ContentResolver.openFileDescriptor",
                "android.content.ContentResolver.openAssetFileDescriptor"));
        s.put("zip_extract", List.of(
                "java.util.zip.ZipInputStream.getNextEntry",
                "java.util.zip.ZipFile.getInputStream",
                "java.util.zip.ZipEntry.getName"));
        s.put("exec", List.of(
                "java.lang.Runtime.exec",
                "java.lang.ProcessBuilder.start"));
        s.put("native_load", List.of(
                "java.lang.System.load",
                "java.lang.System.loadLibrary",
                "java.lang.Runtime.load",
                "java.lang.Runtime.loadLibrary"));
        s.put("class_load", List.of(
                "java.lang.Class.forName",
                "java.lang.ClassLoader.loadClass",
                "dalvik.system.DexClassLoader.<init>",
                "dalvik.system.PathClassLoader.<init>",
                "dalvik.system.InMemoryDexClassLoader.<init>",
                "dalvik.system.BaseDexClassLoader.<init>"));
        s.put("content_share", List.of(
                "android.content.ClipboardManager.setPrimaryClip",
                "android.text.ClipboardManager.setText"));
        s.put("network_request", List.of(
                "java.net.URL.openConnection",
                "java.net.URL.openStream",
                "java.net.URLConnection.connect"));
        s.put("crypto_misuse", List.of(
                "javax.crypto.Cipher.init",
                "javax.crypto.Cipher.getInstance"));
        s.put("account_authenticator", List.of(
                "android.accounts.AccountManager.getAuthToken",
                "android.accounts.AccountManager.addAccount",
                "android.accounts.AccountManager.getAccountsByType"));
        return Collections.unmodifiableMap(s);
    }

    /** One sink API to look for, and the key the caller asked for it under. */
    private static final class SinkMatcher {
        final String key;
        /** Declaring class of the sink, or null to match any receiver. */
        final String declClass;

        SinkMatcher(String key, String declClass) {
            this.key = key;
            this.declClass = declClass;
        }
    }

    /**
     * Every place in the APK that calls one of a set of framework methods.
     *
     * This is sink discovery as a metadata lookup. A call that leaves the dex is
     * a call into the framework, and a call into the framework is what a sink is,
     * so getUnresolvedUsed() -- which load() has already populated -- holds most
     * of the answer and nothing has to be decompiled to read it. Measured on the
     * 141461-class a large production APK APK, full default catalogue, by SinkSweepCostProbe:
     * 59 ms for the unresolved half, 145 ms once in-APK implementations are
     * resolved too. Still a metadata scan; decompiling this APK takes minutes.
     *
     * getUnresolvedUsed() alone is only half the answer, and the missing half
     * is not small. It holds calls that LEAVE the dex, so the moment the APK
     * itself declares the method the call resolves and drops out of it --
     * whether that is a bundled SDK shipping the class (androidx's Fragment) or
     * the app's own subclass overriding the method. Measured on a large production APK
     * against a bytecode ground truth (SinkOverrideBlindSpotProbe): the
     * unresolved half sees 88 of 129 real WebView.loadUrl call sites, and the
     * single biggest thing swallowing the rest is
     * com.example.webview.AppWebView -- the app's own primary
     * hybrid container, i.e. exactly the class a WebView analysis exists to find.
     * Activity.startActivityForResult: 140 of 191, swallowed mostly by
     * androidx.activity.ComponentActivity.
     *
     * resolveInApkImplementations closes it, and the closure is measured
     * complete: over every sink probed, unresolved-half plus in-APK
     * implementations equals the bytecode truth exactly, at a cost of tens of
     * milliseconds because it rides the walk this method already does.
     *
     * A sink token takes one of three forms, resolved in this order:
     *
     *   1. a catalogue tag ("intent_parse") -- expands to the platform calls
     *      that identify it, see DEFAULT_SINKS
     *   2. "package.Class.method" -- matches a call whose method name is
     *      `method` and whose receiver is `Class` OR ANY SUBTYPE OF IT
     *   3. a bare "method" -- matches that method name on any receiver
     *
     * Rule 2's subtype clause is the difference between this working and not.
     * The bytecode names the receiver's static type, so an in-APK subclass that
     * inherits the sink arrives under its own name: DIVA's
     * MainActivity.startChallenge calls jakhar.aseem.diva.MainActivity.startActivity,
     * and on a large production APK four obfuscated WebView subclasses call
     * com.facebook.ads.redexgen.X.SX.loadUrl. Exact declaring-class matching
     * drops all of them -- it finds 553 of the 696 Context.startActivity call
     * sites and 85 of the 91 WebView.loadUrl ones. Matching on the bare method
     * name instead over-corrects: "write" alone matches 1428 call sites across
     * 22 unrelated receivers where only 149 are FileOutputStream. Subtype
     * resolution is the one rule that is neither too narrow nor too wide, and
     * ClspGraph makes it a cached map lookup.
     *
     * Every sink asked for gets a key even when it matched nothing, because
     * "swept, found none" and "never swept" are different facts.
     *
     * limit caps each sink's list; 0 or less means uncapped. A capped sweep adds
     * a "@truncated" entry -- one self-describing string per capped sink, naming
     * it and its true total -- because a list silently cut to its first N reads
     * as the whole truth. It used to be a {sink: total} map, which was a third
     * shape for the same fact; see {@link #TRUNCATION_MARKER_PREFIX}. Truncation
     * takes the first N of the same class-then-method ordering every other tool
     * uses -- it is deterministic, not a ranking.
     *
     * A sweep that resolved through in-APK types adds "@resolved_in_apk"
     * naming them per sink. That is a lead, not a caveat: for webview_loadurl on
     * a hybrid app it is the list of the app's own WebView containers. Both
     * bookkeeping keys start with '@', which is not legal in a class name, a
     * method name or a catalogue tag, so neither can collide with a sink.
     */
    public Map<String, Object> findSinkCallSites(List<String> sinks, int limit) {
        checkLoaded();

        // Sink key -> callSiteKey -> entry. Insertion-ordered so the answer comes
        // back in the order asked for, and the default answer leads with the
        // WebView surface; the inner map is what dedupes a caller that reaches
        // the same sink twice.
        Map<String, Map<String, Map<String, String>>> found = new LinkedHashMap<>();
        // One method name may serve several sinks: "loadUrl" is asked for by the
        // webview_loadurl tag and possibly by an explicit API in the same call.
        Map<String, List<SinkMatcher>> byMethodName = new HashMap<>();

        for (String token : requestedSinks(sinks)) {
            found.putIfAbsent(token, new LinkedHashMap<>());
            List<String> apis = DEFAULT_SINKS.get(token);
            for (String api : apis != null ? apis : List.of(token)) {
                int dot = api.lastIndexOf('.');
                String methodName = dot < 0 ? api : api.substring(dot + 1);
                String declClass = dot < 0 ? null : api.substring(0, dot);
                if (methodName.isEmpty()) {
                    continue;  // a malformed token matches nothing, but keeps its key
                }
                byMethodName.computeIfAbsent(methodName, k -> new ArrayList<>())
                        .add(new SinkMatcher(token, declClass));
            }
        }

        // Sink key -> in-APK types whose own declaration absorbed the dispatch.
        Map<String, Set<String>> resolvedInApk = new LinkedHashMap<>();

        ClspGraph clsp = jadx.getRoot().getClsp();
        DexReverseIndex index = reverseIndex();
        for (ClassNode cls : jadx.getRoot().getClasses()) {
            for (MethodNode mth : cls.getMethods()) {
                resolveInApkImplementations(index, clsp, cls, mth, byMethodName, found,
                        resolvedInApk);
                for (MethodInfo callee : mth.getUnresolvedUsed()) {
                    for (SinkMatcher matcher : matchersFor(byMethodName,
                            callee.getName(), callee.getAlias())) {
                        if (!receiverMatches(clsp, callee, matcher.declClass)) {
                            continue;
                        }
                        record(found, matcher.key, mth);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> truncated = new ArrayList<>();
        long returnedSites = 0;
        for (Map.Entry<String, Map<String, Map<String, String>>> e : found.entrySet()) {
            List<Map<String, String>> sites =
                    sortedByClassThenMethod(new ArrayList<>(e.getValue().values()));
            if (limit > 0 && sites.size() > limit) {
                truncated.add(marker(TRUNCATION_MARKER_PREFIX, "showing " + limit
                        + " of " + sites.size() + " call sites for \"" + e.getKey()
                        + "\". Ask for this sink alone with limit 0 for the rest."));
                sites = new ArrayList<>(sites.subList(0, limit));
            }
            returnedSites += sites.size();
            result.put(e.getKey(), sites);
        }
        if (!truncated.isEmpty()) {
            result.put(TRUNCATION_MARKER_PREFIX, truncated);
        }
        // The annotation is part of the response, so it is part of what the size
        // guard weighs; built before the notice so it can be, and inserted after
        // it so the key order does not move.
        Map<String, List<String>> named = null;
        if (!resolvedInApk.isEmpty()) {
            named = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : resolvedInApk.entrySet()) {
                named.put(e.getKey(), cappedTypes(e.getValue(), limit));
            }
        }

        // Measured over what is actually being handed back -- every sink's list
        // as returned, the truncation notices, and the resolved-in-APK block.
        //
        // The previous version summed only the sinks that were NOT cut, on the
        // reasoning that a cut sink already carries its own notice. That
        // conflated two different facts. "@truncated" says a list is missing
        // entries; "@oversized" says what you were handed is a file dump. Both
        // can be true at once, here and on every other count-capped tool -- see
        // OVERSIZED_MARKER_PREFIX, where this tool was for one round the named
        // exception rather than the first instance. What is specific to this one
        // is that the DEFAULT limit reaches it: the response is N lists, so every
        // sink can be cut, contribute its 100 entries to the payload, and
        // contribute zero to the total the guard weighed. Measured on
        // a large production APK with the limit left alone, the 60 bare method names of
        // a set of bare sink names -- legal tokens this tool's own docstring
        // documents -- return 1,289,611 bytes with 59 sinks cut and nothing in
        // the body admitting the size. The default catalogue is 214,944, well
        // under.
        String notice = oversizedNotice("findSinkCallSites", returnedSites,
                "call sites",
                jsonCostOf(result) + (named == null ? 0 : jsonCostOf(named)),
                sinks == null || sinks.isEmpty() ? "the default catalogue"
                        : "sinks=" + String.join(",", sinks),
                "Name the one sink you are reading and keep the default limit;"
                + " a bare method name matches every receiver in the APK.");
        if (notice != null) {
            result.put(OVERSIZED_MARKER_PREFIX, List.of(notice));
        }
        if (named != null) {
            result.put(RESOLVED_IN_APK_KEY, named);
        }
        return result;
    }

    /**
     * The same cap the call-site lists obey, applied to the types they were
     * found through -- otherwise the annotation outgrows the data.
     *
     * Measured on a large production APK: the bare name "getName" caps at 100 call sites but
     * is declared by 416 in-APK types, so an uncapped list made the bookkeeping
     * key three quarters of a 39 KB response. Truncation is stated the way
     * getAllClasses states it, as one final entry, because a list silently cut
     * to its first N reads as the whole truth.
     */
    private static List<String> cappedTypes(Set<String> types, int limit) {
        return cappedNames(new ArrayList<>(types), limit, "in-APK types",
                "Ask for this sink alone with limit 0 for the rest.");
    }

    /**
     * The trailing-marker cap, for the name lists that have no limit parameter
     * of their own to carry one.
     *
     * @param limit 0 or less means uncapped, the same clamp every limit here uses
     */
    private static List<String> cappedNames(List<String> all, int limit, String unit,
                                            String advice) {
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        List<String> capped = new ArrayList<>(all.subList(0, limit));
        capped.add(marker(TRUNCATION_MARKER_PREFIX, truncationText(limit, all.size(),
                unit, advice)));
        return capped;
    }

    /** One sentence, one word order, whichever list was cut. */
    private static String truncationText(int shown, int total, String unit, String advice) {
        return "showing " + shown + " of " + total + " " + unit + ". " + advice;
    }

    /**
     * The half of the advice that is the same on every tool that takes a limit.
     *
     * Kept out of the per-tool advice strings because it belongs to only one of
     * the two branches: telling a caller who just passed limit=0 to "pass limit
     * 0 for the rest" is the kind of notice that teaches a reader to stop
     * reading notices. The truncation branch prepends it; the oversize branch,
     * where the cap is already off, does not.
     */
    private static final String RAISE_LIMIT = "Raise limit, or pass limit 0 for the rest.";

    /**
     * A name list, capped when the caller gave a limit and told to admit its own
     * size when they disabled it.
     *
     * The pair belongs together because the two markers answer the two halves of
     * one question -- "are you missing some" and "is what you got a file dump" --
     * and a tool that emits only the first has a silent hole exactly where a
     * caller opted out of the cap. Every list-returning tool now routes through
     * this or {@link #guardedRefs}, so the one check
     * ({@value #TRUNCATION_MARKER_PREFIX} / {@value #OVERSIZED_MARKER_PREFIX} as
     * a trailing element) works everywhere without a per-tool exception list.
     *
     * BOTH branches weigh what is being handed back, which is the third hole in
     * this decision and the last one. A CAP IS ON THE COUNT AND A COUNT IS NOT A
     * SIZE: the cut branch used to return the moment it had cut, on the reading
     * that a cut answer is small by construction. It is small only at the
     * default cap. A caller who passes an explicit limit chooses the count and
     * nothing chooses the bytes -- measured on a large production APK,
     * getAllClasses(null, 100000) is 6,084,879 bytes, getXrefsToClass(Intrinsics,
     * 50000) is 9,518,250, and searchMethodByName("in", 100000) is 18,225,571,
     * every one of them wearing {@value #TRUNCATION_MARKER_PREFIX} alone and
     * saying nothing at all about its size.
     *
     * The two markers are not alternatives and the cut branch is where that is
     * easiest to get wrong; see {@link #OVERSIZED_MARKER_PREFIX}. The size
     * notice goes LAST, after the truncation one, in both branches: it is a
     * statement about the whole response including the truncation notice, so it
     * can only be made once that is in place, and it then sits in the same
     * position whether the answer was cut or not.
     *
     * The advice differs by branch on purpose and does not change here: the
     * truncation notice prepends {@link #RAISE_LIMIT}, and the size notice never
     * does -- telling a caller their 6 MB answer would be improved by raising
     * the limit is the notice that teaches a reader to stop reading notices.
     *
     * @param limit  0 or less means uncapped, the clamp every limit here uses
     * @param tool   names the call in the notice, so it says what to fix
     * @param unit   what the entries are, e.g. "resource file names"
     * @param scope  what was asked for, e.g. "className=kotlin.Unit"
     * @param advice the narrower call to make instead
     */
    private static List<String> guardedNames(List<String> all, int limit, String tool,
                                             String unit, String scope, String advice) {
        if (limit > 0 && all.size() > limit) {
            List<String> capped = new ArrayList<>(all.subList(0, limit));
            capped.add(marker(TRUNCATION_MARKER_PREFIX, truncationText(limit,
                    all.size(), unit, RAISE_LIMIT + " " + advice)));
            // Counted as what came back, not as what exists: "@oversized" is
            // about the payload in the caller's context window, and the real
            // total is already named by the notice above it.
            String cutNotice = oversizedNotice(tool, limit, unit, jsonBytes(capped),
                    scope, advice);
            if (cutNotice != null) {
                capped.add(cutNotice);
            }
            return capped;
        }
        String notice = oversizedNotice(tool, all.size(), unit, jsonBytes(all), scope, advice);
        if (notice == null) {
            return all;
        }
        List<String> annotated = new ArrayList<>(all);
        annotated.add(notice);
        return annotated;
    }

    /**
     * The size of the WHOLE answer, for a tool that returns several lists.
     *
     * A per-list guard bounds each list and nothing bounds their sum, so the
     * shape it cannot see is two lists just under the line: a response twice
     * {@link #OVERSIZED_RESPONSE_BYTES} wearing no notice at all. getCallees and
     * getOverrides were the two multi-list tools with no such guard --
     * getTypeHierarchy and findSinkCallSites have had one, and the asymmetry was
     * the only reason those two did not.
     *
     * Measured on a large production APK, neither can reach it today, and this says so rather
     * than claiming a repair: over the widely-implemented types a tracer
     * actually holds, the widest whole get_overrides answer is
     * java.lang.Runnable.run at 470,503 bytes uncapped -- 45% of the line -- and
     * over the 500 methods with the most dex instructions behind them the widest
     * whole get_callees answer is ServiceRegistry.&lt;clinit&gt; at 68,651. In
     * every one of those the big list holds over 99% of the bytes, so its own
     * guard would speak first anyway. What is closed here is the asymmetry: the
     * guarantee is meant to be a property of the server rather than of the
     * targets it has been pointed at, which is the same argument that put a byte
     * guard on the count-capped tools and on the truncation branch.
     *
     * The notice goes under {@value #OVERSIZED_MARKER_PREFIX}, the key
     * getTypeHierarchy and findSinkCallSites already use, and last, once every
     * list and every other marker is in place -- it is a statement about the
     * whole response including those.
     */
    static void wholeResponseNotice(Map<String, Object> result, String tool,
                                    long count, String unit, String scope,
                                    String advice) {
        if (result.containsKey(OVERSIZED_MARKER_PREFIX)) {
            return;
        }
        String notice = oversizedNotice(tool, count, unit, jsonCostOf(result), scope, advice);
        if (notice != null) {
            result.put(OVERSIZED_MARKER_PREFIX, List.of(notice));
        }
    }

    /**
     * {@link #guardedNames} for a tool whose whole answer is one string.
     *
     * A list can carry a notice as a trailing element and a map as a value under
     * a key. A bare string has neither, which is the reason these four tools
     * went uncapped and, worse, uncommentable: there was nowhere obvious to put
     * the notice, so nothing was put anywhere.
     *
     * There is somewhere obvious. The notice is the LEADING LINE, followed by a
     * blank one -- so the response is a string beginning '@', and the same check
     * that finds a notice in a list finds it here. That placement is not a
     * cosmetic choice: getMethodByName already wrote an ambiguity notice at the
     * front and prefixed it "// " to keep the result valid Java, which cost
     * exactly the property the notice existed to provide. Nothing downstream
     * compiles this text; a caller reads it. So the marker leads, bare.
     *
     * Leading rather than trailing for a second reason: what is cut is the END,
     * so a reader who stops early would never reach a trailing notice, and a
     * reader who is going to stop early is precisely the one being protected.
     *
     * Both halves of the pair, exactly as {@link #guardedNames} has them. The
     * uncapped branch used to return early -- {@code maxBytes <= 0} meant "hand
     * it over and say nothing" -- which made this the one family where opting
     * out of the cap also opted out of being told. It was not a small hole:
     * getResourceFile("lib/arm64-v8a/libapp.so", 0) is 150,606,477 bytes of tool
     * result, the largest response this server can produce, and it carried no
     * marker at all. limit=0 on a list has never behaved that way, and the name
     * of the test that blessed this claimed the two were the same.
     *
     * So an explicit escape hatch still returns everything -- that is what it is
     * for -- but a whole answer over {@link #OVERSIZED_RESPONSE_BYTES} says so,
     * in the same leading-line shape a cut answer uses.
     *
     * @param maxBytes 0 or less means uncapped, the same clamp every limit here
     *                 uses. Counted in {@link #jsonCost}, the bytes the answer
     *                 costs once serialised; getResourceFile spends the same
     *                 budget but never builds the string it would have cut.
     * @param tool     names the call in the notice, so it says what to fix
     * @param scope    what was asked for, e.g. the class or file name
     * @param advice   the narrower call to make instead
     */
    private static String guardedText(String text, int maxBytes, String tool,
                                      String scope, String advice) {
        if (text == null) {
            return null;
        }
        long cost = jsonCost(text);
        if (maxBytes > 0 && cost > maxBytes) {
            String notice = textTruncationMarker(tool, maxBytes,
                    text.length() + " characters", scope, advice);
            return notice + cutToJsonBudget(text, budgetLeftAfter(notice, maxBytes));
        }
        return withOversizedNotice(text, cost, tool, text.length(), "characters",
                scope, advice);
    }

    /**
     * {@link #oversizedNotice} for a tool whose whole answer is one string: a
     * complete answer that is itself a file dump, wearing the leading-line shape
     * {@link #guardedText} uses for a cut one.
     *
     * @param cost  what the text costs serialised, already computed by the caller
     * @param count how much of {@code unit} came back -- characters here, and
     *              archive bytes for getResourceFile, which measures a resource
     *              in the unit its own truncation notice already names
     */
    private static String withOversizedNotice(String text, long cost, String tool,
                                              long count, String unit,
                                              String scope, String advice) {
        String notice = oversizedNotice(tool, count, unit, cost, scope, advice);
        return notice == null ? text : notice + "\n\n" + text;
    }

    /**
     * The leading notice a cut string carries, blank line included.
     *
     * Shared by {@link #guardedText} and by getResourceFile, which measures its
     * total in real archive bytes rather than in characters -- hence {@code
     * total} arriving pre-worded rather than as a number and a unit. Conflating
     * the two units is not hypothetical: lib/arm64-v8a/libapp.so is a 48,628,640
     * byte entry that reaches a caller as 150,606,754 bytes of tool result, and
     * a notice naming one number as though it were the other would be wrong by a
     * factor of 3.1.
     */
    private static String textTruncationMarker(String tool, int budget, String total,
                                               String scope, String advice) {
        return marker(TRUNCATION_MARKER_PREFIX, tool + " cut " + scope + " to fit"
                + " maxBytes=" + budget + "; the whole answer is " + total + "."
                + " Pass maxBytes 0 for all of it, or a bigger maxBytes. " + advice)
                + "\n\n";
    }

    /**
     * What a string costs in a tool result, which is what a caller actually pays.
     *
     * Not its length. Every layer between here and the reader serialises this
     * as JSON -- Jackson on the way out of the daemon, json.dumps on the way out
     * of the MCP server -- and JSON escaping is not free on the content these
     * tools return. A control byte becomes a six-character \\u00XX escape, and
     * binary read as text is full of them.
     *
     * Measured, before this was accounted for: cutting a resource at 1,048,576
     * RAW BYTES produced a 2,729,428 character tool result for
     * assets/audience_network.dex and 2,471,105 for assets/MobNetV2.tflite. The
     * cap was real and the guarantee it was documented as giving was still
     * false, by 2.6x, on exactly the binary assets the guard exists for. So the
     * budget is spent in this currency rather than in raw length.
     *
     * BYTES, not characters. Counting escaped CHARACTERS was the second version
     * of this and it was wrong in the same shape as the first, just smaller: the
     * default get_resource_file("lib/arm64-v8a/libapp.so") response came back at
     * 1,853,835 bytes against an advertised 1 MB cap, 77% over, because a byte
     * that is not valid UTF-8 decodes to U+FFFD -- one character, three bytes on
     * the wire -- and a binary asset read as text is almost entirely those. The
     * parameter is called maxBytes and the reference states its unit as bytes of
     * tool result, so this counts what both of them say.
     *
     * Exact rather than approximate, in both directions. Jackson on the way out
     * of the daemon and json.dumps(ensure_ascii=False) on the way out of the MCP
     * server agree character for character: \\b \\t \\n \\f \\r are two-character
     * escapes, every other control byte is a six-character \\u00XX, the quote and
     * the backslash cost two, and everything else is written raw as UTF-8. So
     * the cap is an upper bound that is also tight -- charging 6 for a newline,
     * as the previous version did, cut decompiled Java about 12% early.
     */
    private static long jsonCost(CharSequence text) {
        long cost = 0;
        for (int i = 0; i < text.length(); i++) {
            cost += jsonCost(text.charAt(i));
        }
        return cost;
    }

    /**
     * What one character costs, in bytes, once escaped and UTF-8 encoded.
     *
     * The surrogate split is what keeps this exact AND keeps
     * {@link #cutToJsonBudget} from ever cutting a pair in half: a code point
     * above the BMP is four UTF-8 bytes and arrives here as two chars, so the
     * leading half is charged all four and the trailing half nothing. The pair
     * therefore crosses the budget together or not at all, and a cut can only
     * land in front of the leading half -- never between them, which would have
     * produced a lone surrogate that neither serialiser can encode.
     */
    private static int jsonCost(char c) {
        if (c < 0x20) {
            return c == '\b' || c == '\t' || c == '\n' || c == '\f' || c == '\r' ? 2 : 6;
        }
        if (c == '"' || c == '\\') {
            return 2;
        }
        if (c < 0x80) {
            return 1;
        }
        if (c < 0x800) {
            return 2;
        }
        if (Character.isHighSurrogate(c)) {
            return 4;
        }
        return Character.isLowSurrogate(c) ? 0 : 3;
    }

    /**
     * What is left of the budget once the notice itself is paid for.
     *
     * The notice is part of the response, so it spends the budget too -- and it
     * spends it in the same escaped currency, which is not its length: it ends
     * in a blank line and quotes the name it is about, and each of those
     * characters costs 6 and 2. Subtracting the raw length instead put the
     * first version of this guard a few characters over its own cap on every
     * truncated answer.
     */
    private static int budgetLeftAfter(String notice, int maxBytes) {
        return (int) Math.max(0, maxBytes - jsonCost(notice));
    }

    /** The longest prefix of {@code text} that costs at most {@code budget}. */
    private static String cutToJsonBudget(String text, int budget) {
        long cost = 0;
        for (int i = 0; i < text.length(); i++) {
            cost += jsonCost(text.charAt(i));
            if (cost > budget) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * {@link #guardedNames} for the {className, methodName, signature} entry
     * lists the xref and callee tools return.
     *
     * A list of objects cannot carry a bare string, so the notice arrives as a
     * trailing one-key entry whose key is the marker name and whose value is the
     * same self-describing sentence -- the shape
     * {@value #AMBIGUOUS_MARKER_PREFIX} already uses in these lists. Every real
     * entry has a "className"; a bookkeeping entry has exactly one key and it
     * starts with '@'.
     *
     * Both branches weigh what is handed back, for the reason and in the order
     * {@link #guardedNames} gives. This family is where it bites hardest,
     * because reference counts are heavy-tailed: getXrefsToClass on
     * kotlin.jvm.internal.Intrinsics with limit 50000 is 9,518,250 bytes.
     */
    private static List<Map<String, String>> guardedRefs(List<Map<String, String>> all,
            int limit, String tool, String unit, String scope, String advice) {
        if (limit > 0 && all.size() > limit) {
            List<Map<String, String>> capped = new ArrayList<>(all.subList(0, limit));
            capped.add(Map.of(TRUNCATION_MARKER_PREFIX, marker(TRUNCATION_MARKER_PREFIX,
                    truncationText(limit, all.size(), unit,
                            RAISE_LIMIT + " " + advice))));
            String cutNotice = oversizedNotice(tool, limit, unit, refBytes(capped),
                    scope, advice);
            if (cutNotice != null) {
                capped.add(Map.of(OVERSIZED_MARKER_PREFIX, cutNotice));
            }
            return capped;
        }
        String notice = oversizedNotice(tool, all.size(), unit, refBytes(all), scope, advice);
        if (notice == null) {
            return all;
        }
        List<Map<String, String>> annotated = new ArrayList<>(all);
        annotated.add(Map.of(OVERSIZED_MARKER_PREFIX, notice));
        return annotated;
    }

    /**
     * JSON cost of an entry list, the same estimate {@link #jsonBytes} makes for
     * a name list: two braces per entry, and per pair the key, its two quotes,
     * the colon, the value, its two quotes and one comma -- key + value + 6.
     *
     * The comma each pair is charged for is what pays for the comma between
     * entries too, so there is deliberately no separate separator term: adding
     * one double-counts by 159 KB over the widest list here. Measured against
     * the real serialisation of that list -- 53,181 xrefs to
     * kotlin.jvm.internal.Intrinsics -- this estimates 10,030,140 bytes against
     * an actual 10,030,766, 0.006% low.
     */
    private static long refBytes(List<Map<String, String>> entries) {
        long bytes = 0;
        for (Map<String, String> entry : entries) {
            bytes += jsonCostOf(entry);
        }
        return bytes;
    }

    /**
     * The half of a sink's call surface that leaves no unresolved edge.
     *
     * If `mth` is itself an in-APK declaration satisfying a requested sink --
     * an override in the app's own subclass, or the sink's declaring class
     * shipped inside the APK -- then every call made through that static type
     * resolved to `mth` and never reached getUnresolvedUsed(). Its callers are
     * call sites of the sink, so recovering them is a lookup per implementation
     * rather than a second pass.
     *
     * The callers come from the dex reverse index, not from getUseIn(). This
     * method's first version read getUseIn() on the claim that it, unlike
     * getUsed(), was the stable choice. That claim was wrong and it turned a
     * previously order-independent sweep into an order-dependent one: 0783597
     * measured 3,593 caller edges lost and 834 gained on a large production APK after 3,000
     * classes, because ClassModifier hands synthetic methods to
     * UsageInfoVisitor.replaceMethodUsage, which EMPTIES the replaced method's
     * useIn. The other half of this sweep -- getUnresolvedUsed() -- never moves,
     * so this was the only order-dependent half of a sink analysis.
     *
     * A deeper subclass that does NOT redeclare the method needs nothing extra:
     * a call through its static type resolves up the chain to this same node, so
     * the index already names the caller.
     */
    private void resolveInApkImplementations(DexReverseIndex index, ClspGraph clsp,
            ClassNode cls, MethodNode mth,
            Map<String, List<SinkMatcher>> byMethodName,
            Map<String, Map<String, Map<String, String>>> found,
            Map<String, Set<String>> resolvedInApk) {
        MethodInfo info = mth.getMethodInfo();
        // Raw dex name and PINNED alias -- not the live one. An in-APK receiver
        // can be renamed, so a caller naming the sink may hold either of these
        // two; the live alias is a third spelling that only exists once the
        // class has decompiled, and matching it would make a sink sweep return
        // different call sites depending on what the daemon happened to
        // decompile earlier. Cold the pinned and live aliases are the same
        // string, so this matches exactly what the sweep always matched.
        for (SinkMatcher matcher : matchersFor(byMethodName, info.getName(),
                pinnedNames.aliasOf(mth))) {
            if (!ownerMatches(clsp, cls, matcher.declClass)) {
                continue;
            }
            List<MethodNode> callers = index.callersOf(mth);
            if (callers.isEmpty()) {
                continue;  // it swallows nothing, so naming it would be noise
            }
            resolvedInApk.computeIfAbsent(matcher.key, k -> new TreeSet<>())
                    .add(classDisplayName(cls));
            for (MethodNode user : callers) {
                record(found, matcher.key, user);
            }
        }
    }

    /**
     * The sinks a method name can satisfy.
     *
     * A framework method is never renamed, but an in-APK receiver's method could
     * be, and a caller may hold that name from get_class_source -- so the alias
     * is tried too, and only when it differs.
     */
    private static List<SinkMatcher> matchersFor(Map<String, List<SinkMatcher>> byMethodName,
            String... names) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            List<SinkMatcher> matchers = byMethodName.get(name);
            if (matchers != null) {
                return matchers;
            }
        }
        return List.of();
    }

    /** One calling method, filed under one sink key, deduped by class+signature. */
    private void record(Map<String, Map<String, Map<String, String>>> found,
            String key, MethodNode caller) {
        Map<String, String> entry = refEntry(caller);
        found.get(key).putIfAbsent(
                entry.get("className") + "#" + entry.get("signature"), entry);
    }

    /** Nothing asked for means the catalogue, so an agent can call this cold. */
    private static List<String> requestedSinks(List<String> sinks) {
        List<String> tokens = new ArrayList<>();
        if (sinks != null) {
            for (String s : sinks) {
                if (s != null && !s.trim().isEmpty()) {
                    tokens.add(s.trim());
                }
            }
        }
        return tokens.isEmpty() ? new ArrayList<>(DEFAULT_SINKS.keySet()) : tokens;
    }

    /**
     * Does this call's receiver satisfy the sink's declaring class?
     *
     * ClspGraph.isImplements is a lookup in a map load() already built -- it
     * cannot throw, and it answers false for a class it has never heard of, so
     * unknown receivers need no guard. The alias comparison is there for a
     * caller who names an in-APK class by the deobfuscated name it read out of
     * get_class_source; subtype resolution itself works on dex names, which is
     * what the classpath graph is keyed by.
     */
    private static boolean receiverMatches(ClspGraph clsp, MethodInfo callee, String declClass) {
        if (declClass == null) {
            return true;
        }
        String receiver = callee.getDeclClass().getFullName();
        return receiver.equals(declClass)
                || callee.getDeclClass().getAliasFullName().equals(declClass)
                || clsp.isImplements(receiver, declClass);
    }

    /**
     * Same question as receiverMatches, asked of a class that DECLARES the
     * method rather than of a call's receiver.
     *
     * The equality arm is not redundant with isImplements: isImplements(X, X) is
     * false, and X is precisely the bundled-SDK case -- androidx's own
     * Fragment.startActivity, support-v4's LocalBroadcastManager.sendBroadcast.
     */
    private static boolean ownerMatches(ClspGraph clsp, ClassNode cls, String declClass) {
        if (declClass == null) {
            return true;
        }
        String owner = cls.getClassInfo().getFullName();
        return owner.equals(declClass)
                || cls.getClassInfo().getAliasFullName().equals(declClass)
                || clsp.isImplements(owner, declClass);
    }

    /**
     * Components a link or a co-located app can reach, with resource references
     * resolved to literals.
     *
     * Two tiers, in this order, matching the analysis client's manifest.py so the
     * result can be handed straight to the client's component store:
     *   1. the MAIN/LAUNCHER activity, anything with a BROWSABLE filter, and
     *      activity-aliases pointing at a kept target -- the link-reachable surface
     *   2. exported components with no BROWSABLE filter, which no link reaches but
     *      any app on the device can start directly
     *
     * Tier 2 is appended rather than merged so a caller taking the first N
     * naturally covers the link-reachable surface first.
     *
     * The resolution is the point. A manifest may declare
     * android:scheme="@string/fb_login_protocol_scheme"; an agent needs the
     * literal an attacker would type, and a reference is unusable.
     *
     * This and {@link #getExportedComponentsAsMap()} return the SAME record
     * shape and differ only in which components they keep; see
     * {@link #parseAllComponents()} for the record and
     * {@link #getExportedComponentsAsMap()} for how the two populations relate.
     */
    public List<Map<String, Object>> getDeeplinkComponents() {
        checkLoaded();
        if (manifestContent == null) {
            throw new RuntimeException("AndroidManifest.xml not loaded");
        }

        List<Map<String, Object>> all = parseAllComponents();

        List<Map<String, Object>> kept = new ArrayList<>();
        Set<String> keptNames = new LinkedHashSet<>();
        for (Map<String, Object> c : all) {
            if (Boolean.TRUE.equals(c.get("is_main")) || Boolean.TRUE.equals(c.get("is_browsable"))) {
                kept.add(c);
                keptNames.add((String) c.get("name"));
            }
        }
        // An alias re-exports its target's class under a new filter, so an alias
        // pointing at something already kept is itself part of the surface.
        for (Map<String, Object> c : all) {
            String aliasOf = (String) c.get("alias_of");
            if (aliasOf != null && keptNames.contains(aliasOf) && keptNames.add((String) c.get("name"))) {
                kept.add(c);
            }
        }
        // Tier 2.
        for (Map<String, Object> c : all) {
            if (keptNames.contains(c.get("name"))) {
                continue;
            }
            if (isRogueAppReachable(c)) {
                kept.add(c);
                keptNames.add((String) c.get("name"));
            }
        }
        return cappedComponents(kept, "getDeeplinkComponents",
                "link-reachable components");
    }

    /**
     * Exported, but no link reaches it -- any app on the device can send it an
     * intent directly.
     *
     * A component exported only because it carries MAIN/LAUNCHER is excluded:
     * that is the launcher entry, already kept by tier 1.
     */
    @SuppressWarnings("unchecked")
    private static boolean isRogueAppReachable(Map<String, Object> c) {
        if (Boolean.TRUE.equals(c.get("is_main")) || Boolean.TRUE.equals(c.get("is_browsable"))
                || !Boolean.TRUE.equals(c.get("exported"))) {
            return false;
        }
        List<Map<String, Object>> filters = (List<Map<String, Object>>) c.get("intent_filters");
        Set<String> actions = new LinkedHashSet<>();
        for (Map<String, Object> f : filters) {
            actions.addAll((List<String>) f.get("actions"));
        }
        actions.remove("android.intent.action.MAIN");
        // A non-launcher action, or an explicit export with no filter at all,
        // which is reached by class name.
        return !actions.isEmpty() || filters.isEmpty();
    }

    /**
     * Every declared component, keep-rules not yet applied -- the one manifest
     * parse behind both components tools.
     *
     * There used to be two. {@code parseManifest}/{@code analyzeComponent} built
     * get_exported_components out of a second walk of the same XML, and the two
     * walks disagreed: that one never looked at {@code activity-alias}, compared
     * android:exported case-sensitively, defaulted a filter-less provider to NOT
     * exported, resolved no resource references, and flattened each filter to a
     * prose string ("Action: …, Category: …, Data: scheme=x "). Measured on
     * a large production APK it also disagreed about the population: 45 components against
     * this parse's 47, the two extras being BROWSABLE activities carrying
     * android:exported="false". One question, two answers, depending on which
     * tool you happened to call.
     *
     * THE RECORD. Every component carries name, type, is_main, is_browsable,
     * exported, exported_reason and intent_filters -- always, on every entry.
     * permission and alias_of are written only when the manifest declares them,
     * because they are the two whose absence is not a value: the consumer's
     * ComponentInput types them as optional and its records read cleaner
     * without them. Measured on a large production APK, 7 of 47 components declare a
     * permission and none is an alias.
     */
    private List<Map<String, Object>> parseAllComponents() {
        List<Map<String, Object>> components = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            doc.getDocumentElement().normalize();

            String pkg = doc.getDocumentElement().getAttribute("package");
            String[] tags = {"activity", "activity-alias", "service", "receiver", "provider"};

            for (String tag : tags) {
                NodeList nodes = doc.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    if (nodes.item(i).getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    Element el = (Element) nodes.item(i);
                    String name = qualify(el.getAttribute("android:name"), pkg);
                    if (name.isEmpty()) {
                        continue;
                    }

                    List<Map<String, Object>> filters = readIntentFilters(el);
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("name", name);
                    c.put("type", tag);
                    c.put("is_main", isMain(filters));
                    c.put("is_browsable", isBrowsable(filters));

                    String explicit = el.getAttribute("android:exported");
                    boolean exported;
                    String reason;
                    if (explicit != null && !explicit.isEmpty()) {
                        exported = "true".equalsIgnoreCase(explicit);
                        reason = "explicit android:exported";
                    } else if (tag.equals("provider")) {
                        exported = true;
                        reason = "provider with no android:exported (implicit, pre-API-17)";
                    } else if (!filters.isEmpty()) {
                        exported = true;
                        reason = "default exported via intent-filter (pre-API-31)";
                    } else {
                        exported = false;
                        reason = "no intent-filter and no android:exported";
                    }
                    c.put("exported", exported);
                    c.put("exported_reason", reason);

                    String permission = el.getAttribute("android:permission");
                    if (permission != null && !permission.isEmpty()) {
                        c.put("permission", resolveResourceRef(permission));
                    }
                    if (tag.equals("activity-alias")) {
                        String target = el.getAttribute("android:targetActivity");
                        if (target != null && !target.isEmpty()) {
                            c.put("alias_of", qualify(target, pkg));
                        }
                    }
                    c.put("intent_filters", filters);
                    components.add(c);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error parsing manifest: " + e.getMessage(), e);
        }
        return components;
    }

    /**
     * Every attribute an {@code <intent-filter><data>} element can carry, in the
     * order the entries report them.
     *
     * All eight are written on EVERY data entry, empty where the element does
     * not declare them; see {@link #readIntentFilters}.
     */
    private static final String[] DATA_ATTRS = {
            "scheme", "host", "port", "path",
            "pathPrefix", "pathPattern", "pathSuffix", "mimeType",
    };

    /**
     * The filters of one component, each data element as a FIXED set of keys.
     *
     * Every entry carries all eight of {@link #DATA_ATTRS} plus autoVerify, and
     * an attribute the element does not declare is present and empty rather
     * than absent. That is the same fix the xref family got, for the same
     * reason and one tool earlier in an analysis: an intent-filter data element
     * genuinely may declare only a scheme, or only a host, or only a mimeType,
     * so the keys that used to be written were the ones that happened to have a
     * value and NO key was common to every entry. Measured on a large production APK, its 75
     * data entries came back in NINE different key sets with no key present on
     * all of them -- scheme on 60, host on 46, autoVerify on 39, pathPrefix on
     * 14, path on 6, and port, pathPattern, pathSuffix and mimeType on none.
     * A caller reading entry["scheme"] on the first tool an analysis calls got a
     * KeyError on 15 of those 75.
     *
     * EMPTY MEANS NOT DECLARED, and that is a fact rather than a gap: an
     * intent-filter matches on the attributes it declares, so a data element
     * with a scheme and no host matches that scheme on any host. It does not
     * mean "any" for the URI a caller builds -- a filter declaring no scheme
     * matches no browsable link at all -- so the two readings are distinguished
     * by which attribute is empty, not by the emptiness.
     *
     * A {@code <data/>} element declaring nothing at all is still skipped. It
     * contributes no matching rule, and an entry of nine empties would be a row
     * of the table with no fact in it.
     */
    private List<Map<String, Object>> readIntentFilters(Element component) {
        List<Map<String, Object>> filters = new ArrayList<>();
        NodeList filterNodes = component.getElementsByTagName("intent-filter");

        for (int i = 0; i < filterNodes.getLength(); i++) {
            Element f = (Element) filterNodes.item(i);
            boolean autoVerify = "true".equalsIgnoreCase(f.getAttribute("android:autoVerify"));

            List<String> actions = new ArrayList<>();
            NodeList actionNodes = f.getElementsByTagName("action");
            for (int j = 0; j < actionNodes.getLength(); j++) {
                String n = ((Element) actionNodes.item(j)).getAttribute("android:name");
                if (n != null && !n.isEmpty()) {
                    actions.add(n);
                }
            }

            List<String> categories = new ArrayList<>();
            NodeList catNodes = f.getElementsByTagName("category");
            for (int j = 0; j < catNodes.getLength(); j++) {
                String n = ((Element) catNodes.item(j)).getAttribute("android:name");
                if (n != null && !n.isEmpty()) {
                    categories.add(n);
                }
            }

            List<Map<String, Object>> data = new ArrayList<>();
            NodeList dataNodes = f.getElementsByTagName("data");
            for (int j = 0; j < dataNodes.getLength(); j++) {
                Element d = (Element) dataNodes.item(j);
                Map<String, Object> entry = new LinkedHashMap<>();
                boolean declaresSomething = false;
                for (String attr : DATA_ATTRS) {
                    String v = d.getAttribute("android:" + attr);
                    if (v == null || v.isEmpty()) {
                        entry.put(attr, "");
                        continue;
                    }
                    declaresSomething = true;
                    // The whole point of the tool: a scheme or host declared
                    // as @string/... arrives here as a literal.
                    entry.put(attr, resolveResourceRef(v));
                }
                if (!declaresSomething) {
                    continue;
                }
                // autoVerify is declared once on the filter; the caller's model
                // carries it per data entry. Always present, false being the
                // empty value a boolean has.
                entry.put("autoVerify", autoVerify);
                data.add(entry);
            }

            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("actions", actions);
            filter.put("categories", categories);
            filter.put("data", data);
            filters.add(filter);
        }
        return filters;
    }

    private static boolean isMain(List<Map<String, Object>> filters) {
        for (Map<String, Object> f : filters) {
            if (listOf(f, "actions").contains("android.intent.action.MAIN")
                    && listOf(f, "categories").contains("android.intent.category.LAUNCHER")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBrowsable(List<Map<String, Object>> filters) {
        for (Map<String, Object> f : filters) {
            if (listOf(f, "categories").contains("android.intent.category.BROWSABLE")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Map<String, Object> filter, String key) {
        return (List<String>) filter.get(key);
    }

    /** name -> value for string resources; built once, on first reference. */
    private Map<String, String> stringResources;

    /**
     * Turn "@string/deeplink_scheme" into the literal it names.
     *
     * Manifest attributes are frequently declared as resource references rather
     * than literals, and an unresolved one is unusable: an agent cannot type
     * "@string/fb_login_protocol_scheme" into a URL. Values that are already
     * literal, and references we cannot resolve, are returned unchanged -- a
     * wrong guess would be worse than an honest reference.
     */
    private String resolveResourceRef(String value) {
        if (value == null || !value.startsWith("@")) {
            return value;
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            // "@7F12038D" -- an unnamed id we have no key for.
            return value;
        }
        String type = value.substring(1, slash);
        String key = value.substring(slash + 1);
        if (!type.equals("string")) {
            // Only strings can be a scheme, host or path.
            return value;
        }
        String resolved = stringResources().get(key);
        return resolved != null ? resolved : value;
    }

    /**
     * String resources, read from the decoded resource table.
     *
     * The APK ships resources.arsc as binary; jadx decodes it into per-locale
     * values files. The default (unqualified) res/values/strings.xml is the one
     * that matters -- a scheme is not localised, and reading a translation would
     * be actively wrong.
     */
    private synchronized Map<String, String> stringResources() {
        if (stringResources != null) {
            return stringResources;
        }
        Map<String, String> strings = new HashMap<>();
        try {
            for (ResourceFile rf : jadx.getResources()) {
                if (!rf.getOriginalName().equals("resources.arsc")) {
                    continue;
                }
                ResContainer table = rf.loadContent();
                if (table == null) {
                    break;
                }
                for (ResContainer sub : table.getSubFiles()) {
                    // Default locale only: "res/values/strings.xml", never
                    // "res/values-fr/strings.xml".
                    if (!"res/values/strings.xml".equals(sub.getName())) {
                        continue;
                    }
                    ICodeInfo text = sub.getText();
                    if (text != null) {
                        parseStringsXml(text.getCodeStr(), strings);
                    }
                }
                break;
            }
        } catch (Exception e) {
            // A malformed or absent resource table must not take the whole
            // inventory down; unresolved references stay as references.
            System.err.println("Could not read string resources: " + e.getMessage());
        }
        stringResources = strings;
        return stringResources;
    }

    private static final Pattern STRING_ENTRY =
            Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    private static void parseStringsXml(String xml, Map<String, String> into) {
        Matcher m = STRING_ENTRY.matcher(xml);
        while (m.find()) {
            into.putIfAbsent(m.group(1), unescapeXml(m.group(2)));
        }
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("\\'", "'").replace("&amp;", "&");
    }

    private static String qualify(String name, String pkg) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.startsWith(".")) {
            return pkg + name;
        }
        if (!name.contains(".") && pkg != null && !pkg.isEmpty()) {
            return pkg + "." + name;
        }
        return name;
    }

    /**
     * The methods overriding a method, and the base methods it overrides.
     *
     * This is what resolves interface dispatch to concrete implementations: an
     * xref says a router calls IHandler.handle, and this says which classes
     * actually implement it.
     *
     * The data comes from AType.METHOD_OVERRIDE, attached by OverrideMethodVisitor
     * -- a PASS, so unlike everything else these tools read it is jadx's derived
     * model rather than the dex, and it does not exist until something asks for
     * it.
     *
     * This used to say load() had already attached it everywhere: "18516 methods
     * carrying the attribute on a large production APK before any explicit call, unchanged
     * after". The first half is right and the second is not. Re-measured by
     * sampling methods that had NO attribute cold and asking again once
     * unrelated classes decompiled: 44 of 200 had gained one, every inspected
     * case an attributePresent false -> true flip on a synthetic enum $values
     * method. So the cold answer was not a different answer, it was an
     * unfinished one -- WARM is the truth here, and pinning the cold reading
     * would have pinned a wrong one.
     *
     * The fix is therefore to finish the work instead of recording it early:
     * the target's class is put through the process passes before its attribute
     * is read. That is bounded -- one class, no code generation -- and it makes
     * the answer independent of what the daemon happened to have decompiled.
     *
     * "attributePresent" is reported rather than assumed. If a future jadx
     * stopped attaching the attribute, every result would silently become "no
     * overrides", which is worse than an error because it looks like an answer.
     * It describes THIS answer -- the method asked about carried the attribute,
     * or an implementation was found -- and no longer whether some unrelated
     * class the daemon had warmed happened to carry one.
     *
     * "overriddenBy" is still a sweep over attributes other classes carry, and
     * a class nothing has processed yet carries none. Making that list complete
     * would mean processing all 141,461 classes on the reference APK, so it is
     * left as it is and named here rather than papered over.
     */
    public Map<String, Object> getOverrides(String className, String methodName) {
        return getOverrides(className, methodName, null);
    }

    /**
     * @param signature optional, narrowing an overloaded name to one method;
     *                  see {@link #resolveMethods(ClassNode, String, String)}
     */
    public Map<String, Object> getOverrides(String className, String methodName,
                                            String signature) {
        return getOverrides(className, methodName, signature, 0);
    }

    /**
     * @param limit at most this many entries in "overriddenBy" and in
     *              "baseMethods"; 0 or less means uncapped.
     *
     *              This one bites on the query the tool exists for. Asking
     *              java.lang.Runnable.run on a large production APK returns 7,188
     *              implementations and 470,517 bytes -- and a widely-implemented
     *              framework interface is exactly what a tracer holds when it is
     *              trying to resolve dispatch.
     */
    public Map<String, Object> getOverrides(String className, String methodName,
                                            String signature, int limit) {
        checkLoaded();

        ClassNode cls = findClassNode(className);
        Set<String> baseMethods = new TreeSet<>();
        Set<String> overriddenBy = new TreeSet<>();
        boolean attributePresent = false;
        // Two class names, for the same reason there are two method names below.
        // resolvedName drives the override-chain comparison and must stay in the
        // vocabulary detailsName() reads the chain in -- the plain alias. The
        // '$' display spelling would match nothing there, silently emptying
        // overriddenBy for exactly the classes that need disambiguating.
        String resolvedName = className;
        String reportedName = className;
        // The scan below matches on the alias, and the caller may have handed us
        // a raw dex name or a fully-qualified one. Normalise once, here, so the
        // scan, the override chain comparison and the echoed answer all speak
        // the same vocabulary as the rest of the server.
        int methodDot = methodName == null ? -1 : methodName.lastIndexOf('.');
        String resolvedMethod = methodDot < 0 ? methodName : methodName.substring(methodDot + 1);
        // What gets REPORTED, which is the pinned vocabulary. Kept apart from
        // resolvedMethod, which drives the override-chain comparison below and
        // has to stay in the same vocabulary as detailsName() reads the chain
        // in -- the live one. Mixing the two would silently stop matching for
        // exactly the drifted methods this change exists for.
        String reportedMethod = resolvedMethod;
        String ambiguity = null;

        if (cls != null) {
            resolvedName = cls.getClassInfo().getAliasFullName();
            reportedName = classDisplayName(cls);
            List<MethodNode> targets = resolveMethods(cls, methodName, signature);
            if (targets.isEmpty()) {
                throw noSuchMethod(cls, methodName, signature);
            }
            resolvedMethod = targets.get(0).getMethodInfo().getAlias();
            reportedMethod = pinnedNames.aliasOf(targets.get(0));
            ambiguity = ambiguityNotice(methodName, targets);
            // AType.METHOD_OVERRIDE is attached by OverrideMethodVisitor, a
            // PASS -- so cold it is absent not because the method overrides
            // nothing but because nobody has asked jadx to work it out yet.
            // Measured on a large production APK: 44 of 200 sampled methods that had no
            // attribute cold had one once unrelated classes decompiled, every
            // inspected case an attributePresent false -> true flip. WARM is
            // the true answer, so pinning the cold one would pin a wrong one;
            // the fix is to stop asking before the answer exists.
            //
            // forceProcess() runs the process pipeline for this class only.
            // That is where OverrideMethodVisitor lives, and it is NOT code
            // generation, so this costs a fraction of a decompile and does not
            // touch the rest of the APK. (ClassNode.ensureProcessed() is an
            // assertion, not an action -- it throws when the class is cold.)
            processForAttributes(cls);
            for (MethodNode target : targets) {
                MethodOverrideAttr attr = target.get(AType.METHOD_OVERRIDE);
                if (attr == null) {
                    continue;
                }
                attributePresent = true;
                for (IMethodDetails base : attr.getBaseMethods()) {
                    baseMethods.add(reportedDetailsName(base));
                }
                for (IMethodDetails m : attr.getOverrideList()) {
                    baseMethods.add(reportedDetailsName(m));
                }
            }
        } else if (!jadx.getRoot().getClsp().isClsKnown(className)) {
            throw new RuntimeException("Class not found: " + className);
        }

        // Implementations are found the same way whether the declaring type is
        // in the APK or not: ask every method that carries an override attribute
        // whether this class+method is in its chain.
        //
        // This is the query a tracer actually starts from -- following a WebView
        // router means holding android.webkit.WebViewClient, which is not in the
        // dex at all. Restricting to in-APK declarations would reject exactly
        // the lookup that resolves dispatch.
        //
        // The scan is over dex metadata and attributes only, never decompiled
        // source; a full walk of the 141461-class APK measures in tens of ms.
        for (ClassNode candidate : jadx.getRoot().getClasses()) {
            for (MethodNode mth : candidate.getMethods()) {
                // Either vocabulary gets a candidate through the cheap filter;
                // declaresOverrideOf below is what actually decides, and it
                // reads the chain in the live one.
                if (!mth.getMethodInfo().getAlias().equals(resolvedMethod)
                        && !pinnedNames.aliasOf(mth).equals(resolvedMethod)) {
                    continue;
                }
                MethodOverrideAttr attr = mth.get(AType.METHOD_OVERRIDE);
                if (attr == null) {
                    continue;
                }
                // Deliberately NOT setting attributePresent here. It used to be
                // set by whichever unrelated class in this sweep happened to be
                // warm, which made the flag a report on the daemon's history
                // rather than on the method asked about.
                if (declaresOverrideOf(attr, resolvedName, resolvedMethod)) {
                    // A match IS override data about the question asked, so it
                    // counts -- and it is the only signal available when the
                    // declaring type is a framework class with no ClassNode to
                    // process.
                    attributePresent = true;
                    overriddenBy.add(pinnedNames.fullNameOf(mth));
                }
            }
        }
        // A class does not override itself. Matched on resolvedName, not the
        // display name: overriddenBy is built from PinnedMethodNames.fullNameOf,
        // which spells the class with getAliasFullName().
        if (cls != null) {
            String ownPrefix = resolvedName + ".";
            overriddenBy.removeIf(m -> m.startsWith(ownPrefix));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", reportedName);
        result.put("methodName", reportedMethod);
        result.put("inApk", cls != null);
        result.put("attributePresent", attributePresent);
        String scope = reportedName + "." + reportedMethod;
        List<String> bases = guardedNames(new ArrayList<>(baseMethods), limit,
                "getOverrides", "base methods", scope, OVERRIDE_ADVICE);
        List<String> impls = guardedNames(new ArrayList<>(overriddenBy), limit,
                "getOverrides", "implementations", scope, OVERRIDE_ADVICE);
        result.put("baseMethods", bases);
        result.put("overriddenBy", impls);
        if (ambiguity != null) {
            result.put(AMBIGUOUS_MARKER_PREFIX, List.of(ambiguity));
        }
        wholeResponseNotice(result, "getOverrides", bases.size() + impls.size(),
                "method names", scope, OVERRIDE_ADVICE);
        return result;
    }

    /**
     * Run the process passes for one class, so its dex-derived attributes exist.
     *
     * Best effort: a class jadx cannot process is a class we answer about with
     * whatever attributes it does have, which is exactly the old behaviour. The
     * point is to remove the dependence on daemon warmth, not to add a new way
     * for a query to fail.
     */
    private void processForAttributes(ClassNode cls) {
        try {
            if (!cls.checkProcessed()) {
                jadx.getRoot().getProcessClasses().forceProcess(cls);
            }
        } catch (Throwable ignored) {
            // Answer from what is there rather than turn a lookup into an error.
        }
    }

    /** What to do when an override list is wider than the cap. */
    private static final String OVERRIDE_ADVICE =
            "A method this widely implemented is a framework callback rather"
            + " than a dispatch target -- narrow by asking about the interface"
            + " the caller actually holds, or use search_classes_by_keyword.";

    /** Does this method's override chain name the given class and method? */
    private static boolean declaresOverrideOf(MethodOverrideAttr attr, String className, String methodName) {
        String wanted = className + "." + methodName;
        for (IMethodDetails base : attr.getBaseMethods()) {
            if (detailsName(base).equals(wanted)) {
                return true;
            }
        }
        for (IMethodDetails m : attr.getOverrideList()) {
            if (detailsName(m).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IMethodDetails covers both in-APK methods and framework ones.
     *
     * The LIVE alias, deliberately: this is the vocabulary jadx builds the
     * override chain in, so it is the vocabulary a chain comparison has to read
     * it in. It is NOT what gets reported -- see {@link #reportedDetailsName}.
     */
    private static String detailsName(IMethodDetails details) {
        MethodInfo info = details.getMethodInfo();
        return info != null ? info.getAliasFullName() : String.valueOf(details);
    }

    /**
     * The same chain entry in the vocabulary every other tool speaks: pinned
     * method name, unambiguous class name.
     *
     * baseMethods used to be emitted as live aliases, which is a vocabulary
     * nothing else on this server reports in and one that moves -- so a base
     * method a caller read out of get_overrides could fail to resolve in
     * get_method_by_name, and two sessions could record two spellings of it.
     *
     * A base method declared outside the APK has no MethodNode to pin, and
     * falls back to what jadx knows. That is honest: it is a framework name,
     * and no tool here claims to resolve one.
     */
    private String reportedDetailsName(IMethodDetails details) {
        MethodInfo info = details.getMethodInfo();
        if (info == null) {
            return String.valueOf(details);
        }
        MethodNode node = jadx.getRoot().resolveMethod(info);
        if (node != null) {
            return classDisplayName(node.getParentClass()) + "." + pinnedNames.aliasOf(node);
        }
        return classDisplayName(info.getDeclClass()) + "." + info.getAlias();
    }

    /**
     * Supertypes, interfaces, subclasses and nesting for a type.
     *
     * Handles two cases that look the same to a caller but are not. A type
     * declared in the APK has a ClassNode and answers everything. A framework or
     * SDK type -- android.app.Activity is the common one -- has no ClassNode at
     * all, and reporting an empty hierarchy for it would be indistinguishable
     * from "this class has no supertypes". ClspGraph carries jadx's model of
     * types outside the APK, so those still resolve; "inApk" tells the caller
     * which kind of answer it is holding.
     *
     * Every name in the answer is a name this server accepts back. That took
     * doing here, because superClass, interfaces and superTypes are read off
     * the dex type system rather than off ClassNodes: {@code ArgType.getObject()}
     * and ClspGraph's keys are RAW dex names -- pre-deobfuscation, '$' between
     * an outer class and an inner one -- and this tool used to hand them out
     * unchanged. That was worse than a rejection. Fed back, such a name misses
     * every tier of {@link #resolveClassNodes(String)} that a renamed class has,
     * so the class fell through to the not-in-the-APK branch and
     * get_type_hierarchy reported {@code inApk:false} for a class sitting in the
     * dex. {@link #typeDisplayName(ArgType)} and {@link #displayNameOfRaw(String)}
     * put both back on {@link #classDisplayName(ClassNode)}, the one vocabulary.
     */
    public Map<String, Object> getTypeHierarchy(String className) {
        checkLoaded();

        Map<String, Object> result = new LinkedHashMap<>();
        ClassNode cls = findClassNode(className);
        ClspGraph clsp = jadx.getRoot().getClsp();

        if (cls == null) {
            // Not in the APK. It may still be a type jadx knows about.
            // ClspGraph keys on dotted names, not the slash-separated internal
            // form -- isClsKnown("android/app/Activity") is false while
            // isClsKnown("android.app.Activity") is true.
            if (!clsp.isClsKnown(className)) {
                throw new RuntimeException("Class not found: " + className);
            }
            result.put("className", className);
            result.put("inApk", false);
            result.put("superClass", null);
            result.put("interfaces", new ArrayList<String>());
            result.put("superTypes", cappedHierarchy(
                    displayNames(clsp.getSuperTypes(className)), "supertypes"));
            result.put("innerClasses", new ArrayList<String>());
            result.put("outerClass", null);
            result.put("subClasses", cappedSubClasses(className, null));
            return guardedHierarchy(result, className);
        }

        result.put("className", classDisplayName(cls));
        result.put("inApk", true);

        ArgType superType = cls.getSuperClass();
        result.put("superClass", superType != null ? typeDisplayName(superType) : null);

        List<String> interfaces = new ArrayList<>();
        for (ArgType iface : cls.getInterfaces()) {
            interfaces.add(typeDisplayName(iface));
        }
        result.put("interfaces", cappedHierarchy(interfaces, "interfaces"));

        // The transitive closure, which is what tells a caller whether a class
        // is "an Activity" without walking the chain itself.
        //
        // Keyed on getRawName(), which is what ClspGraph.addApp() indexes:
        // ClassInfo.getType().getObject(). The key used to be getFullName(),
        // the same raw name with '.' where the type has '$', which matches for
        // a top-level class and matches NOTHING for an inner one -- so this
        // list came back empty for every inner and anonymous class in the APK,
        // which is most WebViewClients. Named out loud because it is a
        // pre-existing bug rather than one this round introduced; it is fixed
        // here because it is the same '.'-for-'$' name confusion, on the line
        // the vocabulary fix rewrites, and routing an always-empty list through
        // a display-name helper would have been theatre.
        result.put("superTypes", cappedHierarchy(
                displayNames(clsp.getSuperTypes(cls.getClassInfo().getRawName())),
                "supertypes"));

        List<String> inner = new ArrayList<>();
        for (ClassNode ic : cls.getInnerClasses()) {
            inner.add(classDisplayName(ic));
        }
        result.put("innerClasses", cappedHierarchy(inner, "inner classes"));

        ClassNode outer = cls.getParentClass();
        result.put("outerClass",
                outer != null && outer != cls ? classDisplayName(outer) : null);

        result.put("subClasses", cappedSubClasses(className, cls));
        return guardedHierarchy(result, className);
    }

    /**
     * The byte half of the guarantee, for the one map-shaped tool that had only
     * the count half.
     *
     * Four lists capped at 1000 each is a bound on entries, not on bytes: 4000
     * class names is 1 MB the moment the average name reaches 259 characters,
     * and a name's length is bounded by the dex format rather than by anything
     * this server controls. Measured on a large production APK, where the longest class name
     * in 141,461 is 162 characters, the worst case the cap admits is about 660
     * KB and the widest real answer -- getTypeHierarchy("java.lang.Object"),
     * whose subClasses list is the one that actually reaches the cap -- is
     * 52,045 bytes. So it does not bite on this APK, and it is here for the
     * same reason the caps themselves are: a guarantee that holds because of
     * the data rather than because of the code is not a guarantee.
     *
     * The notice is a key rather than a trailing element, because the answer is
     * a map; that is the shape searchMethodByName and findSinkCallSites already
     * use, so the one '@' check still finds it.
     */
    private static Map<String, Object> guardedHierarchy(Map<String, Object> result,
                                                        String className) {
        String notice = oversizedNotice("getTypeHierarchy", countNames(result),
                "type names", jsonCostOf(result), "className=" + className,
                "This tool takes no limit; use get_all_classes with a"
                + " packagePrefix, or search_classes_by_keyword, to enumerate.");
        if (notice != null) {
            result.put(OVERSIZED_MARKER_PREFIX, List.of(notice));
        }
        return result;
    }

    /** The names in the hierarchy's four lists, markers not counted. */
    private static long countNames(Map<String, Object> result) {
        long names = 0;
        for (Object value : result.values()) {
            if (!(value instanceof Collection)) {
                continue;
            }
            for (Object entry : (Collection<?>) value) {
                if (!(entry instanceof String) || !((String) entry).startsWith("@")) {
                    names++;
                }
            }
        }
        return names;
    }

    /**
     * Direct subclasses, capped like every other list this server hands back.
     *
     * This tool takes no limit parameter, so the cap is not the caller's to
     * choose -- which makes it the one place a default is not merely a default.
     * Measured on a large production APK, getTypeHierarchy("java.lang.Object") names 100,036
     * direct subclasses -- the widest response left once the searches were
     * capped, and reachable from a question as ordinary as "what is this type".
     * Capped, the whole call is 52,045 bytes of tool result. Nobody is reading subclass 1001 of Object, and the marker
     * names the real total, so a caller who wants the population can go and
     * count it with get_all_classes.
     */
    private List<String> cappedSubClasses(String className, ClassNode target) {
        return cappedNames(findSubClasses(className, target), DEFAULT_RESULT_LIMIT,
                "direct subclasses",
                "This tool takes no limit; use get_all_classes with a"
                + " packagePrefix, or search_classes_by_keyword, to enumerate.");
    }

    /**
     * The hierarchy's other three lists, under the same fixed cap as subClasses.
     *
     * None of them reaches it on either reference APK -- the widest measured on
     * a large production APK are 540 inner classes (ServiceRegistry) and 25 interfaces
     * (kotlin.jvm.internal.FunctionImpl). They are capped anyway so that "no
     * list-returning tool exceeds the cap silently" is a property of the server
     * rather than a property of the APKs it has been pointed at. A guarantee
     * with a footnote is the thing that made the consumer doc wrong last time.
     */
    private static List<String> cappedHierarchy(List<String> names, String unit) {
        return cappedNames(names, DEFAULT_RESULT_LIMIT, unit,
                "This tool takes no limit; use get_all_classes with a"
                + " packagePrefix, or search_classes_by_keyword, to enumerate.");
    }

    /**
     * Direct subclasses and implementors declared in the APK.
     *
     * There is no reverse index, so this scans -- but only over dex metadata,
     * which is the same shape of loop as searchMethodByName and stays cheap.
     *
     * @param target the class the caller named, when it is in the APK. Matching
     *               is then on NODE IDENTITY, which is the only test that holds
     *               for all four spellings this server accepts -- and the only
     *               one that holds for a collision class, whose reported name
     *               ('$' between outer and inner) equals neither the raw dex
     *               name nor the alias. Null for a framework supertype, which
     *               has no node and so can only be matched on its name.
     */
    private List<String> findSubClasses(String className, ClassNode target) {
        Set<String> subs = new TreeSet<>();
        for (ClassNode candidate : jadx.getRoot().getClasses()) {
            ArgType superType = candidate.getSuperClass();
            if (superType != null && matchesType(superType, className, target)) {
                subs.add(classDisplayName(candidate));
                continue;
            }
            for (ArgType iface : candidate.getInterfaces()) {
                if (matchesType(iface, className, target)) {
                    subs.add(classDisplayName(candidate));
                    break;
                }
            }
        }
        return new ArrayList<>(subs);
    }

    private boolean matchesType(ArgType type, String className, ClassNode target) {
        if (!type.isObject()) {
            return false;
        }
        if (target != null) {
            return jadx.getRoot().resolveClass(type) == target;
        }
        // No node to compare against: the caller named a type outside the APK,
        // and ArgType carries it as the raw dex name the caller was handed.
        return type.getObject().equals(className);
    }

    /**
     * The reported name of a type mentioned in a hierarchy.
     *
     * {@code ArgType.getObject()} is the RAW dex name. For a class in this APK
     * that is the wrong vocabulary twice over -- pre-deobfuscation, and '$'
     * where the alias has '.' -- so the type is resolved to its node and named
     * the way every other tool names it. A type with no node is outside the APK,
     * and its raw name is all jadx has; that is the same honest fallback
     * {@link #reportedDetailsName(IMethodDetails)} makes for a framework method,
     * and it is the spelling ClspGraph itself accepts, so get_type_hierarchy can
     * be asked about it in turn.
     */
    private String typeDisplayName(ArgType type) {
        if (!type.isObject()) {
            return type.toString();
        }
        ClassNode node = jadx.getRoot().resolveClass(type);
        return node != null ? classDisplayName(node) : type.getObject();
    }

    /** Same, for the raw names ClspGraph stores; some carry the slash form. */
    private List<String> displayNames(Collection<String> rawNames) {
        Set<String> names = new TreeSet<>();
        if (rawNames != null) {
            for (String raw : rawNames) {
                names.add(displayNameOfRaw(raw));
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * A raw dex class name as this server reports it: the node's display name
     * when the class is in the APK, the dotted raw name when it is not.
     */
    private String displayNameOfRaw(String rawName) {
        ClassNode node = jadx.getRoot().resolveRawClass(rawName);
        if (node == null && rawName.indexOf('/') >= 0) {
            node = jadx.getRoot().resolveRawClass(rawName.replace('/', '.'));
        }
        return node != null ? classDisplayName(node) : rawName.replace('/', '.');
    }

    /**
     * Where a reference lives: the owning class and the method holding it.
     *
     * All three fields are alias-aware. getShortId() would be the obvious thing
     * to put in "signature", but it is built from the RAW dex name, so an entry
     * came back reading {methodName: "m30104b", signature: "b(Ljava/lang/String;)..."}
     * -- one record naming the same method twice, in two vocabularies, neither
     * of which told the caller the other existed. makeSignature(true, true) is
     * the same string in the same format with the alias in front of the argument
     * types, and the argument types are the part that disambiguates an overload.
     */
    private Map<String, String> refEntry(MethodNode mth) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("className", classDisplayName(mth.getParentClass()));
        // Pinned, not live: this is the funnel every xref, callee and sink call
        // site is reported through, so it is where a drifting name would reach
        // the widest set of recorded findings. makeSignature would drift with
        // it, so the signature is rebuilt on the pinned name too.
        entry.put("methodName", pinnedNames.aliasOf(mth));
        entry.put("signature", pinnedNames.signatureOf(mth));
        return entry;
    }

    /**
     * Same shape for a call that resolves to nothing in this dex. MethodInfo is
     * all jadx has for those -- there is no node to ask for a parent class -- so
     * the declaring type comes off the info's ClassInfo, alias-aware either way.
     */
    private Map<String, String> refEntry(MethodInfo info) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("className", classDisplayName(info.getDeclClass()));
        entry.put("methodName", info.getAlias());
        entry.put("signature", info.makeSignature(true, true));
        return entry;
    }

    private static List<Map<String, String>> dedupe(List<Map<String, String>> xrefs) {
        Map<String, Map<String, String>> unique = new LinkedHashMap<>();
        for (Map<String, String> x : xrefs) {
            unique.putIfAbsent(x.get("className") + "#" + x.get("signature"), x);
        }
        return new ArrayList<>(unique.values());
    }

    /** Stable order so an agent re-running a query sees the same list. */
    private static List<Map<String, String>> sortedByClassThenMethod(List<Map<String, String>> xrefs) {
        xrefs.sort(Comparator
                .comparing((Map<String, String> x) -> x.getOrDefault("className", ""))
                .thenComparing(x -> x.getOrDefault("methodName", "")));
        return xrefs;
    }

    /**
     * Dex-tree counterpart of findClass, and now the primitive of the pair --
     * the xref, hierarchy and override data all live on ClassNode rather than
     * on the JavaClass wrapper, and going through the wrapper only to unwrap it
     * again would decompile on the way past.
     *
     * Null when nothing matches, which every caller already handles. Throws when
     * the spelling matches SEVERAL classes, because that is not a missing answer
     * but an unanswerable question; see {@link #ambiguousClass}.
     */
    private ClassNode findClassNode(String className) {
        List<ClassNode> matches = resolveClassNodes(className);
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw ambiguousClass(className, matches);
        }
        return matches.get(0);
    }

    /**
     * The exported components, as the CLI's flat display objects.
     *
     * Built from {@link #parseAllComponents()} like everything else that reads
     * the manifest, so the {@code components} CLI command, {@link #getApkInfo()}
     * and get_exported_components can no longer disagree about which components
     * are exported.
     *
     * Built on first ask rather than at load: resolving an @string/ reference
     * decodes resources.arsc, and a session that only reads class source should
     * not pay for a table it never looks at. loadManifest used to run the whole
     * component parse, which is also why the answer could not resolve resource
     * references -- it ran before anything could.
     */
    public synchronized List<ExportedComponent> getExportedComponents() {
        if (exportedComponents == null) {
            if (manifestContent == null) {
                return new ArrayList<>();
            }
            exportedComponents = exportedComponentsOf(parseAllComponents());
        }
        return new ArrayList<>(exportedComponents);
    }

    /**
     * Get exported components grouped by type
     */
    public Map<String, List<ExportedComponent>> getExportedComponentsByType() {
        Map<String, List<ExportedComponent>> grouped = new HashMap<>();

        for (ExportedComponent component : getExportedComponents()) {
            grouped.computeIfAbsent(component.type, k -> new ArrayList<>()).add(component);
        }

        return grouped;
    }

    /**
     * Components any app on the device can start directly, in the SAME record
     * shape {@link #getDeeplinkComponents()} returns.
     *
     * ONE CONCEPT, ONE SHAPE. These two tools describe overlapping populations
     * and used to describe them incompatibly: this one answered
     * {type, name, intentFilters, permission, exported} with intentFilters a
     * list of prose strings, and that one answers the component record the
     * caller's the client's component store consumes. So no access pattern worked across
     * the pair -- a caller could not read intent_filters[0]["scheme"] from both,
     * or from either, since the prose form had no keys at all. Both now come
     * from {@link #parseAllComponents()}: same fields, same names, same
     * resource-reference resolution, and the difference between the tools is
     * which components they KEEP and nothing else.
     *
     * The two populations, precisely:
     *   this one     -- exported == true, whatever else is true of it. That is
     *                   the question "what can a co-located app start", and the
     *                   launcher activity is part of the answer.
     *   deeplink     -- link-reachable (MAIN/LAUNCHER, BROWSABLE, or an alias of
     *                   one of those) PLUS the exported ones no link reaches.
     *
     * Neither contains the other in general. A BROWSABLE activity declaring
     * android:exported="false" is link-shaped but not exported, and an exported
     * component whose only filter carries MAIN without LAUNCHER is exported but
     * neither link-reachable nor rogue-app-reachable by the tier-2 rule. The
     * first case is real on a large production APK: 45 exported against 47 deeplink, and the
     * two extras are exactly that -- DrawerStyleWebActivity and
     * DispatcherActivity, both BROWSABLE, both exported="false". On
     * DivaApplication.apk the two lists are identical, all four components.
     */
    public List<Map<String, Object>> getExportedComponentsAsMap() {
        checkLoaded();
        if (manifestContent == null) {
            throw new RuntimeException("AndroidManifest.xml not loaded");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> c : parseAllComponents()) {
            if (Boolean.TRUE.equals(c.get("exported"))) {
                result.add(c);
            }
        }
        return cappedComponents(result, "getExportedComponents",
                "exported components");
    }

    /**
     * The manifest-derived lists, under the same fixed cap as subClasses -- and
     * under the same byte guard as every other list, which a count cap alone is
     * not.
     *
     * Neither comes close to 1000 on either reference APK: a large production APK declares 45
     * exported components and 47 link-reachable ones, and a manifest is bounded
     * by what an app can plausibly declare. They are capped for the same reason
     * the hierarchy lists are: the guarantee is meant to be a property of the
     * server, and "it has never been big on an APK we tried" is not one.
     *
     * A count cap is not a size guarantee, which is what these two had until
     * now. An entry here is not one name: it carries the component's whole
     * intent-filter set, and the widest single entry measured on a large production APK is
     * 13,582 bytes -- the same entry from both tools, now that both return the
     * same record. 1000 of those is 13.6 MB, so "capped at 1000" bounded these
     * at thirteen times the line this file calls a file dump. The bound is now
     * on the bytes as well as the count, in the trailing-entry shape
     * {@link #guardedRefs} uses. (It read 8,226 and 6,683 while the two tools
     * answered in two different shapes; every data entry carrying every key
     * grew both.)
     *
     * No limit parameter, so no contract change for two tools that take no
     * arguments today; the marker names the total and the manifest itself is
     * always available whole through get_android_manifest.
     */
    private static List<Map<String, Object>> cappedComponents(
            List<Map<String, Object>> all, String tool, String unit) {
        String advice = "This tool takes no limit; read get_android_manifest for"
                + " the declaration itself.";
        if (all.size() > DEFAULT_RESULT_LIMIT) {
            List<Map<String, Object>> capped =
                    new ArrayList<>(all.subList(0, DEFAULT_RESULT_LIMIT));
            capped.add(Map.of(TRUNCATION_MARKER_PREFIX, marker(TRUNCATION_MARKER_PREFIX,
                    truncationText(DEFAULT_RESULT_LIMIT, all.size(), unit, advice))));
            // The cut branch weighs itself, as everywhere else. It is the branch
            // that matters most here: the widest single entry on a large production APK is
            // 13,582 bytes, so the count cap alone admits 13.6 MB.
            String cutNotice = oversizedNotice(tool, DEFAULT_RESULT_LIMIT, unit,
                    jsonCostOf(capped), "the manifest", advice);
            if (cutNotice != null) {
                capped.add(Map.of(OVERSIZED_MARKER_PREFIX, cutNotice));
            }
            return capped;
        }
        String notice = oversizedNotice(tool, all.size(), unit, jsonCostOf(all),
                "the manifest", advice);
        if (notice == null) {
            return all;
        }
        List<Map<String, Object>> annotated = new ArrayList<>(all);
        annotated.add(Map.of(OVERSIZED_MARKER_PREFIX, notice));
        return annotated;
    }
    
    /**
     * Check if APK is loaded
     */
    public boolean isLoaded() {
        return jadx != null;
    }
    
    /**
     * Ensure APK is loaded, throw exception if not
     */
    public void checkLoaded() {
        if (!isLoaded()) {
            throw new RuntimeException("No APK loaded. Call loadApk() first.");
        }
    }
    
    // Private helper methods
    
    private void extractPackageName() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            packageName = doc.getDocumentElement().getAttribute("package");
        } catch (Exception e) {
            throw new RuntimeException("Error extracting package name: " + e.getMessage(), e);
        }
    }
    
    private void loadManifest() {
        List<ResourceFile> resources = jadx.getResources();
        
        for (ResourceFile resource : resources) {
            String resName = resource.getOriginalName();
            if (resName.equals("AndroidManifest.xml")) {
                try {
                    ResContainer resContainer = resource.loadContent();
                    if (resContainer != null) {
                        ICodeInfo codeInfo = resContainer.getText();
                        if (codeInfo != null) {
                            manifestContent = codeInfo.toString();
                        } else if (resContainer.getDecodedData() != null) {
                            manifestContent = new String(resContainer.getDecodedData());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading manifest: " + e.getMessage(), e);
                }
                break;
            }
        }
        
        if (manifestContent != null) {
            extractPackageName();
        }
    }
    
    /**
     * The one class lookup. Everything that takes a className goes through here.
     *
     * Alias-aware -- loadApk() turns deobfuscation on, so the names callers hold
     * are aliases rather than raw dex names -- and it resolves inner classes,
     * which iterating getClasses() does not.
     *
     * This used to be jadx's {@code searchJavaClassByAliasFullName}, a
     * {@code findFirst()} over {@code getAliasFullName()}. That is a silent
     * arbitrary pick whenever two classes share the string; see
     * {@link #ambiguousClassNames}. Now it goes through
     * {@link #resolveClassNodes(String)}, which answers only when the spelling
     * denotes exactly one class.
     */
    private JavaClass findClass(String className) {
        ClassNode cls = findClassNode(className);
        return cls == null ? null : (JavaClass) jadx.getJavaNodeByRef(cls);
    }

    /**
     * Alias names shared by two or more classes; see {@link #ambiguousClassNames}.
     *
     * Reads {@code ClassInfo} only, so it decompiles nothing, and is called from
     * loadApk() while the tree is pristine -- not because class aliases drift
     * (they were measured not to: 0 of 141,461 across a 3,000-class warm-up)
     * but because every other name snapshot is taken there and one rule is
     * easier to keep than two.
     *
     * The 3,000-class figure is the warm-up {@link PinnedFieldNames} retracts
     * as a sample -- a prefix of the tree holds almost no enums. The class-alias
     * conclusion survives it: {@link PinnedMethodNames} re-measured with every
     * enum in a large production APK decompiled and 12,000 further top-level classes after
     * that, and still found 0 of 141,461.
     */
    private static Set<String> findAmbiguousClassNames(RootNode root) {
        Map<String, Integer> counts = new HashMap<>(1 << 16);
        for (ClassNode cls : root.getClasses()) {
            counts.merge(cls.getClassInfo().getAliasFullName(), 1, Integer::sum);
        }
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                ambiguous.add(e.getKey());
            }
        }
        return ambiguous;
    }

    /**
     * The ONE name this server reports for a class, and the one it accepts.
     *
     * Almost always {@code getAliasFullName()}, unchanged. For the handful of
     * names that denote two classes it is {@code makeAliasRawFullName()}, which
     * is the same alias vocabulary with '$' between an outer class and an inner
     * one -- unique by construction, since a package name cannot contain a '$'.
     *
     * Reporting the unique spelling is what actually closes this hole. Making
     * the lookup stricter alone would only turn a wrong answer into an error;
     * the caller still has to be handed a name that means one class, because
     * the names a caller holds are the ones the server gave it.
     */
    private String classDisplayName(ClassNode cls) {
        String alias = cls.getClassInfo().getAliasFullName();
        return ambiguousClassNames.contains(alias)
                ? cls.getClassInfo().makeAliasRawFullName()
                : alias;
    }

    /** Same, for a class known only by its ClassInfo. */
    private String classDisplayName(ClassInfo info) {
        String alias = info.getAliasFullName();
        return ambiguousClassNames.contains(alias)
                ? info.makeAliasRawFullName()
                : alias;
    }

    /**
     * Every class a caller's spelling names, in ordered tiers -- the class half
     * of {@link #resolveMethods}, and ordered for the same reason: a lower tier
     * can only turn "not found" into an answer, never one answer into a
     * different one.
     *
     * <pre>
     *   1. makeAliasRawFullName -- the unique spelling, the one this server now
     *      reports: alias names, '$' between an outer class and an inner one.
     *      Indexed, so this is a hash lookup. Matches at most one class, so an
     *      explicit caller always wins outright. A class jadx did not rename
     *      arrives here under its raw dex name too, since the two strings are
     *      then the same.
     *   2. getAliasFullName -- alias names, '.' between outer and inner -- and
     *      only for a spelling KNOWN to denote several classes, which is what
     *      the ambiguousClassNames guard tests. Reached only by the handful of
     *      collisions; the answer is then refused rather than guessed.
     *   3. getAliasFullName for everything else, via jadx's own lookup. This is
     *      the spelling older workspaces recorded for an inner class, and by
     *      here it is known not to collide.
     *   4. ClassInfo.getFullName() -- the ORIGINAL dex name, '.' between outer
     *      and inner, i.e. the pre-deobfuscation name of a renamed class.
     *   5. ClassInfo.getRawName() -- the original dex name with '$', which is
     *      the spelling in the parentheses of a get_smali_of_class header, and
     *      the one get_type_hierarchy handed out for superClass, interfaces and
     *      superTypes until today. Indexed by RootNode, so it is a hash lookup.
     * </pre>
     *
     * Tiers 3 and 4 were one line of javadoc for a while, reading "the raw dex
     * name, with '$' or with '.', which is what get_smali_of_class prints".
     * Every clause of that was wrong: tier 3 is the ALIAS name, tier 4 took only
     * the '.' form -- the '$' form is tier 5, added here because until today it
     * was the vocabulary get_type_hierarchy emitted and a workspace that
     * recorded one of those names has to keep resolving it -- and the header of
     * get_smali_of_class prints the DISPLAY name, with the raw one after it in
     * parentheses. All three now resolve. The one spelling still not accepted is
     * the descriptor form, Lp/C$D;, which nothing here emits.
     */
    private List<ClassNode> resolveClassNodes(String className) {
        if (className == null || jadx == null) {
            return new ArrayList<>();
        }
        String query = className.trim();

        // Tier 1, O(1): the unique spelling, which is what this server reports.
        ClassNode unique = classesByUniqueName.get(query);
        if (unique != null) {
            return new ArrayList<>(List.of(unique));
        }

        // Tier 2: the shared alias spelling. Only reachable for a name that
        // denotes SEVERAL classes -- a single class's alias name is either its
        // unique spelling already (top-level) or resolved just below -- so the
        // scan runs at most for the handful in ambiguousClassNames.
        if (ambiguousClassNames.contains(query)) {
            List<ClassNode> matches = new ArrayList<>();
            for (ClassNode cls : jadx.getRoot().getClasses()) {
                if (cls.getClassInfo().getAliasFullName().equals(query)) {
                    matches.add(cls);
                }
            }
            if (!matches.isEmpty()) {
                return matches;
            }
        }

        // Tier 3: the dotted inner-class spelling, which every existing
        // workspace recorded. jadx's own lookup, and unambiguous by now.
        JavaClass byAlias = jadx.searchJavaClassByAliasFullName(query);
        if (byAlias != null) {
            return new ArrayList<>(List.of(byAlias.getClassNode()));
        }

        // Tier 4: the original dex name, dotted.
        ClassNode byRaw = jadx.searchClassNodeByOrigFullName(query);
        if (byRaw != null) {
            return new ArrayList<>(List.of(byRaw));
        }

        // Tier 5, O(1): the original dex name with '$'.
        ClassNode byRawDollar = jadx.getRoot().resolveRawClass(query);
        if (byRawDollar != null) {
            return new ArrayList<>(List.of(byRawDollar));
        }
        return new ArrayList<>();
    }

    /**
     * Index every class under its unique spelling; see
     * {@link #classesByUniqueName}.
     *
     * Reads ClassInfo only, so it decompiles nothing. A duplicate key is
     * impossible by construction -- that is what makes the spelling unique --
     * but putIfAbsent rather than put keeps a hypothetical one from silently
     * replacing the class already indexed.
     */
    private static Map<String, ClassNode> indexByUniqueName(RootNode root) {
        Map<String, ClassNode> index = new HashMap<>(1 << 16);
        for (ClassNode cls : root.getClasses()) {
            index.putIfAbsent(cls.getClassInfo().makeAliasRawFullName(), cls);
        }
        return index;
    }

    /**
     * Refuses a spelling that names more than one class, naming the unique
     * spelling of each so the caller can ask again and mean it.
     *
     * An error rather than a union: two classes that happen to render the same
     * are unrelated types, and merging their members would be a worse answer
     * than none. Overloads are unioned because they are one name for one class's
     * methods; this is one name for two classes.
     */
    private RuntimeException ambiguousClass(String className, List<ClassNode> matches) {
        List<String> spellings = new ArrayList<>();
        for (ClassNode cls : matches) {
            spellings.add(cls.getClassInfo().makeAliasRawFullName()
                    + " (" + cls.getMethods().size() + " methods, "
                    + cls.getFields().size() + " fields)");
        }
        Collections.sort(spellings);
        return new RuntimeException(marker(AMBIGUOUS_MARKER_PREFIX, "\"" + className
                + "\" names " + matches.size() + " different classes in this APK: "
                + String.join(", ", spellings)
                + ". Ask again with the spelling that uses '$' between an outer"
                + " class and an inner one."));
    }
    
    /**
     * One method out of a class smali dump, matched on its raw shortId.
     *
     * A .method line ends with exactly the shortId -- "h(Lcom/x/Y;)V" -- so
     * anchoring on that rather than on the bare name keeps overloads apart. The
     * old bare-name match returned whichever overload the dump printed first and
     * said nothing about the others.
     */
    private String extractMethodSmali(String classSmali, String shortId) {
        String[] lines = classSmali.split("\n");
        StringBuilder methodSmali = new StringBuilder();
        boolean inMethod = false;
        boolean foundMethod = false;

        for (String line : lines) {
            if (!inMethod) {
                if (line.contains(".method ") && line.trim().endsWith(" " + shortId)) {
                    inMethod = true;
                    foundMethod = true;
                    methodSmali.append(line).append("\n");
                }
                continue;
            }
            methodSmali.append(line).append("\n");
            if (line.contains(".end method")) {
                break;
            }
        }

        return foundMethod ? methodSmali.toString() : null;
    }

    /**
     * The exported subset of {@link #parseAllComponents()}, projected onto the
     * CLI's flat display object.
     *
     * This replaced parseManifest/analyzeComponent/extractIntentFilter, a second
     * walk of the same XML that answered a different question by accident --
     * see {@link #parseAllComponents()} for the five ways the two disagreed.
     * Nothing is parsed here; the record is already built, and this only picks
     * the four fields ExportedComponent holds and renders the filters for a
     * human reading terminal output.
     */
    private List<ExportedComponent> exportedComponentsOf(List<Map<String, Object>> all) {
        List<ExportedComponent> components = new ArrayList<>();
        for (Map<String, Object> c : all) {
            if (!Boolean.TRUE.equals(c.get("exported"))) {
                continue;
            }
            ExportedComponent component = new ExportedComponent();
            component.type = (String) c.get("type");
            component.name = (String) c.get("name");
            component.exported = true;
            component.permission = c.get("permission") == null ? "" : (String) c.get("permission");
            for (Map<String, Object> f : filtersOf(c)) {
                String rendered = renderFilter(f);
                if (!rendered.isEmpty()) {
                    component.intentFilters.add(rendered);
                }
            }
            components.add(component);
        }
        return components;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filtersOf(Map<String, Object> component) {
        return (List<Map<String, Object>>) component.get("intent_filters");
    }

    /**
     * One intent-filter as the line the CLI prints.
     *
     * Prose, and deliberately only here: this is what get_exported_components
     * used to RETURN, and a caller cannot read a scheme out of
     * "Data: scheme=x host=y " without writing a parser for it. The tools return
     * the structured filter; a terminal gets this.
     */
    @SuppressWarnings("unchecked")
    private static String renderFilter(Map<String, Object> filter) {
        List<String> parts = new ArrayList<>();
        for (String action : (List<String>) filter.get("actions")) {
            parts.add("Action: " + action);
        }
        for (String category : (List<String>) filter.get("categories")) {
            parts.add("Category: " + category);
        }
        for (Map<String, Object> data : (List<Map<String, Object>>) filter.get("data")) {
            StringBuilder sb = new StringBuilder("Data:");
            for (String attr : DATA_ATTRS) {
                Object v = data.get(attr);
                if (v != null && !String.valueOf(v).isEmpty()) {
                    sb.append(' ').append(attr).append('=').append(v);
                }
            }
            if (sb.length() > "Data:".length()) {
                parts.add(sb.toString());
            }
        }
        return String.join(", ", parts);
    }

    /**
     * Resource file names, optionally narrowed.
     *
     * This took no arguments at all, which made it the only wide tool a caller
     * could not ask a narrower question of: 16,559 names and 897,281 bytes on
     * a large production APK, the same answer for everybody, no prefix and no limit. A caller
     * wants a handful of those -- res/xml is 24 names and 799 bytes and holds
     * network_security_config, the FileProvider paths and the App Links
     * assetlinks declaration -- so the narrowing is the fix and the cap is the
     * backstop.
     *
     * @param pathPrefix keep only names starting with this, case-insensitively,
     *                   matched against the whole archive path ("res/xml",
     *                   "assets/", "AndroidManifest.xml"). Null or empty keeps
     *                   everything. Deliberately the same contract getAllClasses
     *                   gives packagePrefix, so one rule covers both.
     * @param limit      at most this many names; 0 or less means uncapped.
     */
    public List<String> getAllResourceFileNames(String pathPrefix, int limit) {
        checkLoaded();
        String prefix = pathPrefix == null || pathPrefix.isEmpty()
                ? null
                : pathPrefix.toLowerCase();

        List<String> resourceNames = new ArrayList<>();
        for (ResourceFile resource : jadx.getResources()) {
            String name = resource.getOriginalName();
            if (prefix == null || name.toLowerCase().startsWith(prefix)) {
                resourceNames.add(name);
            }
        }

        Collections.sort(resourceNames);
        return guardedNames(resourceNames, limit, "getAllResourceFileNames",
                "resource file names",
                prefix == null ? "no pathPrefix" : "pathPrefix=" + prefix,
                "Pass a pathPrefix -- res/xml is 24 of a large production APK's 16,559"
                + " names and holds the security config, the FileProvider paths"
                + " and the App Links declaration.");
    }

    /** Every resource file name, uncapped; see the overload. */
    public List<String> getAllResourceFileNames() {
        return getAllResourceFileNames(null, 0);
    }
    
    /** Every byte of a resource file, uncapped; see the overload. */
    public String getResourceFile(String fileName) {
        return getResourceFile(fileName, 0);
    }

    /** What both of getResourceFile's notices tell a caller to do instead. */
    private static final String RESOURCE_TOO_BIG =
            "A file this size is a binary asset or a bundled library, not"
            + " something to read in a context window -- unzip it if you need"
            + " the whole thing.";

    /**
     * Content of a resource file, capped by default.
     *
     * This is still the widest response the server can produce, by two orders of
     * magnitude, and it was the one that needed no opt-in to reach:
     * lib/arm64-v8a/libapp.so is a 48,628,640-byte entry that reaches a caller
     * as 150,606,754 bytes of tool result, and assets/audience_network.dex
     * 3,406,320 bytes arriving as 11,879,027. Neither is an exotic query. The
     * second name is in the DEFAULT capped 1000-name listing that
     * getAllResourceFileNames hands back, and that tool's own docstring says
     * "Feed any entry to get_resource_file" -- so the accident path was built,
     * documented and recommended here, not stumbled into.
     *
     * The cut happens on the RAW BYTES, before decoding. readNBytes reads only
     * what is asked for and getUncompressedSize() names the real total from the
     * zip's own directory, so a 48 MB entry now costs 1 MB of heap and one short
     * read rather than the whole array plus the String built from it. The old
     * code called readAllBytes() unconditionally, so the daemon paid the full
     * size even for a caller who wanted the first line.
     *
     * Bytes rather than characters because that is what a resource is: an entry
     * in an archive, binary as often as not. The notice says which unit it used.
     *
     * @param maxBytes at most this many bytes; 0 or less means uncapped, and
     *                 uncapped still says so above 1 MB -- see guardedText
     */
    public String getResourceFile(String fileName, int maxBytes) {
        checkLoaded();

        List<ResourceFile> resources = jadx.getResources();
        for (ResourceFile resource : resources) {
            if (resource.getOriginalName().equals(fileName)) {
                try {
                    // Try using getZipEntry first
                    IZipEntry zipEntry = resource.getZipEntry();
                    if (zipEntry != null) {
                        long total = zipEntry.getUncompressedSize();
                        String scope = "\"" + fileName + "\"";
                        if (maxBytes <= 0) {
                            // The escape hatch, and the widest response this
                            // server can produce: 150,606,754 bytes for
                            // libapp.so. It still returns everything -- that is
                            // what it is for -- but it no longer returns it
                            // silently. See guardedText.
                            byte[] data = zipEntry.getInputStream().readAllBytes();
                            if (data == null) {
                                return null;
                            }
                            String whole = new String(data, "UTF-8");
                            return withOversizedNotice(whole, jsonCost(whole),
                                    "getResourceFile", total, "bytes", scope,
                                    RESOURCE_TOO_BIG);
                        }
                        // One byte of archive can never decode to less than one
                        // byte of escaped output -- ASCII costs 1, a two-byte
                        // sequence 2, a three-byte 3, a surrogate pair 4, and an
                        // invalid byte becomes a three-byte U+FFFD -- so reading
                        // maxBytes always yields enough text to spend the
                        // budget. Reading the whole entry is what this is here
                        // to stop: 1 MB rather than the 48,628,640 of libapp.so.
                        long want = (long) maxBytes + 4;
                        byte[] head = zipEntry.getInputStream().readNBytes(
                                (int) Math.min(want, Integer.MAX_VALUE));
                        if (head == null) {
                            return null;
                        }
                        String text = new String(head, "UTF-8");
                        if (head.length >= total && jsonCost(text) <= maxBytes) {
                            return text;
                        }
                        String notice = textTruncationMarker("getResourceFile", maxBytes,
                                total + " bytes", scope, RESOURCE_TOO_BIG);
                        return notice
                                + cutToJsonBudget(text, budgetLeftAfter(notice, maxBytes));
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading resource file: " + fileName + " - " + e.getMessage(), e);
                }
            }
        }

        return null;
    }
    
    /** Whole smali dump of a class, uncapped; see the overload. */
    public String getSmaliOfClass(String className) {
        return getSmaliOfClass(className, 0);
    }

    /**
     * @param maxBytes at most this many bytes of answer; 0 or less means
     *                 uncapped. Smali is several times the size of the Java it
     *                 came from, so this bites where getClassSource does and a
     *                 little sooner: kotlin.collections.ArraysKt___ArraysKt is
     *                 3,605,169 bytes of smali against 1,449,229 of source.
     */
    public String getSmaliOfClass(String className, int maxBytes) {
        checkLoaded();

        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        try {
            return guardedText(canonicalSmaliHeaders(javaClass.getSmali()), maxBytes,
                    "getSmaliOfClass",
                    "\"" + className + "\"",
                    "Smali runs several times the size of the source it came"
                    + " from; use get_smali_of_method for the one method you"
                    + " are reading.");
        } catch (Exception e) {
            throw new RuntimeException("Error getting smali for class: " + className + " - " + e.getMessage(), e);
        }
    }
    
    /** The header line jadx writes above each class in a smali dump. */
    private static final String SMALI_CLASS_HEADER = "###### Class ";

    /**
     * Put the class names in a smali dump into this server's vocabulary.
     *
     * jadx writes one "###### Class NAME (RAW)" line per class -- the asked-for
     * class, then every inner and inlined class folded into the dump -- and NAME
     * is {@code ClassNode.getFullName()}, i.e. {@code getAliasFullName()}, which
     * classDisplayName() never touches because the whole dump comes back as one
     * opaque string from {@code JavaClass.getSmali()}.
     *
     * For a collision class that name denotes two classes, and the one this
     * server resolves it to is the OTHER one: the alias spelling of an inner
     * class {@code p.C$D} is exactly the unique spelling of the top-level
     * {@code p.C.D}, so it hits tier 1 of resolveClassNodes and lands on the
     * twin. A caller reading the header and asking for that class next would
     * silently change subject -- the exact failure the collision fix removed
     * everywhere else, left open on the one tool whose output jadx writes.
     *
     * Keyed on RAW, which is {@code ClassInfo.getRawName()} and therefore
     * unique, and which RootNode indexes -- so this is one hash lookup per
     * header, not a re-derivation of the name. A header with no raw name in it
     * ("Class X is created by jadx") and one whose raw name is not in this dex
     * are both left exactly as jadx wrote them.
     */
    private String canonicalSmaliHeaders(String smali) {
        if (smali == null) {
            return null;
        }
        int at = smali.indexOf(SMALI_CLASS_HEADER);
        if (at < 0) {
            return smali;
        }
        StringBuilder out = new StringBuilder(smali.length());
        int copiedTo = 0;
        while (at >= 0) {
            int nameAt = at + SMALI_CLASS_HEADER.length();
            int eol = smali.indexOf('\n', nameAt);
            if (eol < 0) {
                eol = smali.length();
            }
            out.append(smali, copiedTo, nameAt);
            out.append(canonicalHeaderName(smali.substring(nameAt, eol)));
            copiedTo = eol;
            at = smali.indexOf(SMALI_CLASS_HEADER, eol);
        }
        return out.append(smali, copiedTo, smali.length()).toString();
    }

    /** "p.C.D (p.C$D)" -> "p.C$D (p.C$D)"; anything unrecognised, unchanged. */
    private String canonicalHeaderName(String header) {
        int rawAt = header.lastIndexOf(" (");
        if (rawAt < 0 || !header.endsWith(")")) {
            return header;
        }
        String raw = header.substring(rawAt + 2, header.length() - 1);
        ClassNode node = jadx.getRoot().resolveRawClass(raw);
        return node == null ? header : classDisplayName(node) + header.substring(rawAt);
    }

    /**
     * Smali of a specific method.
     *
     * The smali dump is raw dex, so its .method lines carry raw names -- which is
     * why this used to reject the alias every other tool reports. The name is
     * resolved to MethodNodes first and each one is then cut out by its raw
     * shortId, so an overload set comes back as separate methods rather than
     * collapsing onto whichever one the dump happened to print first.
     */
    public String getSmaliOfMethod(String className, String methodName) {
        return getSmaliOfMethod(className, methodName, null);
    }

    /**
     * @param signature optional, narrowing an overloaded name to one method;
     *                  see {@link #resolveMethods(ClassNode, String, String)}
     */
    public String getSmaliOfMethod(String className, String methodName, String signature) {
        checkLoaded();

        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        try {
            String classSmali = javaClass.getSmali();
            if (classSmali == null) {
                return null;
            }
            List<MethodNode> targets =
                    resolveMethods(javaClass.getClassNode(), methodName, signature);
            StringBuilder smali = new StringBuilder();
            // The one tool that merged an overload set and said nothing at all.
            // getMethodByName has announced this for a while; here nine bodies
            // arrived concatenated and read as one method's bytecode, which is
            // the exact misreading AMBIGUOUS_MARKER_PREFIX exists to prevent.
            // Leading and bare, so the one '@' check finds it; see guardedText.
            if (targets.size() > 1) {
                smali.append(ambiguityNotice(methodName, targets)).append("\n\n");
            }
            boolean anyBody = false;
            for (MethodNode target : targets) {
                String one = extractMethodSmali(classSmali, target.getMethodInfo().getShortId());
                if (one == null) {
                    continue;
                }
                smali.append(one);
                anyBody = true;
            }
            // The notice alone is not an answer: with no body extracted at all
            // this still has to be the "not available" null the API layer turns
            // into an error, not a marker pretending to be bytecode.
            if (!anyBody) {
                return null;
            }
            return guardedText(smali.toString(), DEFAULT_MAX_BYTES, "getSmaliOfMethod",
                    "\"" + className + "." + methodName + "\"",
                    "Pass signature to ask about one overload.");
        } catch (Exception e) {
            throw new RuntimeException("Error getting smali for method: " + methodName + " in class: " + className + " - " + e.getMessage(), e);
        }
    }

    /**
     * Close the analyzer and free resources
     */
    /**
     * Release the decompiler.
     *
     * The field is cleared, not merely closed. jadx.close() drops caches but the
     * loaded class graph stays reachable through this object, and this object
     * outlives eviction: the API layer holds the analyzer it last loaded. Left
     * set, an evicted APK's ~2.9 GB (measured on a large production APK) stays live and the
     * eviction frees nothing. Clearing it also makes isLoaded() honest, so a
     * call against an evicted analyzer says "No APK loaded" instead of failing
     * somewhere inside jadx.
     */
    public void close() {
        if (jadx != null) {
            try {
                jadx.close();
            } catch (Exception e) {
                // Log error but don't throw
                System.err.println("Error closing JADX: " + e.getMessage());
            }
            jadx = null;
        }
        // Both of these are keyed by MethodNode/FieldNode/ClassNode, and every
        // one of those keys reaches the whole dex tree through its parent
        // chain. Holding either after close would pin the entire loaded APK in
        // memory and quietly defeat AnalyzerRegistry's eviction, which calls
        // close() precisely to get that memory back.
        pinnedNames = null;
        reverseIndex = null;
    }
}
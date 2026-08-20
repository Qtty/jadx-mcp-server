package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.AnalyzerRegistry;
import com.example.jadxmcpserver.core.JadxAnalyzerCore;

import java.util.*;

/**
 * JADX APK Analyzer API - Clean API wrapper for MCP Server
 * This class provides a simple API interface that delegates to JadxAnalyzerCore
 */
public class JadxApkAnalyzerAPI {

    private final AnalyzerRegistry registry = new AnalyzerRegistry();

    /** The analyzer for the currently selected APK; a cache of registry.get(currentApkPath). */
    private JadxAnalyzerCore core;
    private String currentApkPath;

    /**
     * Load and analyze an APK file.
     *
     * Idempotent: asking for an APK that is already resident returns immediately
     * instead of re-parsing it. That is the point of the daemon — a 282 MB APK
     * used to be re-parsed once per session.
     */
    public Map<String, Object> loadApk(String apkPath) throws Exception {
        try {
            core = registry.get(apkPath);
            currentApkPath = apkPath;
            return core.getApkInfo();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /** Most recently loaded APK path, or null. Surfaced on /health. */
    public String loadedApkPath() {
        return registry.loadedApkPath();
    }

    /**
     * Registry capacity, residency and thrash counters. Surfaced on /health so
     * that evicting a warm APK — an 11-15 s reload the next time it is asked
     * for — is visible instead of being paid silently.
     */
    public Map<String, Object> registryStats() {
        return registry.stats();
    }

    /**
     * What the dex reverse index cost on the current APK, or null if nothing has
     * asked for one yet.
     *
     * Surfaced on /health for the same reason the eviction counters are: the
     * index is built lazily, so exactly one request per loaded APK pays for it
     * — 3 s on a 141,461-class APK — and without this there is nothing to tell
     * a caller which request that was or whether it has already happened.
     */
    public Map<String, Object> reverseIndexStats() {
        String path = registry.loadedApkPath();
        if (path == null) {
            return null;
        }
        // peek, not get: a health poll must never load an APK.
        JadxAnalyzerCore core = registry.peek(path);
        return core == null ? null : core.reverseIndexStats();
    }

    /**
     * Class names, as many as the default cap allows; see the overload.
     *
     * Not "all classes" any more, whatever the name says: this is the shape of
     * call that returned 8.1 MB. Pass limit 0 to the overload for the whole list.
     */
    public List<String> getAllClasses() throws Exception {
        return getAllClasses(null, null);
    }

    /**
     * Class names, optionally narrowed by package prefix and count.
     *
     * This layer is where "the caller named no limit" is a fact, so it is where
     * the default cap lives -- {@link JadxAnalyzerCore#DEFAULT_RESULT_LIMIT},
     * next to findSinkCallSites, which had one from the start. A null limit used
     * to mean uncapped: 141,461 names and 8.1 MB for a bare GET, on the argument
     * that the CLI and the agent files call this expecting the whole list. The
     * CLI does, and it still gets it -- it talks to the core, which keeps
     * 0-means-uncapped as its primitive. The agent files do not: what they want
     * is the answer, and 8.1 MB of it is not usable by the thing it is returned
     * to.
     *
     * Below the cap nothing changed. Above it, the last entry says how many
     * there really were, so a truncated list still answers "how big is this APK"
     * -- which was the argument against defaulting, and it does not survive the
     * marker being there.
     *
     * Only a null limit takes the default. 0 and any negative number still mean
     * uncapped, clamped rather than rejected: getAllClasses(null, -5) returns
     * every class. So the old behaviour is one explicit argument away, and a
     * caller whose limit is computed rather than literal has to range-check it
     * here, not downstream.
     */
    public List<String> getAllClasses(String packagePrefix, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.getAllClasses(packagePrefix, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * The cap to use when the caller named none.
     *
     * Null is "I did not choose" and takes the default; 0 and negatives are a
     * choice -- "no cap" -- and are honoured. Keeping those two apart is what
     * lets the default be a default rather than a maximum.
     */
    private static int orDefault(Integer limit) {
        return limit == null ? JadxAnalyzerCore.DEFAULT_RESULT_LIMIT : limit;
    }

    /**
     * The byte budget to use when the caller named none.
     *
     * Same rule as {@link #orDefault} one axis over: null is "I did not choose"
     * and takes {@link JadxAnalyzerCore#DEFAULT_MAX_BYTES}; 0 and negatives are
     * a choice -- "no cap" -- and are honoured. Kept as a separate method with a
     * separate name because the unit is different, and the doc that used one
     * word for both units is how the last round's claim went wrong.
     */
    private static int orDefaultBytes(Integer maxBytes) {
        return maxBytes == null ? JadxAnalyzerCore.DEFAULT_MAX_BYTES : maxBytes;
    }

    /**
     * {@link #orDefaultBytes} for get_class_source alone, which budgets against
     * a different question and so takes a different default.
     *
     * Every other byte-capped tool is guarding against a runaway -- a 48 MB
     * native library, a 6 MB generated resource table -- and 1 MB is the line
     * for that. This one is guarding a caller's context window, where the cost
     * that matters is not one absurd answer but the thousands of ordinary ones
     * that each get re-read on every turn the caller takes afterwards. Measured
     * on a large analysis run, get_class_source was a majority of the run's
     * whole payload, at a median of 4,878 characters. Nothing there is a
     * runaway. All of it is the bill.
     *
     * See {@link JadxAnalyzerCore#DEFAULT_SOFT_BYTES} for why 8192 and not less.
     */
    private static int orDefaultSourceBytes(Integer maxBytes) {
        return maxBytes == null ? JadxAnalyzerCore.DEFAULT_SOFT_BYTES : maxBytes;
    }

    /**
     * Source code of a class, capped by default; see the overload.
     */
    public String getClassSource(String className) throws Exception {
        return getClassSource(className, null);
    }

    /**
     * A class's decompiled source, or its outline when the source does not fit.
     *
     * Two guards, asking two questions. The 1 MB runaway guard arrived three
     * rounds after the same class's FIELD list got one: com.example.app.R.id
     * had been capped at 1000 fields while its source came back whole, all
     * 15,260 declarations and 6,157,819 bytes of tool result.
     *
     * The 8 KB context budget arrived after an analysis was measured end to end and
     * this tool turned out to be the largest single share of everything it read. Past it the
     * answer is the class's fields and method signatures rather than a cut
     * body, because the first 8 KB of a decompiled class is its imports and two
     * of forty methods -- see {@link JadxAnalyzerCore#OUTLINE_MARKER_PREFIX}.
     *
     * @param maxBytes null takes {@link JadxAnalyzerCore#DEFAULT_SOFT_BYTES};
     *                 0 or negative is uncapped and returns the whole file
     */
    public String getClassSource(String className, Integer maxBytes) throws Exception {
        checkLoaded();
        try {
            String source = core.getClassSource(className, orDefaultSourceBytes(maxBytes));
            if (source == null) {
                throw new Exception("Class not found: " + className);
            }
            return source;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get methods of a class
     */
    public List<String> getMethodsOfClass(String className) throws Exception {
        return getMethodsOfClass(className, null);
    }

    /**
     * A class's methods, capped by default.
     *
     * A class looks like a narrow scope and mostly is, which is why this was
     * never given a cap. It is not always: kotlin.collections.ArraysKt___ArraysKt
     * declares 1,668 methods on a large production APK, 155,936 bytes, and the answer said
     * nothing about it. A null limit takes DEFAULT_RESULT_LIMIT; 0 or negative
     * is uncapped, as everywhere else.
     */
    public List<String> getMethodsOfClass(String className, Integer limit) throws Exception {
        checkLoaded();
        try {
            List<String> methods = core.getMethodsOfClass(className, orDefault(limit));
            if (methods.isEmpty()) {
                // Check if class exists
                if (core.getClassSource(className) == null) {
                    throw new Exception("Class not found: " + className);
                }
            }
            return methods;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Get fields of a class
     */
    public List<String> getFieldsOfClass(String className) throws Exception {
        return getFieldsOfClass(className, null);
    }

    /**
     * A class's fields, capped by default.
     *
     * The widest per-class list in the server: generated resource tables are
     * classes, and com.example.app.R.id declares 15,260 fields and 411,485
     * bytes of them.
     */
    public List<String> getFieldsOfClass(String className, Integer limit) throws Exception {
        checkLoaded();
        try {
            List<String> fields = core.getFieldsOfClass(className, orDefault(limit));
            if (fields.isEmpty()) {
                // Check if class exists
                if (core.getClassSource(className) == null) {
                    throw new Exception("Class not found: " + className);
                }
            }
            return fields;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get method source code
     */
    public String getMethodSource(String className, String methodName) throws Exception {
        return getMethodSource(className, methodName, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     */
    public String getMethodSource(String className, String methodName, String signature)
            throws Exception {
        checkLoaded();
        try {
            String methodCode = core.getMethodByName(className, methodName, signature);
            if (methodCode == null) {
                throw new Exception("Method not found: " + methodName + " in class " + className);
            }
            return methodCode;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Search for classes whose name contains a keyword.
     *
     * No "not found" error: a search that matches nothing is an answer, not a
     * failure, and agents branch on the empty list.
     */
    public List<String> searchClassesByKeyword(String keyword) throws Exception {
        return searchClassesByKeyword(keyword, null);
    }

    /**
     * Classes matching a keyword, optionally capped.
     *
     * A null limit takes DEFAULT_RESULT_LIMIT, as on getAllClasses and for the
     * same reason -- the 14 one-argument call sites in the caller's agent files
     * want the answer, not the corpus. Measured, the cap is chosen so those
     * calls are unaffected: the keywords an analysis opens with come back whole
     * ("WebView" 769, "Router" 735, "Bridge" 690), and the ones it does not
     * ("Service" 6090) come back capped and say so. Same bargain getAllClasses
     * struck: the narrow path is available, the wide one still answers on
     * request, and a cut-short list says
     * so rather than passing for a complete one.
     */
    public List<String> searchClassesByKeyword(String keyword, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.searchClassesByKeyword(keyword, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Search for methods across all classes
     */
    public Map<String, List<String>> searchMethod(String methodName) throws Exception {
        return searchMethod(methodName, null);
    }

    /**
     * Methods matching a name, optionally capped by class count.
     *
     * The tool the finding was actually about, and the one where the character
     * floor was least of a guard: with an empty methodName refused and a
     * two-character minimum enforced, methodName="in" was still 22,317,076 bytes
     * of HTTP 200 with nothing in the body admitting it. A floor rejects a query
     * that can only be a sweep. It cannot bound a real query that happens to be
     * wide, and that is what a default cap is for.
     *
     * A null limit takes DEFAULT_RESULT_LIMIT. The cap is on classes, which is
     * the axis this map is keyed by; the marker names both totals, classes and
     * methods, because either can be the one that surprised the caller.
     */
    public Map<String, List<String>> searchMethod(String methodName, Integer limit)
            throws Exception {
        checkLoaded();
        try {
            return core.searchMethodByName(methodName, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    
    /**
     * Cross-references to a class, method or field.
     *
     * An unreferenced target yields an empty list; only an unknown target is an
     * error, and it uses the same "not found" wording as getClassSource so an
     * agent can recognise it without a special case.
     *
     * All three are capped by default, and this is the family where that matters
     * most. Measured on a large production APK before the cap existed: 53,181 references to
     * kotlin.jvm.internal.Intrinsics and 10,030,776 bytes, 38,291 callers of
     * Intrinsics.checkNotNullParameter and 7,542,206 bytes, 6,510 users of
     * kotlin.Unit.INSTANCE and 1,490,741 bytes -- every one of them HTTP 200
     * with nothing in the body saying so. A heavily-referenced target is not a
     * pathological query here: it is exactly what a tracer asks about, because
     * it asks about whatever the chain led to.
     */
    public List<Map<String, String>> getXrefsToClass(String className) throws Exception {
        return getXrefsToClass(className, null);
    }

    /** @param limit null takes the default cap; 0 or negative is uncapped */
    public List<Map<String, String>> getXrefsToClass(String className, Integer limit)
            throws Exception {
        checkLoaded();
        try {
            return core.getXrefsToClass(className, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    public List<Map<String, String>> getXrefsToMethod(String className, String methodName) throws Exception {
        return getXrefsToMethod(className, methodName, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     */
    public List<Map<String, String>> getXrefsToMethod(String className, String methodName,
            String signature) throws Exception {
        return getXrefsToMethod(className, methodName, signature, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     * @param limit     null takes the default cap; 0 or negative is uncapped
     */
    public List<Map<String, String>> getXrefsToMethod(String className, String methodName,
            String signature, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.getXrefsToMethod(className, methodName, signature, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    public List<Map<String, String>> getXrefsToField(String className, String fieldName) throws Exception {
        return getXrefsToField(className, fieldName, null);
    }

    /** @param limit null takes the default cap; 0 or negative is uncapped */
    public List<Map<String, String>> getXrefsToField(String className, String fieldName,
            Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.getXrefsToField(className, fieldName, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * The methods a method calls, split into the ones this dex declares and the
     * ones it does not.
     *
     * Same error contract as the xref calls: an unknown class or method is an
     * error worded like getClassSource's, and a method that calls nothing yields
     * empty lists rather than an error.
     */
    public Map<String, Object> getCallees(String className, String methodName) throws Exception {
        return getCallees(className, methodName, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     */
    public Map<String, Object> getCallees(String className, String methodName,
            String signature) throws Exception {
        return getCallees(className, methodName, signature, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     * @param limit     caps "callees" and "external" separately; null takes the
     *                  default, 0 or negative is uncapped. Measured, the default
     *                  never bites on either reference APK -- the widest of
     *                  a large production APK's 872,119 methods is 541 entries and 68,663
     *                  bytes -- so this is the guarantee rather than a fix.
     */
    public Map<String, Object> getCallees(String className, String methodName,
            String signature, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.getCallees(className, methodName, signature, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Every call site in the APK for a set of framework methods.
     *
     * No "not found" error exists here: a sink nobody calls is an empty list
     * under its own key, and an unknown sink is the same answer. The sweep reads
     * dex metadata only, so it cannot fail on a class it does not understand.
     *
     * A null or empty sink list means the default catalogue, so an agent can
     * call this cold. A null limit means DEFAULT_CALL_SITE_LIMIT rather than
     * "uncapped" -- the uncapped default sweep of the largest APK measured is
     * 5491 call sites and roughly 700 KB of JSON, which is not what an agent
     * asking a broad question wants back. Pass 0 to get all of it.
     *
     * Two keys in the answer are bookkeeping rather than sinks, both '@'-prefixed
     * so they cannot collide with a token the caller asked for: "@truncated"
     * holds one self-describing string per capped sink, naming it and its real
     * total, and "@resolved_in_apk" names the in-APK types a sink was reached
     * through. Neither appears when it has nothing to say. This tool's limit is
     * per-sink, so it keeps its own smaller default rather than taking
     * DEFAULT_RESULT_LIMIT -- 100 across a 20-tag catalogue is 214 KB, and the
     * whole uncapped sweep is 725 KB, so it was never the tool with the problem.
     */
    public Map<String, Object> findSinkCallSites(List<String> sinks, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.findSinkCallSites(sinks,
                    limit == null ? JadxAnalyzerCore.DEFAULT_CALL_SITE_LIMIT : limit);
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Supertypes, interfaces, subclasses and nesting for a type.
     *
     * Resolves framework types that are not in the APK too; the "inApk" flag in
     * the result says which kind of answer it is. Every class name in the answer
     * is one this server accepts back and reports again unchanged; see
     * JadxAnalyzerCore.getTypeHierarchy for what that took.
     */
    public Map<String, Object> getTypeHierarchy(String className) throws Exception {
        checkLoaded();
        try {
            return core.getTypeHierarchy(className);
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Methods overriding a method, plus the base methods it overrides.
     */
    public Map<String, Object> getOverrides(String className, String methodName) throws Exception {
        return getOverrides(className, methodName, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     */
    public Map<String, Object> getOverrides(String className, String methodName,
            String signature) throws Exception {
        return getOverrides(className, methodName, signature, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     * @param limit     caps "overriddenBy" and "baseMethods"; null takes the
     *                  default, 0 or negative is uncapped. This one bites on the
     *                  tool's own headline use: java.lang.Runnable.run is 7,188
     *                  implementations and 470,517 bytes on a large production APK, and a
     *                  widely-implemented framework interface is exactly what a
     *                  caller holds when resolving dispatch.
     */
    public Map<String, Object> getOverrides(String className, String methodName,
            String signature, Integer limit) throws Exception {
        checkLoaded();
        try {
            return core.getOverrides(className, methodName, signature, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Link-reachable and co-located-app-reachable components, with resource
     * references resolved to literals.
     */
    public List<Map<String, Object>> getDeeplinkComponents() throws Exception {
        checkLoaded();
        try {
            return core.getDeeplinkComponents();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Get exported components
     */
    public List<Map<String, Object>> getExportedComponents() throws Exception {
        checkLoaded();
        try {
            return core.getExportedComponentsAsMap();
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get AndroidManifest.xml content
     */
    public String getAndroidManifest() throws Exception {
        checkLoaded();
        try {
            String manifest = core.getAndroidManifest();
            if (manifest == null) {
                throw new Exception("AndroidManifest.xml not loaded");
            }
            return manifest;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get main activity class
     */
    public String getMainActivity() throws Exception {
        checkLoaded();
        try {
            String mainActivity = core.getMainActivityClass();
            if (mainActivity == null) {
                throw new Exception("No main activity found");
            }
            return mainActivity;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Resource file names, capped by default; see the overload.
     */
    public List<String> getAllResourceFileNames() throws Exception {
        return getAllResourceFileNames(null, null);
    }

    /**
     * Resource file names, optionally narrowed by path prefix and count.
     *
     * This was the only wide tool that took no arguments at all: 16,559 names
     * and 897,281 bytes on a large production APK, the same answer for every caller, with no
     * way to ask anything narrower. So it gets both halves of the treatment the
     * others got -- a default cap, and a way to not need it.
     *
     * pathPrefix is the narrowing that matches what the answer is used for.
     * res/xml is 24 of those 16,559 names and 799 bytes, and it is where
     * network_security_config, the FileProvider path declarations and
     * assetlinks live; assets/ is 2,073. A null limit takes
     * DEFAULT_RESULT_LIMIT; 0 or negative is uncapped.
     */
    public List<String> getAllResourceFileNames(String pathPrefix, Integer limit)
            throws Exception {
        checkLoaded();
        try {
            return core.getAllResourceFileNames(pathPrefix, orDefault(limit));
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Content of a resource file, capped by default; see the overload.
     */
    public String getResourceFile(String fileName) throws Exception {
        return getResourceFile(fileName, null);
    }

    /**
     * A resource file's content, capped by default.
     *
     * The widest response this server could produce, and the only one that
     * needed no opt-in whatsoever: lib/arm64-v8a/libapp.so is 150,606,754 bytes
     * of tool result and assets/audience_network.dex 11,879,027, and the second
     * is in the
     * DEFAULT 1000-name listing getAllResourceFileNames returns under a
     * docstring that says to feed any entry here. Every parameter at its
     * default, following our own instructions, reached 11 MB.
     *
     * @param maxBytes null takes the default budget; 0 or negative is uncapped
     */
    public String getResourceFile(String fileName, Integer maxBytes) throws Exception {
        checkLoaded();
        try {
            String content = core.getResourceFile(fileName, orDefaultBytes(maxBytes));
            if (content == null) {
                throw new Exception("Resource file not found: " + fileName);
            }
            return content;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }

    /**
     * Smali of a class, capped by default; see the overload.
     */
    public String getSmaliOfClass(String className) throws Exception {
        return getSmaliOfClass(className, null);
    }

    /**
     * @param maxBytes null takes the default budget; 0 or negative is uncapped.
     *                 Smali runs several times the size of its source, so this
     *                 bites a little sooner than getClassSource does:
     *                 kotlin.collections.ArraysKt___ArraysKt is 3,605,169 bytes.
     */
    public String getSmaliOfClass(String className, Integer maxBytes) throws Exception {
        checkLoaded();
        try {
            String smali = core.getSmaliOfClass(className, orDefaultBytes(maxBytes));
            if (smali == null) {
                throw new Exception("Class not found or smali not available: " + className);
            }
            return smali;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    /**
     * Get smali code of a specific method
     */
    public String getSmaliOfMethod(String className, String methodName) throws Exception {
        return getSmaliOfMethod(className, methodName, null);
    }

    /**
     * @param signature optional, picking one overload out of a shared name
     */
    public String getSmaliOfMethod(String className, String methodName, String signature)
            throws Exception {
        checkLoaded();
        try {
            String smali = core.getSmaliOfMethod(className, methodName, signature);
            if (smali == null) {
                throw new Exception("Method not found or smali not available: " + methodName + " in class " + className);
            }
            return smali;
        } catch (RuntimeException e) {
            throw new Exception(e.getMessage(), e);
        }
    }
    
    private void checkLoaded() throws Exception {
        if (core == null || !core.isLoaded()) {
            throw new Exception("No APK loaded. Call loadApk() first.");
        }
    }
    
    public void close() {
        if (currentApkPath != null) {
            registry.close(currentApkPath);
            currentApkPath = null;
        }
        core = null;
    }
}

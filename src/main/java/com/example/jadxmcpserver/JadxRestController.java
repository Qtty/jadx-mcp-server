package com.example.jadxmcpserver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The jadx analysis API.
 *
 * This is the interface the Python MCP server talks to, so addressability is
 * load-bearing: every parameter is a query parameter rather than a path
 * variable. Path variables could not represent the real inputs — resource names
 * like "res/xml/network_security_config.xml" contain slashes and simply 404'd.
 *
 * Response envelopes (the "source"/"classes"/"results" keys) are a contract with
 * mcp_server/jadx_mcp.py, which unwraps them. Do not rename them.
 *
 * A response that was refused, cut short, answered about more methods than the
 * caller named, or answered in full at a size that is itself the problem says so
 * INSIDE the envelope, never only in the daemon's log: a string beginning
 * "@rejected: ", "@truncated: ", "@ambiguous: " or "@oversized: ". It trails a
 * list, is held under a key of the same name in a map, or -- where the envelope
 * holds one string -- is its LEADING LINE, so that the same "does this string
 * start with '@'" test finds it in every shape. The status code is 200 in all of
 * them: these are answers, not transport failures. An actual failure is the one
 * thing this rule does not cover -- it is HTTP 400 with {"error": "..."}, plain
 * text and no marker. See JadxAnalyzerCore#TRUNCATION_MARKER_PREFIX.
 */
@RestController
@RequestMapping("/api/jadx")
public class JadxRestController {

    @Autowired
    private JadxApkAnalyzerAPI jadxAPI;

    /**
     * Liveness probe used by the Python supervisor to decide whether to spawn a JVM.
     *
     * loadedApk lets a caller see whether the APK it wants is already warm.
     * build identifies the artifact this JVM is running, so the supervisor can
     * tell a live daemon from a live daemon running stale code -- see BuildIdentity.
     * registry says how many APKs this daemon can hold warm, which ones it is
     * holding, and how often it has had to throw one out to make room; eviction
     * costs an 11-15 s reload and used to be entirely invisible.
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ok");
        body.put("loadedApk", jadxAPI.loadedApkPath());
        body.put("build", BuildIdentity.current());
        body.put("registry", jadxAPI.registryStats());
        body.put("reverseIndex", jadxAPI.reverseIndexStats());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/load-apk")
    public ResponseEntity<?> loadApk(@RequestBody Map<String, String> request) {
        String apkPath = request.get("apkPath");
        if (apkPath == null || apkPath.trim().isEmpty()) {
            return error("apkPath is required");
        }
        try {
            return ResponseEntity.ok(jadxAPI.loadApk(apkPath));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * Class names, capped by default.
     *
     * Both parameters are optional. An absent limit is no longer "no limit": it
     * is DEFAULT_RESULT_LIMIT, because the complete answer is 141461 names and
     * 8,097,706 bytes on the largest APK measured, and a bare GET is exactly how
     * a caller reaches it without meaning to.
     *
     * ?limit=0 and ?limit=-5 are still the same request as "no cap" and both
     * return every class, now with an @oversized entry saying what that cost.
     * A non-positive limit is clamped rather than refused with a 400, which is
     * the same rule /search-classes, /search-method and /sink-call-sites follow.
     * A caller that computes its limit should range-check it before the request;
     * there is no error to catch afterwards, and 0 widens rather than narrows.
     */
    @GetMapping("/classes")
    public ResponseEntity<?> getAllClasses(@RequestParam(required = false) String packagePrefix,
                                           @RequestParam(required = false) Integer limit) {
        try {
            return ok("classes", jadxAPI.getAllClasses(packagePrefix, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * A class's source, capped by default.
     *
     * maxBytes is to a string endpoint what limit is to a list one, and it obeys
     * the same rules: absent means DEFAULT_MAX_BYTES, 0 or negative is clamped
     * to uncapped rather than refused, and a cut answer says so on its leading
     * line rather than in the daemon's log. A separate word from limit because
     * the unit is separate -- bytes of the answer here, entries there.
     *
     * ?className=com.example.app.R.id was 6,157,819 bytes before this: all
     * 15,260 field declarations of a generated resource table whose FIELD LIST
     * has been capped since last round.
     */
    @GetMapping("/class-source")
    public ResponseEntity<?> getClassSource(@RequestParam String className,
                                            @RequestParam(required = false) Integer maxBytes) {
        try {
            return ok("source", jadxAPI.getClassSource(className, maxBytes));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * A class's members, capped by default like every other list here.
     *
     * A class reads like a narrow scope, which is why these two went uncapped
     * for so long, but a generated one is not: com.example.app.R.id declares
     * 15,260 fields and kotlin.collections.ArraysKt___ArraysKt 1,668 methods.
     * limit behaves as on /classes -- absent is the default, 0 or negative is
     * uncapped.
     */
    @GetMapping("/class-methods")
    public ResponseEntity<?> getClassMethods(@RequestParam String className,
                                             @RequestParam(required = false) Integer limit) {
        try {
            return ok("methods", jadxAPI.getMethodsOfClass(className, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/class-fields")
    public ResponseEntity<?> getClassFields(@RequestParam String className,
                                            @RequestParam(required = false) Integer limit) {
        try {
            return ok("fields", jadxAPI.getFieldsOfClass(className, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * signature is optional and picks one overload out of a shared method name;
     * omitted, the answer covers every overload and says so.
     */
    @GetMapping("/method-source")
    public ResponseEntity<?> getMethodSource(@RequestParam String className,
                                             @RequestParam String methodName,
                                             @RequestParam(required = false) String signature) {
        try {
            return ok("source", jadxAPI.getMethodSource(className, methodName, signature));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * The two substring searches. limit is optional on both and absent means
     * DEFAULT_RESULT_LIMIT, as on /classes.
     *
     * The two-character floor these gained is a guard on the query, not on the
     * answer, and the difference was measurable: ?methodName=in cleared the
     * floor and returned 22,317,076 bytes with HTTP 200 and nothing in the body
     * admitting it. A default cap is what bounds a query that is legitimate and
     * still wide.
     *
     * limit here behaves as it does on /classes: 0 or negative is clamped to
     * uncapped, not refused, and an uncapped answer over 1 MB carries an
     * @oversized entry. Only the too-short query is refused, and that comes back
     * as an @rejected entry inside a 200 rather than as an HTTP error.
     */
    @GetMapping("/search-classes")
    public ResponseEntity<?> searchClasses(@RequestParam String keyword,
                                           @RequestParam(required = false) Integer limit) {
        try {
            return ok("classes", jadxAPI.searchClassesByKeyword(keyword, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/search-method")
    public ResponseEntity<?> searchMethod(@RequestParam String methodName,
                                          @RequestParam(required = false) Integer limit) {
        try {
            return ok("results", jadxAPI.searchMethod(methodName, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * The three xref directions, capped by default.
     *
     * These were the widest uncapped responses the server could produce, and the
     * only ones with no marker anywhere in the body. Measured on this API before
     * the cap: ?className=kotlin.jvm.internal.Intrinsics returned 10,030,776
     * bytes and 53,181 entries, ?className=...Intrinsics&methodName=
     * checkNotNullParameter 7,542,206 bytes and 38,291, and the field direction
     * 1,490,741 bytes on kotlin.Unit.INSTANCE -- HTTP 200 in every case.
     *
     * limit behaves as on /classes: absent means DEFAULT_RESULT_LIMIT, 0 or
     * negative is clamped to uncapped rather than refused, and an uncapped
     * answer over 1 MB carries an @oversized entry.
     */
    @GetMapping("/xrefs-to-class")
    public ResponseEntity<?> getXrefsToClass(@RequestParam String className,
                                             @RequestParam(required = false) Integer limit) {
        try {
            return ok("xrefs", jadxAPI.getXrefsToClass(className, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * signature is optional and picks one overload out of a shared method name;
     * omitted, the answer covers every overload and says so.
     */
    @GetMapping("/xrefs-to-method")
    public ResponseEntity<?> getXrefsToMethod(@RequestParam String className,
                                              @RequestParam String methodName,
                                              @RequestParam(required = false) String signature,
                                              @RequestParam(required = false) Integer limit) {
        try {
            return ok("xrefs",
                    jadxAPI.getXrefsToMethod(className, methodName, signature, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/xrefs-to-field")
    public ResponseEntity<?> getXrefsToField(@RequestParam String className,
                                             @RequestParam String fieldName,
                                             @RequestParam(required = false) Integer limit) {
        try {
            return ok("xrefs", jadxAPI.getXrefsToField(className, fieldName, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * signature is optional and picks one overload out of a shared method name;
     * omitted, the answer covers every overload and says so.
     *
     * limit caps "callees" and "external" separately. It does not bite on either
     * reference APK -- the widest method measured is 541 entries -- so it is
     * here to close the guarantee, not because a measurement demanded it.
     */
    @GetMapping("/callees")
    public ResponseEntity<?> getCallees(@RequestParam String className,
                                        @RequestParam String methodName,
                                        @RequestParam(required = false) String signature,
                                        @RequestParam(required = false) Integer limit) {
        try {
            return ok("callees", jadxAPI.getCallees(className, methodName, signature, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * Whole-APK sink sweep.
     *
     * sinks is a comma-separated list rather than a repeated parameter because
     * the Python client builds it as one string; omitting it entirely runs the
     * default catalogue, which is the call an agent makes cold. Splitting here
     * rather than letting Spring bind a List keeps an empty "?sinks=" meaning
     * the same thing as no parameter at all.
     *
     * The response map mixes sink keys with '@'-prefixed bookkeeping keys
     * ("@truncated", "@resolved_in_apk"); '@' is not legal in a class name, a
     * method name or a catalogue tag, so a client reading by the token it asked
     * for never picks one up by accident. "@truncated" holds one self-describing
     * string per capped sink -- the same shape every other endpoint's notices
     * take, rather than the {sink: total} map it used to be.
     */
    @GetMapping("/sink-call-sites")
    public ResponseEntity<?> findSinkCallSites(@RequestParam(required = false) String sinks,
                                               @RequestParam(required = false) Integer limit) {
        try {
            List<String> requested = sinks == null || sinks.trim().isEmpty()
                    ? null
                    : Arrays.asList(sinks.split(","));
            return ok("sinkCallSites", jadxAPI.findSinkCallSites(requested, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/type-hierarchy")
    public ResponseEntity<?> getTypeHierarchy(@RequestParam String className) {
        try {
            return ok("hierarchy", jadxAPI.getTypeHierarchy(className));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * signature is optional and picks one overload out of a shared method name;
     * omitted, the answer covers every overload and says so.
     *
     * limit caps "overriddenBy" and "baseMethods", and it bites on the query
     * this endpoint exists for: ?className=java.lang.Runnable&methodName=run was
     * 7,188 implementations and 470,517 bytes on a large production APK.
     */
    @GetMapping("/overrides")
    public ResponseEntity<?> getOverrides(@RequestParam String className,
                                          @RequestParam String methodName,
                                          @RequestParam(required = false) String signature,
                                          @RequestParam(required = false) Integer limit) {
        try {
            return ok("overrides",
                    jadxAPI.getOverrides(className, methodName, signature, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/deeplink-components")
    public ResponseEntity<?> getDeeplinkComponents() {
        try {
            return ok("components", jadxAPI.getDeeplinkComponents());
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/components")
    public ResponseEntity<?> getExportedComponents() {
        try {
            return ok("components", jadxAPI.getExportedComponents());
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/manifest")
    public ResponseEntity<?> getAndroidManifest() {
        try {
            return ok("manifest", jadxAPI.getAndroidManifest());
        } catch (Exception e) {
            return error(e);
        }
    }

    @GetMapping("/main-activity")
    public ResponseEntity<?> getMainActivity() {
        try {
            return ok("mainActivity", jadxAPI.getMainActivity());
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * Resource file names, capped by default and narrowable.
     *
     * This endpoint took no parameters at all and returned 897,281 bytes and
     * 16,559 names on a large production APK -- the same answer for every caller, with no way
     * to ask for less. pathPrefix is the narrowing that matches the question
     * people actually have: ?pathPrefix=res/xml is 24 names and 799 bytes and
     * covers the security config, the FileProvider paths and assetlinks.
     * It matches case-insensitively against the whole archive path, exactly as
     * packagePrefix does on /classes; limit behaves the same too.
     */
    @GetMapping("/resources")
    public ResponseEntity<?> getAllResourceFileNames(
            @RequestParam(required = false) String pathPrefix,
            @RequestParam(required = false) Integer limit) {
        try {
            return ok("resources", jadxAPI.getAllResourceFileNames(pathPrefix, limit));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * A resource file's content, capped by default.
     *
     * This endpoint had no size guard at any layer and produced the largest
     * response the server could: ?fileName=lib/arm64-v8a/libapp.so was
     * 150,606,754 bytes, and ?fileName=assets/audience_network.dex 11,879,027
     * from a name the DEFAULT /resources listing hands out. maxBytes behaves as
     * on /class-source; it cuts the raw bytes before decoding, so an oversized
     * entry is now a 1 MB short read rather than a 48 MB allocation.
     */
    @GetMapping("/resource")
    public ResponseEntity<?> getResourceFile(@RequestParam String fileName,
                                             @RequestParam(required = false) Integer maxBytes) {
        try {
            return ok("content", jadxAPI.getResourceFile(fileName, maxBytes));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** maxBytes behaves as on /class-source; smali runs several times larger. */
    @GetMapping("/class-smali")
    public ResponseEntity<?> getSmaliOfClass(@RequestParam String className,
                                             @RequestParam(required = false) Integer maxBytes) {
        try {
            return ok("smali", jadxAPI.getSmaliOfClass(className, maxBytes));
        } catch (Exception e) {
            return error(e);
        }
    }

    /**
     * signature is optional and picks one overload out of a shared method name;
     * omitted, the answer covers every overload and says so.
     */
    @GetMapping("/method-smali")
    public ResponseEntity<?> getSmaliOfMethod(@RequestParam String className,
                                              @RequestParam String methodName,
                                              @RequestParam(required = false) String signature) {
        try {
            return ok("smali", jadxAPI.getSmaliOfMethod(className, methodName, signature));
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/close")
    public ResponseEntity<?> closeAnalyzer() {
        try {
            jadxAPI.close();
            return ok("message", "Analyzer closed successfully");
        } catch (Exception e) {
            return error(e);
        }
    }

    // HashMap rather than Map.of: jadx can legitimately return null for a missing
    // resource or an absent main activity, and Map.of throws on null values.
    private static ResponseEntity<?> ok(String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put(key, value);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<?> error(Exception e) {
        return error(String.valueOf(e.getMessage()));
    }

    private static ResponseEntity<?> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.badRequest().body(body);
    }
}

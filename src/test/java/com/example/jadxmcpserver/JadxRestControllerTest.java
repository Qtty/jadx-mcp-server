package com.example.jadxmcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST layer is the interface the Python MCP server talks to, so its
 * addressability is now load-bearing rather than a convenience.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JadxRestControllerTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthRespondsBeforeAnyApkIsLoaded() {
        ResponseEntity<Map> r = rest.getForEntity("/api/jadx/health", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("status");
    }

    @Test
    void resourcePathWithSlashesIsAddressable() {
        // The old /resource/{fileName} mapping 404s on this shape, which is most
        // real resource paths. Anything but 404 means the route was matched and
        // the request reached the handler.
        ResponseEntity<Map> r = rest.getForEntity(
                "/api/jadx/resource?fileName={f}", Map.class,
                "res/xml/network_security_config.xml");
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The sink sweep has to be callable with no arguments at all -- that is the
     * cold call an agent makes before it knows what to look for -- and with a
     * comma-separated list of dotted names, which is exactly the shape a path
     * variable could not carry.
     */
    @Test
    void sinkCallSitesIsAddressableWithAndWithoutSinks() {
        ResponseEntity<Map> bare = rest.getForEntity("/api/jadx/sink-call-sites", Map.class);
        assertThat(bare.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> listed = rest.getForEntity(
                "/api/jadx/sink-call-sites?sinks={s}&limit=10", Map.class,
                "android.content.Intent.parseUri,android.webkit.WebView.loadUrl");
        assertThat(listed.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void classSourceIsAddressableByFullyQualifiedName() {
        ResponseEntity<Map> r = rest.getForEntity(
                "/api/jadx/class-source?className={c}", Map.class,
                "com.example.Foo");
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * /classes has to keep answering a bare GET -- that is what every agent file
     * calls -- and to accept the two narrowing parameters, whose whole purpose
     * is to avoid the 7.7 MB whole-APK answer.
     */
    @Test
    void classesIsAddressableBareAndWithNarrowingParameters() {
        ResponseEntity<Map> bare = rest.getForEntity("/api/jadx/classes", Map.class);
        assertThat(bare.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> narrowed = rest.getForEntity(
                "/api/jadx/classes?packagePrefix={p}&limit=50", Map.class, "com.example.app");
        assertThat(narrowed.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(narrowed.getStatusCode()).isEqualTo(bare.getStatusCode());
    }

    /**
     * The two search endpoints gained the same optional limit /classes has, and
     * for the same reason -- search-method with an empty methodName was 58.9 MB
     * and search-classes with a one-character keyword 7.5 MB. Both must still
     * answer the one-parameter call every agent file makes.
     */
    @Test
    void bothSearchesAreAddressableWithAndWithoutALimit() {
        ResponseEntity<Map> bareMethod = rest.getForEntity(
                "/api/jadx/search-method?methodName={m}", Map.class, "onCreate");
        assertThat(bareMethod.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/jadx/search-method?methodName={m}&limit=10",
                Map.class, "onCreate").getStatusCode())
                .isEqualTo(bareMethod.getStatusCode());

        ResponseEntity<Map> bareClasses = rest.getForEntity(
                "/api/jadx/search-classes?keyword={k}", Map.class, "Activity");
        assertThat(bareClasses.getStatusCode()).isNotEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/jadx/search-classes?keyword={k}&limit=10",
                Map.class, "Activity").getStatusCode())
                .isEqualTo(bareClasses.getStatusCode());
    }

    /**
     * The endpoints that gained a limit in the size-guard round, and /resources
     * which gained a pathPrefix as well.
     *
     * A query parameter Spring cannot bind is a 400 that no unit test below this
     * layer can see, and every one of these has to keep answering the bare call
     * an agent file makes -- the cap is a default, never a requirement. The
     * status codes are compared rather than asserted absolutely because no APK
     * is loaded here; what matters is that adding the parameter does not change
     * the answer's shape.
     */
    @Test
    void theNewlyCappedEndpointsTakeALimitAndStillAnswerBare() {
        String[][] endpoints = {
            {"xrefs-to-class", "className=kotlin.Unit"},
            {"xrefs-to-method", "className=kotlin.Unit&methodName=toString"},
            {"xrefs-to-field", "className=kotlin.Unit&fieldName=INSTANCE"},
            {"callees", "className=a.B&methodName=c"},
            {"overrides", "className=java.lang.Runnable&methodName=run"},
            {"class-methods", "className=a.B"},
            {"class-fields", "className=a.B"},
            {"resources", ""},
        };
        for (String[] e : endpoints) {
            String q = e[1].isEmpty() ? "" : "?" + e[1];
            ResponseEntity<Map> bare =
                    rest.getForEntity("/api/jadx/" + e[0] + q, Map.class);
            assertThat(bare.getStatusCode()).as(e[0])
                    .isNotEqualTo(HttpStatus.NOT_FOUND);
            assertThat(rest.getForEntity(
                    "/api/jadx/" + e[0] + (q.isEmpty() ? "?" : q + "&") + "limit=10",
                    Map.class).getStatusCode())
                    .as(e[0] + " with limit").isEqualTo(bare.getStatusCode());
        }

        ResponseEntity<Map> bareResources =
                rest.getForEntity("/api/jadx/resources", Map.class);
        assertThat(rest.getForEntity("/api/jadx/resources?pathPrefix={p}", Map.class,
                "res/xml").getStatusCode())
                .as("resources with pathPrefix").isEqualTo(bareResources.getStatusCode());
    }

    /**
     * Eviction used to be invisible: two analyses on one daemon at capacity 1 paid
     * an 11-15 s reload per switch and nothing said so. /health is where an
     * operator or a supervisor can see it, so the counters belong in the body --
     * additively, next to the keys the Python supervisor already reads.
     */
    @Test
    @SuppressWarnings("unchecked")
    void healthReportsRegistryCapacityAndThrashCounters() {
        ResponseEntity<Map> r = rest.getForEntity("/api/jadx/health", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKeys("status", "loadedApk", "build", "registry");

        Map<String, Object> registry = (Map<String, Object>) r.getBody().get("registry");
        assertThat(registry).containsKeys("capacity", "loaded", "loads", "evictions",
                "thrashEvictions", "reloads", "lastThrash", "apkSwitches");
        assertThat((Integer) registry.get("capacity")).isGreaterThanOrEqualTo(1);
    }
}

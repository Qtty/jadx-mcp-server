package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * get_deeplink_components is the one parity tool whose JEB counterpart does real
 * work rather than answering a query: it returns browsable components with
 * resource references resolved to literals.
 *
 * The record shape is not free to choose. A downstream analysis client consumes
 * this output field for field, so this must produce the same fields under the
 * same names or the client cannot consume it.
 *
 * get_exported_components is checked here too, and deliberately in this file
 * rather than beside itself: as of 2026-08-08 the two tools are one parse and
 * one record shape, differing only in which components they keep, and a test
 * that could not see both at once is how they drifted into two shapes.
 *
 * Note on fixtures: DIVA is not obfuscated and its manifest declares no
 * BROWSABLE categories and no android:scheme attributes, so it covers the
 * record shape and the exported second tier but not @string/-referenced scheme
 * resolution, which needs a manifest that carries one.
 */
class DeeplinkComponentsTest {

    static final String SMALL_APK = "misc/DivaApplication.apk";

    static JadxAnalyzerCore core;

    @BeforeAll
    static void load() {
        assumeTrue(new File(SMALL_APK).exists(), "fixture " + SMALL_APK + " missing");
        core = new JadxAnalyzerCore(SMALL_APK);
        core.loadApk();
    }

    @Test
    void includesTheLauncherActivity() {
        Map<String, Object> main = byName("jakhar.aseem.diva.MainActivity");

        assertThat(main).isNotNull();
        assertThat(main.get("is_main")).isEqualTo(true);
    }

    /**
     * Every field the client's manifest parser emits, because the client's component store validates
     * against that shape. A missing key is a broken hand-off, not a cosmetic gap.
     */
    @Test
    void recordsCarryTheFieldsTheHunterPipelineExpects() {
        Map<String, Object> main = byName("jakhar.aseem.diva.MainActivity");

        assertThat(main).containsKeys(
                "name", "type", "is_main", "is_browsable",
                "exported", "exported_reason", "intent_filters");
    }

    @Test
    void componentTypeUsesTheHunterVocabulary() {
        assertThat(byName("jakhar.aseem.diva.MainActivity").get("type")).isEqualTo("activity");
        assertThat(byName("jakhar.aseem.diva.NotesProvider").get("type")).isEqualTo("provider");
    }

    /**
     * The second tier: exported with no BROWSABLE filter, reachable by a
     * co-located app rather than a link. A MAIN/BROWSABLE-only inventory misses
     * these entirely, and the caller's corpus is full of them.
     */
    @Test
    void includesExportedComponentsThatNoLinkCanReach() {
        // APICredsActivity is exported via a custom-action intent-filter.
        Map<String, Object> c = byName("jakhar.aseem.diva.APICredsActivity");

        assertThat(c).isNotNull();
        assertThat(c.get("exported")).isEqualTo(true);
        assertThat(c.get("is_browsable")).isEqualTo(false);
    }

    @Test
    void exportedReasonExplainsWhyAComponentCounts() {
        assertThat(byName("jakhar.aseem.diva.APICredsActivity").get("exported_reason"))
                .asString().isNotBlank();
    }

    @Test
    void intentFiltersCarryActionsAndCategories() {
        Map<String, Object> c = byName("jakhar.aseem.diva.APICredsActivity");

        List<Map<String, Object>> filters = filtersOf(c);
        assertThat(filters).isNotEmpty();
        assertThat(String.valueOf(filters.get(0).get("actions")))
                .contains("jakhar.aseem.diva.action.VIEW_CREDS");
    }

    @Test
    void namesAreFullyQualified() {
        for (Map<String, Object> c : core.getDeeplinkComponents()) {
            assertThat(String.valueOf(c.get("name")))
                    .as("component names must be resolvable class names")
                    .doesNotStartWith(".");
        }
    }

    /**
     * One concept, one shape. The two components tools describe overlapping
     * populations, and they used to describe them in incompatible shapes --
     * {type, name, intentFilters as prose strings, permission, exported} against
     * the record here. A caller could not read a scheme out of both, or out of
     * the first at all.
     */
    @Test
    void theExportedToolAnswersInTheSameShape() {
        for (Map<String, Object> c : core.getExportedComponentsAsMap()) {
            assertThat(c).containsKeys(
                    "name", "type", "is_main", "is_browsable",
                    "exported", "exported_reason", "intent_filters");
            assertThat(c).doesNotContainKey("intentFilters");
            assertThat(c.get("exported")).isEqualTo(true);
        }
    }

    /**
     * Where the two populations differ, neither contains the other, and where
     * they overlap they must not disagree: an exported component that also
     * appears in the deeplink list has to carry the identical record in both.
     * The old exported parser had no view of the browsable tier at all, so the
     * populations agreeing here is a property of the parse, not a coincidence.
     */
    @Test
    void everyExportedComponentAgreesWithTheDeeplinkRecordOfTheSameName() {
        Map<String, Map<String, Object>> deeplink = new java.util.LinkedHashMap<>();
        for (Map<String, Object> c : core.getDeeplinkComponents()) {
            deeplink.put(String.valueOf(c.get("name")), c);
        }
        for (Map<String, Object> e : core.getExportedComponentsAsMap()) {
            Map<String, Object> other = deeplink.get(String.valueOf(e.get("name")));
            if (other == null) {
                continue;  // exported but neither link- nor rogue-reachable
            }
            assertThat(e)
                    .as("the two tools must not disagree about %s", e.get("name"))
                    .isEqualTo(other);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filtersOf(Map<String, Object> c) {
        return (List<Map<String, Object>>) c.get("intent_filters");
    }

    private static Map<String, Object> byName(String name) {
        for (Map<String, Object> c : core.getDeeplinkComponents()) {
            if (name.equals(c.get("name"))) {
                return c;
            }
        }
        return null;
    }
}

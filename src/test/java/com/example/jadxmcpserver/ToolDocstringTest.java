package com.example.jadxmcpserver;

import com.example.jadxmcpserver.core.DocClaims;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The tool descriptions the model reads are checked the same way the consumer
 * reference is.
 *
 * <h2>Why this exists</h2>
 *
 * A {@code @mcp.tool} docstring in {@code mcp_server/jadx_mcp.py} is not a
 * comment. It is the text a model is handed when it decides whether to call a
 * tool, with what arguments, and what to do with what comes back -- the same
 * job {@code the client's tool reference} does for a human worker, on the same figures.
 * It went unchecked while the markdown was being verified figure by figure, and
 * the round that built that verification put three errors into these
 * docstrings in the same commit:
 *
 * <ul>
 *   <li>{@code get_method_by_name} quoted {@code get_smali_of_method}'s widest
 *       overload set as its own;</li>
 *   <li>{@code get_android_manifest} stated its cap in CHARACTERS while
 *       {@code guardedText} spends BYTES -- the character/byte confusion the
 *       same commit fixed one docstring above;</li>
 *   <li>{@code get_type_hierarchy} gave 51,544 for the population the verified
 *       doc measures at 51,600.</li>
 * </ul>
 *
 * <h2>What is checked</h2>
 *
 * {@code src/test/resources/tool-docstring-claims.md} carries the same two
 * fenced blocks the consumer reference does ({@link CheckedFigures}), and the
 * prose they describe is the concatenation of the 23 tool docstrings. So the
 * same four things have to agree -- the code, the measurement, the block, the
 * sentence the model reads -- and every measurement-shaped figure in a
 * docstring is either re-run or declared unverified out loud.
 *
 * Unlike the consumer reference, both files here are in this repository, so
 * this suite runs everywhere, including on a CI runner that has neither the
 * sibling checkout nor the large APK. What such a runner cannot do is re-run
 * the large-APK measurements; those abort with a named reason, and the census
 * says how many.
 */
class ToolDocstringTest {

    static final String SERVER = "mcp_server/jadx_mcp.py";
    static final String CLAIMS = "src/test/resources/tool-docstring-claims.md";

    private static final String LABEL = "jadx_mcp.py @mcp.tool docstrings";

    @AfterAll
    static void close() {
        DocClaims.closeAll();
    }

    // --------------------------------------------------- the text the model reads

    /**
     * Every {@code @mcp.tool} function's docstring, in file order.
     *
     * Deliberately not every docstring in the file: the module docstring and
     * the helpers' are read by people working on the server, and only these 23
     * are shipped to a model as tool descriptions.
     */
    static Map<String, String> docstrings() throws Exception {
        String src = Files.readString(Path.of(SERVER));
        Pattern p = Pattern.compile(
                "@mcp\\.tool[^\\n]*\\n"                 // the decorator
                        + "(?:\\s*(?:async\\s+)?def\\s+(\\w+)[\\s\\S]*?)"  // def NAME(...)...:
                        + "\"\"\"([\\s\\S]*?)\"\"\"");                     // its docstring
        Matcher m = p.matcher(src);
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** The docstrings as one text, each under a heading naming its tool. */
    static String prose(Map<String, String> docstrings) {
        StringBuilder sb = new StringBuilder();
        docstrings.forEach((name, body) ->
                sb.append("### ").append(name).append('\n').append(body).append('\n'));
        return sb.toString();
    }

    static CheckedFigures docstringFigures() throws Exception {
        return CheckedFigures.of(LABEL, CLAIMS,
                Files.readString(Path.of(CLAIMS)), prose(docstrings()));
    }

    // ----------------------------------------------------------- the checks

    /**
     * The extraction found every tool, so a silent parse failure cannot turn
     * the rest of this suite into a check of nothing.
     */
    @Test
    void everyToolDocstringIsFound() throws Exception {
        Map<String, String> found = docstrings();
        long decorators = Files.readAllLines(Path.of(SERVER)).stream()
                .filter(l -> l.contains("@mcp.tool")).count();
        assertThat(found).as("tools parsed out of %s", SERVER).hasSize((int) decorators);
        assertThat(decorators).as("@mcp.tool decorators in %s", SERVER).isEqualTo(23);

        List<String> empty = new ArrayList<>();
        found.forEach((name, body) -> {
            if (body.isBlank()) {
                empty.add(name);
            }
        });
        assertThat(empty).as("a tool the model is handed with no description").isEmpty();
    }

    /** Every claimed docstring figure names a measurement that re-runs it. */
    @Test
    void everyClaimIsMeasurableAndDescribedTheWayItWasMeasured() throws Exception {
        CheckedFigures ds = docstringFigures();
        assertThat(ds.duplicateIds()).as("the same id claimed twice").isEmpty();
        assertThat(ds.unmeasurable())
                .as("claimed in %s with nothing in DocClaims that re-runs it", CLAIMS)
                .isEmpty();
        assertThat(ds.misdescribed())
                .as("%s describes a different call or unit than the one measured", CLAIMS)
                .isEmpty();
    }

    /** The figure in the block is the figure in the sentence the model reads. */
    @Test
    void everyClaimIsCitedInADocstringAndTheDocstringCarriesTheValue() throws Exception {
        assertThat(docstringFigures().citationProblems())
                .as("a claim whose sentence is missing from the docstrings, duplicated,"
                        + " or missing its value")
                .isEmpty();
    }

    /**
     * Nothing a model reads here is a figure that nobody checks.
     *
     * This is the check that would have caught all three of the errors named in
     * this class's comment: two of them state a number no measurement produces,
     * and the third states one in the wrong unit.
     */
    @Test
    void everyFigureInADocstringIsEitherMeasuredOrDeclaredUnverified() throws Exception {
        CheckedFigures ds = docstringFigures();
        assertThat(ds.unregisteredFigures())
                .as("a figure in a tool description that no claim measures and no"
                        + " declaration excuses")
                .isEmpty();
        assertThat(ds.deadDeclarations())
                .as("an unverified declaration for a figure no docstring states")
                .isEmpty();
        assertThat(ds.declaredYetMeasured())
                .as("declared unverified while a claim measures it")
                .isEmpty();
    }

    /** One test per claim, so a stale figure names itself. */
    @TestFactory
    Stream<DynamicTest> everyStatedFigureStillMeasuresTheSame() throws Exception {
        boolean haveLarge = DocClaims.largeApkPresent();
        return docstringFigures().claims.stream().map(c -> DynamicTest.dynamicTest(
                c.id + " = " + c.expected + " " + c.unit,
                () -> {
                    if (c.apk.equals("large") && !haveLarge) {
                        // "big APK not present" is the phrase the rest of
                        // this suite's assumptions use and the phrase CI's
                        // skip-reason guard knows; keep it even when -DlargeApk
                        // points somewhere else.
                        abort("big APK not present (" + DocClaims.LARGE_APK + "): "
                                + c.call + " not re-run");
                    }
                    DocClaims.Claim probe = DocClaims.byId(c.id);
                    assertThat(probe).as("no probe for %s", c.id).isNotNull();
                    assertThat(probe.measure.value())
                            .as("%s -- %s, in %s", c.id, probe.call, probe.unit)
                            .isEqualTo(c.expected);
                }));
    }

    /** What did not get checked, and why, where a reader of the run sees it. */
    @Test
    void reportWhatWasNotChecked() throws Exception {
        CheckedFigures ds = docstringFigures();
        long large = ds.claims.stream().filter(c -> c.apk.equals("large")).count();
        boolean haveLarge = DocClaims.largeApkPresent();
        int[] figures = ds.census();
        System.out.println("---- " + LABEL + " census ----");
        System.out.println("  descriptions   : " + docstrings().size() + " from " + SERVER);
        System.out.println("  claims         : " + ds.claims.size() + " from " + CLAIMS);
        System.out.println("  checked always : " + (ds.claims.size() - large)
                + " (fixture + source tree)");
        System.out.println("  large-APK      : " + large
                + (haveLarge ? " (checked: " + DocClaims.LARGE_APK + " present)"
                             : " NOT CHECKED: " + DocClaims.LARGE_APK + " absent"));
        System.out.println("  figures        : " + figures[0] + " measured, " + figures[1]
                + " declared unverified in " + CLAIMS + ", " + figures[2] + " unaccounted");
        System.out.println("------------------------------------------------");
    }
}

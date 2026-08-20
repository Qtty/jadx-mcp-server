package com.example.jadxmcpserver;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daemon is meant to be run one-per-analysis on its own port, but every one of
 * them used to append to the single hardcoded /tmp/jadx-mcp-server.log. The
 * warnings that matter most there -- AnalyzerRegistry's cache-thrash warning and
 * the oversize-result warnings -- are only discoverable in that file, so with two
 * analyses in flight you could not attribute a warning to a daemon without matching
 * pids by hand.
 *
 * The rule: the log path follows the port, and the default port keeps the path
 * everything already greps for.
 */
class DaemonLogFileTest {

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String[] NO_ARGS = new String[0];

    // --- the path rule ------------------------------------------------------

    @Test
    void defaultPortKeepsTheHistoricalPath() {
        // Anything that greps /tmp/jadx-mcp-server.log must keep working.
        assertThat(DaemonLogFile.forPort("8765")).isEqualTo("/tmp/jadx-mcp-server.log");
    }

    @Test
    void otherPortsGetTheirOwnFile() {
        assertThat(DaemonLogFile.forPort("8791")).isEqualTo("/tmp/jadx-mcp-server-8791.log");
        assertThat(DaemonLogFile.forPort("8702")).isEqualTo("/tmp/jadx-mcp-server-8702.log");
    }

    @Test
    void twoPortsNeverShareAFile() {
        assertThat(DaemonLogFile.forPort("8791")).isNotEqualTo(DaemonLogFile.forPort("8792"));
    }

    @Test
    void anUnusablePortFallsBackRatherThanBuildingAPath() {
        // A port is going to be interpolated into a filesystem path. Nothing but
        // digits is allowed near it.
        assertThat(DaemonLogFile.forPort("../../etc/passwd")).isEqualTo("/tmp/jadx-mcp-server.log");
        assertThat(DaemonLogFile.forPort("")).isEqualTo("/tmp/jadx-mcp-server.log");
        assertThat(DaemonLogFile.forPort(null)).isEqualTo("/tmp/jadx-mcp-server.log");
    }

    // --- where the port comes from -----------------------------------------

    @Test
    void portDefaultsToTheOneApplicationPropertiesDefaultsTo() {
        assertThat(DaemonLogFile.port(NO_ARGS, Collections.emptyMap(), props()))
                .isEqualTo("8765");
    }

    @Test
    void jadxPortEnvIsHonoured() {
        // How run-api.sh and the Python supervisor set it.
        assertThat(DaemonLogFile.port(NO_ARGS, env("JADX_PORT", "8791"), props()))
                .isEqualTo("8791");
    }

    @Test
    void serverPortSystemPropertyIsHonoured() {
        // How agents on this machine actually launch a second daemon:
        //   java -Xmx16g -Dserver.port=8791 -jar target/jadx-mcp-server-1.0.0.jar
        assertThat(DaemonLogFile.port(NO_ARGS, Collections.emptyMap(), props("server.port", "8791")))
                .isEqualTo("8791");
    }

    @Test
    void commandLineArgWinsOverEverythingElse() {
        // Spring's own precedence: --server.port beats -Dserver.port beats the
        // placeholder default fed from JADX_PORT.
        String[] args = {"--server.port=8793"};
        assertThat(DaemonLogFile.port(args, env("JADX_PORT", "8791"), props("server.port", "8792")))
                .isEqualTo("8793");
        assertThat(DaemonLogFile.port(NO_ARGS, env("JADX_PORT", "8791"), props("server.port", "8792")))
                .isEqualTo("8792");
    }

    // --- overrides ----------------------------------------------------------

    @Test
    void anExplicitLogFileWinsOverThePortRule() {
        assertThat(DaemonLogFile.resolve(NO_ARGS, env("JADX_PORT", "8791",
                                                      "JADX_LOG_FILE", "/var/log/mine.log"),
                                         props()))
                .isEqualTo("/var/log/mine.log");
    }

    @Test
    void resolveOtherwiseFollowsThePort() {
        assertThat(DaemonLogFile.resolve(NO_ARGS, env("JADX_PORT", "8791"), props()))
                .isEqualTo("/tmp/jadx-mcp-server-8791.log");
    }

    @Test
    void installDoesNotOverwriteAnExplicitSystemProperty() {
        Properties sys = props("jadx.log.file", "/already/chosen.log");
        DaemonLogFile.install(NO_ARGS, env("JADX_PORT", "8791"), sys);
        assertThat(sys.getProperty("jadx.log.file")).isEqualTo("/already/chosen.log");
    }

    @Test
    void installPublishesThePlaceholderApplicationPropertiesReads() {
        // application.properties says logging.file.name=${jadx.log.file:...},
        // and this runs before Spring initialises the logging system.
        Properties sys = props();
        DaemonLogFile.install(NO_ARGS, env("JADX_PORT", "8791"), sys);
        assertThat(sys.getProperty("jadx.log.file")).isEqualTo("/tmp/jadx-mcp-server-8791.log");
    }
}

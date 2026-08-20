package com.example.jadxmcpserver;

import java.util.Map;
import java.util.Properties;

/**
 * Where this daemon's log goes: one file per port.
 *
 * The daemon is meant to be run one-per-concurrent-analysis on its own port --
 * AnalyzerRegistry's own thrash warning says so, and JADX_PORT is supported for
 * exactly that. But application.properties pinned logging.file.name to a single
 * hardcoded /tmp/jadx-mcp-server.log, so every daemon on the machine interleaved
 * into it. The warnings that are only discoverable there (cache thrash, the
 * oversize-result guards) could then not be attributed to a daemon without
 * matching pids by hand.
 *
 * The rule: the default port keeps the historical path, so anything already
 * grepping /tmp/jadx-mcp-server.log is unaffected; any other port gets
 * /tmp/jadx-mcp-server-&lt;port&gt;.log. mcp_server/daemon.py mirrors this for the
 * daemons it spawns, and passes JADX_PORT so both halves agree on the answer.
 *
 * This runs from main() rather than from an EnvironmentPostProcessor because
 * Spring initialises its logging system very early, off
 * ApplicationEnvironmentPreparedEvent, and the file name has to be settled
 * before the first appender opens. So the port is resolved here the same way
 * Spring would resolve it, and published as the {@code jadx.log.file} system
 * property that application.properties interpolates.
 *
 * Console output is deliberately not part of the fix. The daemon is detached and
 * its stdout has no reader; per-daemon files are the answer, not a chattier
 * console.
 */
public final class DaemonLogFile {

    /** Must match server.port=${JADX_PORT:8765} in application.properties. */
    public static final String DEFAULT_PORT = "8765";

    /** The path this daemon has always written to, kept for the default port. */
    public static final String DEFAULT_PATH = "/tmp/jadx-mcp-server.log";

    /** The placeholder application.properties reads. */
    public static final String PROPERTY = "jadx.log.file";

    private DaemonLogFile() {
    }

    /**
     * Resolve the log file and publish it, unless someone has already chosen one.
     * Call before SpringApplication.run.
     */
    public static void install(String[] args) {
        install(args, System.getenv(), System.getProperties());
    }

    static void install(String[] args, Map<String, String> env, Properties sysProps) {
        if (isSet(sysProps.getProperty(PROPERTY))) {
            return;
        }
        sysProps.setProperty(PROPERTY, resolve(args, env, sysProps));
    }

    /** An explicit JADX_LOG_FILE, otherwise the file for this daemon's port. */
    static String resolve(String[] args, Map<String, String> env, Properties sysProps) {
        String explicit = env.get("JADX_LOG_FILE");
        if (isSet(explicit)) {
            return explicit;
        }
        return forPort(port(args, env, sysProps));
    }

    /**
     * The log file for a port. Anything that is not a plain number falls back to
     * the default path rather than being interpolated into a filesystem path.
     */
    static String forPort(String port) {
        if (!isNumeric(port) || DEFAULT_PORT.equals(port)) {
            return DEFAULT_PATH;
        }
        int dot = DEFAULT_PATH.lastIndexOf('.');
        return DEFAULT_PATH.substring(0, dot) + "-" + port + DEFAULT_PATH.substring(dot);
    }

    /**
     * The port this JVM will bind, in Spring's own order of precedence.
     *
     * All four sources are in live use here: run-api.sh and the Python
     * supervisor set JADX_PORT, and a second daemon started by hand is usually
     * {@code java -Dserver.port=8791 -jar ...}.
     */
    static String port(String[] args, Map<String, String> env, Properties sysProps) {
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--server.port=")) {
                    return arg.substring("--server.port=".length());
                }
            }
        }
        String[] candidates = {
                sysProps.getProperty("server.port"),
                env.get("SERVER_PORT"),
                env.get("JADX_PORT"),
        };
        for (String candidate : candidates) {
            if (isSet(candidate)) {
                return candidate;
            }
        }
        return DEFAULT_PORT;
    }

    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean isNumeric(String s) {
        if (!isSet(s)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

package com.example.jadxmcpserver;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifies the artifact this JVM is actually running.
 *
 * The daemon is deliberately long-lived and shared, which creates a failure mode
 * nothing else here guards against: it can outlive the code it was built from.
 * That has already happened once -- a merge landed 21 tools in the source while
 * target/ still held a 14-tool jar from two hours earlier, and the supervisor
 * happily reused the running daemon because it answered /health. Every tool call
 * then ran old analysis code and returned confidently wrong answers.
 *
 * Reporting the artifact path plus its mtime and size lets the Python supervisor
 * compare what is running against what is on disk. The path matters as much as
 * the timestamp: a daemon started from a git worktree's target/ while the client
 * points at the main checkout is the same class of bug and is otherwise invisible.
 *
 * Resolved once at class-init: these values describe the file this JVM booted
 * from, so re-reading them after someone rebuilds over it would defeat the point.
 */
public final class BuildIdentity {

    private static final Map<String, Object> IDENTITY = compute();

    private BuildIdentity() {
    }

    public static Map<String, Object> current() {
        return IDENTITY;
    }

    private static Map<String, Object> compute() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("pid", ProcessHandle.current().pid());

        Path artifact = resolveArtifact();
        if (artifact == null) {
            // Running from exploded classes (tests, an IDE). There is no single
            // artifact to compare against, so say so rather than guessing --
            // the supervisor treats an absent artifact as "cannot determine"
            // and leaves the daemon alone.
            id.put("artifact", null);
            id.put("mtime", 0L);
            id.put("size", 0L);
            return id;
        }

        id.put("artifact", artifact.toString());
        try {
            id.put("mtime", Files.getLastModifiedTime(artifact).toMillis());
            id.put("size", Files.size(artifact));
        } catch (Exception e) {
            id.put("mtime", 0L);
            id.put("size", 0L);
        }
        return id;
    }

    /**
     * The jar this JVM was launched from, or null when running from classes.
     *
     * Launched as {@code java -jar foo.jar}, java.class.path is exactly that jar.
     * The code-source location is unreliable for Spring Boot fat jars, where
     * classes load from a nested BOOT-INF entry rather than the jar itself.
     */
    private static Path resolveArtifact() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty() || cp.contains(File.pathSeparator) || !cp.endsWith(".jar")) {
            return null;
        }
        try {
            Path p = Path.of(cp).toAbsolutePath().normalize();
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}

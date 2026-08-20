package com.example.jadxmcpserver.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The registry is what turns the JVM from a per-session subprocess into a warm
 * daemon: re-analysing the same APK must not re-parse it.
 */
class AnalyzerRegistryTest {

    static final String APK = "misc/DivaApplication.apk";

    @BeforeAll
    static void fixtureExists() {
        assumeTrue(new File(APK).exists(), "test fixture " + APK + " not present");
    }

    @Test
    void reloadingTheSameApkReusesTheLoadedAnalyzer() {
        AnalyzerRegistry registry = new AnalyzerRegistry(1);
        try {
            JadxAnalyzerCore first = registry.get(APK);
            JadxAnalyzerCore second = registry.get(APK);

            assertThat(second).as("same APK must not be re-parsed").isSameAs(first);
            assertThat(registry.loadedApkPath()).isEqualTo(APK);
        } finally {
            registry.closeAll();
        }
    }

    @Test
    void loadingADifferentApkEvictsTheOldOneAtCapacityOne(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        AnalyzerRegistry registry = new AnalyzerRegistry(1);
        try {
            JadxAnalyzerCore a = registry.get(APK);
            registry.get(other.toString());

            assertThat(registry.loadedApkPath()).isEqualTo(other.toString());
            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.get(APK)).as("evicted entry is reloaded").isNotSameAs(a);
        } finally {
            registry.closeAll();
        }
    }

    @Test
    void bothApksStayResidentWhenCapacityAllows(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        AnalyzerRegistry registry = new AnalyzerRegistry(2);
        try {
            JadxAnalyzerCore a = registry.get(APK);
            registry.get(other.toString());

            assertThat(registry.size()).isEqualTo(2);
            assertThat(registry.get(APK)).as("still warm").isSameAs(a);
        } finally {
            registry.closeAll();
        }
    }

    /**
     * The capacity default is a memory decision, so it is derived from the heap
     * the JVM was actually given rather than from a constant that was true on
     * one machine.
     *
     * The numbers come from measurement, not taste: the worst APK on this box
     * (a large production APK, 141461 classes) retains 2.9 GB after load and grows to 3.6 GB
     * after 300 classes are decompiled, so a slot is budgeted at 6 GB and 4 GB
     * is left unspent for decompilation, JSON encoding and GC headroom.
     */
    @Test
    void capacityDefaultIsDerivedFromTheHeapNotHardcoded() {
        assertThat(AnalyzerRegistry.defaultCapacity(gb(24)))
                .as("the daemon's own -Xmx24g fits two warm APKs").isEqualTo(2);
        assertThat(AnalyzerRegistry.defaultCapacity(gb(16))).isEqualTo(2);
        assertThat(AnalyzerRegistry.defaultCapacity(gb(12)))
                .as("no room for a second slot: evict rather than risk an OOM").isEqualTo(1);
        assertThat(AnalyzerRegistry.defaultCapacity(gb(2)))
                .as("never below one, however small the heap").isEqualTo(1);
        assertThat(AnalyzerRegistry.defaultCapacity(gb(256)))
                .as("a bigger heap does not buy slots nothing asks for")
                .isEqualTo(AnalyzerRegistry.MAX_DEFAULT_CAPACITY);
    }

    /**
     * The thrash pattern: two analyses sharing one daemon at capacity 1, so every
     * access evicts an analyzer that was in use seconds ago and pays a full
     * reload -- measured at 11-12 s per access on the two largest APKs here.
     *
     * The point of the assertions is that it stops being SILENT. Nothing about
     * the answers changes; what changes is that the daemon counts it, names it
     * in the log at WARNING, and reports it on /health.
     */
    @Test
    void alternatingAccessAtCapacityOneIsCountedAndLoggedAsThrash(@TempDir Path tmp)
            throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger(AnalyzerRegistry.class.getName());
        logger.addHandler(capture);

        AnalyzerRegistry registry = new AnalyzerRegistry(1);
        try {
            registry.get(APK);
            registry.get(other.toString());
            registry.get(APK);
            registry.get(other.toString());

            Map<String, Object> stats = registry.stats();
            assertThat(stats.get("capacity")).isEqualTo(1);
            assertThat((Long) stats.get("loads")).as("every access was a miss").isEqualTo(4L);
            assertThat((Long) stats.get("evictions")).isEqualTo(3L);
            assertThat((Long) stats.get("thrashEvictions"))
                    .as("each eviction dropped an analyzer used seconds ago").isEqualTo(3L);
            assertThat((Long) stats.get("reloads"))
                    .as("two APKs were re-parsed after being evicted").isEqualTo(2L);

            @SuppressWarnings("unchecked")
            Map<String, Object> last = (Map<String, Object>) stats.get("lastThrash");
            assertThat(last).isNotNull();
            assertThat(last.get("evicted")).isEqualTo(APK);
            assertThat(last.get("evictedBy")).isEqualTo(other.toString());
            assertThat((Long) last.get("idleMillis")).isLessThan(60_000L);

            assertThat(logged).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).contains("thrash").contains(APK);
            });
        } finally {
            logger.removeHandler(capture);
            registry.closeAll();
        }
    }

    /** The same alternation, one slot wider: no eviction, so nothing to report. */
    @Test
    @SuppressWarnings("unchecked")
    void theSameAlternationIsThrashFreeAtCapacityTwo(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        AnalyzerRegistry registry = new AnalyzerRegistry(2);
        try {
            JadxAnalyzerCore a = registry.get(APK);
            JadxAnalyzerCore b = registry.get(other.toString());
            assertThat(registry.get(APK)).isSameAs(a);
            assertThat(registry.get(other.toString())).isSameAs(b);

            Map<String, Object> stats = registry.stats();
            assertThat((Long) stats.get("loads")).isEqualTo(2L);
            assertThat((Long) stats.get("evictions")).isEqualTo(0L);
            assertThat((Long) stats.get("thrashEvictions")).isEqualTo(0L);
            assertThat((Long) stats.get("reloads")).isEqualTo(0L);
            assertThat(stats.get("lastThrash")).isNull();
            assertThat((List<String>) stats.get("loaded"))
                    .containsExactlyInAnyOrder(APK, other.toString());
        } finally {
            registry.closeAll();
        }
    }

    /**
     * An eviction that happens long after the analyzer was last touched is the
     * LRU doing its job, not thrash, and must not raise the alarm.
     */
    @Test
    void anEvictionOfAnIdleAnalyzerIsNotThrash(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        long[] now = {1_000_000L};
        AnalyzerRegistry registry = new AnalyzerRegistry(1, () -> now[0]);
        try {
            registry.get(APK);
            now[0] += AnalyzerRegistry.THRASH_WINDOW_MS + 1_000;
            registry.get(other.toString());

            Map<String, Object> stats = registry.stats();
            assertThat((Long) stats.get("evictions")).isEqualTo(1L);
            assertThat((Long) stats.get("thrashEvictions")).isEqualTo(0L);
            assertThat(stats.get("lastThrash")).isNull();
        } finally {
            registry.closeAll();
        }
    }

    /** Evicting an analyzer must actually give the memory back. */
    @Test
    void anEvictedAnalyzerReleasesItsDecompiler(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("Other.apk");
        Files.copy(Path.of(APK), other, StandardCopyOption.REPLACE_EXISTING);

        AnalyzerRegistry registry = new AnalyzerRegistry(1);
        try {
            JadxAnalyzerCore a = registry.get(APK);
            registry.get(other.toString());

            // The API layer keeps a reference to the analyzer it last loaded, so
            // "closed" has to mean "dropped its decompiler", not merely "removed
            // from the map" -- otherwise an evicted APK's 2.9 GB stays reachable.
            assertThat(a.isLoaded())
                    .as("an evicted analyzer must not still claim to hold an APK").isFalse();
        } finally {
            registry.closeAll();
        }
    }

    private static long gb(long n) {
        return n * 1024L * 1024L * 1024L;
    }
}

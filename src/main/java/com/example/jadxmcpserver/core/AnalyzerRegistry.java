package com.example.jadxmcpserver.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Keeps loaded APKs warm across sessions.
 *
 * Before this existed the analyzer was a per-session subprocess, so a 282 MB APK
 * was re-parsed on every run — minutes of work and tens of GB re-committed each
 * time. Here the JVM is a long-lived daemon and the same APK is parsed once.
 *
 * Reads are lock-free on purpose. Loading an APK holds the monitor for minutes,
 * and blocking every other in-flight request behind that would reintroduce the
 * stalls this whole rewrite exists to remove — so a hit never touches the lock.
 *
 * <h2>Capacity is a memory decision, so it is measured</h2>
 *
 * This used to default to 1, on the claim that "a large APK costs roughly 26 GB
 * resident". Measured on the worst APK on this machine (a large production APK,
 * 282 MB, 141,461 classes) under the daemon's own {@code -Xmx24g}, that is wrong
 * by most of an order of magnitude:
 *
 * <pre>
 *   baseline                     retained     1 MB   rss     59 MB
 *   after the large APK          retained  2940 MB   rss   7820 MB   (13.9 s)
 *   + 300 classes decompiled     retained  3554 MB   rss   8977 MB
 *   after a 345 MB APK too       retained  5978 MB   rss  12472 MB   (11.8 s)
 * </pre>
 *
 * So a warm APK costs ~3 GB of live heap, plus ~2 MB per class an analysis
 * decompiles, and two of the largest APKs here sit in 6 GB of live heap and
 * 12.5 GB RSS. The 26 GB figure was the heap ceiling, not the working set.
 *
 * A slot is therefore budgeted at {@value #PER_APK_BUDGET_MB} MB — a load plus
 * roughly 1,500 decompiled classes — and {@value #RESERVE_MB} MB is left unspent
 * for request handling, JSON encoding and GC headroom. The default capacity
 * falls out of {@link Runtime#maxMemory()} rather than a constant, so a daemon
 * started with a small heap still gets one slot and no more. Override with
 * -Djadx.maxLoadedApks=N.
 *
 * <h2>Thrash is reported, not silently paid</h2>
 *
 * Two analyses sharing one daemon at capacity 1 evict each other, and every
 * eviction costs a full reload: 11-12 s per access, measured alternating
 * the two large APKs above. Nothing used to say so. Now an eviction of an analyzer
 * used within {@value #THRASH_WINDOW_MS} ms logs at WARNING and lands in
 * {@link #stats()}, which /health serves.
 *
 * Refusing the second load instead was considered and rejected: the common case
 * for a second APK is an analysis that has *finished* with the first, and failing
 * that call turns a slow analysis into a broken one. Evicting and reporting keeps
 * the daemon usable and makes the cost visible.
 */
public class AnalyzerRegistry {

    private static final Logger logger = Logger.getLogger(AnalyzerRegistry.class.getName());

    /** Heap to budget per warm APK: measured 2.9 GB at load, 3.6 GB after 300 decompiles. */
    static final long PER_APK_BUDGET_MB = 6144;

    /** Heap left unspent whatever the capacity: request handling, JSON, GC headroom. */
    static final long RESERVE_MB = 4096;

    /**
     * Slots the default will never exceed. Two covers the case that exists —
     * one analysis per APK, and a resumed analysis finding its APK still warm. A third
     * has no caller, and each one is 6 GB of budget; -Djadx.maxLoadedApks
     * raises it for anyone who does have a reason.
     */
    static final int MAX_DEFAULT_CAPACITY = 2;

    /** An eviction of an analyzer used more recently than this is thrash, not LRU. */
    static final long THRASH_WINDOW_MS = 60_000;

    private static final class Entry {
        final JadxAnalyzerCore core;
        volatile long lastUsed;
        volatile long lastUsedMillis;

        Entry(JadxAnalyzerCore core, long lastUsed, long lastUsedMillis) {
            this.core = core;
            this.lastUsed = lastUsed;
            this.lastUsedMillis = lastUsedMillis;
        }
    }

    private final int capacity;
    private final LongSupplier clock;
    private final Map<String, Entry> loaded = new ConcurrentHashMap<>();
    private final AtomicLong tick = new AtomicLong();
    private volatile String currentPath;

    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong thrashEvictions = new AtomicLong();
    private final AtomicLong reloads = new AtomicLong();
    private final AtomicLong apkSwitches = new AtomicLong();
    /** Paths this registry has evicted at least once; a re-load of one is a reload. */
    private final Map<String, Boolean> evicted = new ConcurrentHashMap<>();
    private volatile Map<String, Object> lastThrash;

    public AnalyzerRegistry() {
        this(Integer.getInteger("jadx.maxLoadedApks",
                defaultCapacity(Runtime.getRuntime().maxMemory())));
    }

    public AnalyzerRegistry(int capacity) {
        this(capacity, System::currentTimeMillis);
    }

    /** Package-private: the clock is injected so thrash-window behaviour is testable. */
    AnalyzerRegistry(int capacity, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    /**
     * How many APKs this heap can hold warm at once.
     *
     * Public for the measurement it encodes: see the class comment for the
     * numbers. Always at least 1 — a daemon that can hold nothing is useless —
     * and never more than {@link #MAX_DEFAULT_CAPACITY} by default.
     */
    public static int defaultCapacity(long maxMemoryBytes) {
        long usableMb = (maxMemoryBytes / (1024L * 1024L)) - RESERVE_MB;
        long slots = usableMb / PER_APK_BUDGET_MB;
        return (int) Math.max(1, Math.min(MAX_DEFAULT_CAPACITY, slots));
    }

    /**
     * The analyzer for this APK, loading it if necessary.
     * A hit is lock-free; only a miss serialises.
     */
    public JadxAnalyzerCore get(String apkPath) {
        Entry hit = loaded.get(apkPath);
        if (hit != null) {
            return touch(apkPath, hit);
        }
        synchronized (this) {
            hit = loaded.get(apkPath);
            if (hit != null) {
                return touch(apkPath, hit);
            }
            if (evicted.remove(apkPath) != null) {
                reloads.incrementAndGet();
            }
            logger.info("Loading APK (not yet resident): " + apkPath);
            JadxAnalyzerCore core = new JadxAnalyzerCore(apkPath);
            if (!core.loadApk()) {
                throw new RuntimeException("Failed to load APK: " + apkPath);
            }
            loads.incrementAndGet();
            noteSwitch(apkPath);
            loaded.put(apkPath, new Entry(core, tick.incrementAndGet(), clock.getAsLong()));
            currentPath = apkPath;
            evictDownToCapacity(apkPath);
            return core;
        }
    }

    private JadxAnalyzerCore touch(String apkPath, Entry e) {
        e.lastUsed = tick.incrementAndGet();
        e.lastUsedMillis = clock.getAsLong();
        noteSwitch(apkPath);
        currentPath = apkPath;
        return e.core;
    }

    /**
     * Warn when the daemon's notion of "the APK" moves.
     *
     * Every tool call is answered against the most recently selected APK — there
     * is no per-session APK on the wire — so two analyses sharing a daemon do not
     * merely evict each other, they answer each other's questions. That is worse
     * than slow and it is completely silent, so it is counted here even when
     * capacity is wide enough that nothing is evicted.
     */
    private void noteSwitch(String apkPath) {
        String previous = currentPath;
        if (previous == null || previous.equals(apkPath)) {
            return;
        }
        apkSwitches.incrementAndGet();
        logger.warning("Current APK switched: " + previous + " -> " + apkPath
                + ". This daemon answers every request against the most recently"
                + " selected APK, so a session still working on the previous one"
                + " will now get answers about this one. Run one daemon per"
                + " concurrent analysis (JADX_URL / server.port).");
    }

    /** Most recently used APK path, or null when nothing is loaded. */
    public String loadedApkPath() {
        return currentPath;
    }

    /**
     * The analyzer for this APK only if it is already resident.
     *
     * Deliberately not {@link #get}: this never loads and never counts as a use,
     * so /health can read an analyzer's counters without a poll turning into a
     * 14 s reload of an APK the registry had evicted, and without moving
     * anything up the LRU order.
     */
    public JadxAnalyzerCore peek(String apkPath) {
        Entry e = loaded.get(apkPath);
        return e == null ? null : e.core;
    }

    public int size() {
        return loaded.size();
    }

    public boolean isLoaded() {
        return currentPath != null && loaded.containsKey(currentPath);
    }

    /**
     * What the registry has been doing, for /health.
     *
     * Counters only — no APK is touched to build this, so it is safe to poll.
     */
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("capacity", capacity);
        s.put("loaded", new ArrayList<>(loaded.keySet()));
        s.put("loads", loads.get());
        s.put("evictions", evictions.get());
        s.put("thrashEvictions", thrashEvictions.get());
        s.put("reloads", reloads.get());
        s.put("apkSwitches", apkSwitches.get());
        s.put("lastThrash", lastThrash);
        return s;
    }

    /** Caller must hold the monitor. */
    private void evictDownToCapacity(String loadedBy) {
        while (loaded.size() > capacity) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : loaded.entrySet()) {
                if (e.getValue().lastUsed < oldest) {
                    oldest = e.getValue().lastUsed;
                    victim = e.getKey();
                }
            }
            if (victim == null) {
                return;
            }
            Entry evictedEntry = loaded.remove(victim);
            evictions.incrementAndGet();
            evicted.put(victim, Boolean.TRUE);
            long idle = clock.getAsLong() - evictedEntry.lastUsedMillis;
            if (idle < THRASH_WINDOW_MS) {
                recordThrash(victim, loadedBy, idle);
            } else {
                logger.info("Evicting APK to stay within capacity " + capacity + ": " + victim);
            }
            closeQuietly(evictedEntry);
            if (victim.equals(currentPath)) {
                currentPath = null;
            }
        }
    }

    private void recordThrash(String victim, String loadedBy, long idleMillis) {
        long count = thrashEvictions.incrementAndGet();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("evicted", victim);
        event.put("evictedBy", loadedBy);
        event.put("idleMillis", idleMillis);
        event.put("at", clock.getAsLong());
        lastThrash = event;
        logger.warning("APK cache thrash (" + count + " so far): evicted " + victim
                + " after only " + idleMillis + " ms idle to make room for " + loadedBy
                + ". At capacity " + capacity + " these two APKs will keep evicting each"
                + " other and every switch costs a full reload (11-15 s on a large APK)."
                + " Raise it with -Djadx.maxLoadedApks=" + (capacity + 1)
                + " (needs about " + PER_APK_BUDGET_MB + " MB more heap) or give each analysis"
                + " its own daemon.");
    }

    public synchronized void closeAll() {
        for (String path : loaded.keySet()) {
            closeQuietly(loaded.remove(path));
        }
        currentPath = null;
    }

    /** Drops one APK if it is resident. Backs the /close endpoint. */
    public synchronized void close(String apkPath) {
        Entry e = loaded.remove(apkPath);
        closeQuietly(e);
        if (apkPath.equals(currentPath)) {
            currentPath = null;
        }
    }

    private static void closeQuietly(Entry e) {
        if (e == null) {
            return;
        }
        try {
            e.core.close();
        } catch (RuntimeException ex) {
            logger.warning("Error closing analyzer: " + ex.getMessage());
        }
    }
}

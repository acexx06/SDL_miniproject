package com.hms.components.kv;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory key-value storage for one node: a hash index with per-key expiry (TTL) and a
 * least-recently-used bound on the number of entries.
 *
 * The access-ordered {@link LinkedHashMap} keeps entries from least to most recently used, so
 * evicting when full is O(1). One lock guards the map; every operation under it is a handful of
 * pointer updates, so the critical section is far shorter than the network round trip around it.
 */
public final class KvEngine {

    private record Entry(String value, long expiresAtMs) {
    }

    public record Stats(long keys, long hits, long misses, long sets, long deletes, long evictions, long expirations) {
        public double hitRatio() {
            long lookups = hits + misses;
            return lookups == 0 ? 0 : (double) hits / lookups;
        }
    }

    private final int maxEntries;
    private final LongSupplier clockMs;
    private final LinkedHashMap<String, Entry> map;
    private long hits;
    private long misses;
    private long sets;
    private long deletes;
    private long evictions;
    private long expirations;

    public KvEngine(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    public KvEngine(int maxEntries, LongSupplier clockMs) {
        this.maxEntries = maxEntries;
        this.clockMs = clockMs;
        this.map = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                if (size() > KvEngine.this.maxEntries) {
                    evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    private static long expiry(long now, long ttlMs) {
        return ttlMs > 0 ? now + ttlMs : 0;
    }

    /** Returns the live entry for {@code key}, removing it first if it has expired. */
    private Entry live(String key, long now) {
        Entry entry = map.get(key);
        if (entry != null && entry.expiresAtMs != 0 && entry.expiresAtMs <= now) {
            map.remove(key);
            expirations++;
            return null;
        }
        return entry;
    }

    public synchronized String get(String key) {
        Entry entry = live(key, clockMs.getAsLong());
        if (entry == null) {
            misses++;
            return null;
        }
        hits++;
        return entry.value;
    }

    /** @param ttlMs time to live in milliseconds; 0 or less means the entry never expires */
    public synchronized void set(String key, String value, long ttlMs) {
        map.put(key, new Entry(value, expiry(clockMs.getAsLong(), ttlMs)));
        sets++;
    }

    public synchronized boolean setIfAbsent(String key, String value, long ttlMs) {
        long now = clockMs.getAsLong();
        if (live(key, now) != null) {
            return false;
        }
        map.put(key, new Entry(value, expiry(now, ttlMs)));
        sets++;
        return true;
    }

    public synchronized boolean delete(String key) {
        deletes++;
        return map.remove(key) != null;
    }

    /** Atomically adds {@code delta} to a counter, creating it at 0 (with the given TTL) if absent. */
    public synchronized long increment(String key, long delta, long ttlMs) {
        long now = clockMs.getAsLong();
        Entry entry = live(key, now);
        long value = (entry == null ? 0 : Long.parseLong(entry.value)) + delta;
        map.put(key, new Entry(Long.toString(value), entry == null ? expiry(now, ttlMs) : entry.expiresAtMs));
        sets++;
        return value;
    }

    public synchronized int purgeExpired() {
        long now = clockMs.getAsLong();
        int removed = 0;
        for (Iterator<Entry> it = map.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.expiresAtMs != 0 && entry.expiresAtMs <= now) {
                it.remove();
                removed++;
            }
        }
        expirations += removed;
        return removed;
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized Stats stats() {
        return new Stats(map.size(), hits, misses, sets, deletes, evictions, expirations);
    }
}

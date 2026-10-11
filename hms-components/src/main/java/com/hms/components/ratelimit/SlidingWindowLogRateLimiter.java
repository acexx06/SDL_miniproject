package com.hms.components.ratelimit;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Comparison point: remembers the timestamp of every accepted request inside the window.
 * Exact, with no boundary burst, but memory grows with {@code limit} for every active client.
 */
public final class SlidingWindowLogRateLimiter implements RateLimiter {

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> logs = new ConcurrentHashMap<>();

    public SlidingWindowLogRateLimiter(int limit, double windowSeconds, LongSupplier nanoClock) {
        this.limit = limit;
        this.windowNanos = (long) (windowSeconds * 1e9);
        this.nanoClock = nanoClock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        ArrayDeque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            long cutoff = now - windowNanos;
            while (!log.isEmpty() && log.peekFirst() <= cutoff) {
                log.pollFirst();
            }
            if (log.size() < limit) {
                log.addLast(now);
                return new Decision(true, limit - log.size(), 0);
            }
            return new Decision(false, 0, (log.peekFirst() + windowNanos - now) / 1e9);
        }
    }

    @Override
    public int trackedKeys() {
        return logs.size();
    }
}

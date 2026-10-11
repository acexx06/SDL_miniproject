package com.hms.components.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Baseline: {@code limit} requests per fixed window, counter reset at each boundary.
 * Cheap, but a client can send {@code limit} requests just before a boundary and {@code limit}
 * more just after it, which is twice the intended rate in a very short span.
 */
public final class FixedWindowRateLimiter implements RateLimiter {

    private static final class Counter {
        long window;
        int count;
    }

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, double windowSeconds, LongSupplier nanoClock) {
        this.limit = limit;
        this.windowNanos = (long) (windowSeconds * 1e9);
        this.nanoClock = nanoClock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        long window = Math.floorDiv(now, windowNanos);
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (counter) {
            if (counter.window != window) {
                counter.window = window;
                counter.count = 0;
            }
            if (counter.count < limit) {
                counter.count++;
                return new Decision(true, limit - counter.count, 0);
            }
            return new Decision(false, 0, ((window + 1) * windowNanos - now) / 1e9);
        }
    }

    @Override
    public int trackedKeys() {
        return counters.size();
    }
}

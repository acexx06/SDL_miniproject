package com.hms.components.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Token bucket: each key owns a bucket of {@code burst} tokens refilled at {@code ratePerSecond}.
 * A request spends one token. Refill is computed lazily from elapsed time on each call, so an
 * idle client costs nothing and no background timer is needed.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final double ratePerSecond;
    private final double burst;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongAdder allowed = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public TokenBucketRateLimiter(double ratePerSecond, double burst) {
        this(ratePerSecond, burst, System::nanoTime);
    }

    public TokenBucketRateLimiter(double ratePerSecond, double burst, LongSupplier nanoClock) {
        if (ratePerSecond <= 0 || burst < 1) {
            throw new IllegalArgumentException("rate must be positive and burst at least 1");
        }
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.nanoClock = nanoClock;
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        synchronized (bucket) {
            long elapsed = now - bucket.lastNanos;
            if (elapsed > 0) {
                bucket.tokens = Math.min(burst, bucket.tokens + elapsed / 1e9 * ratePerSecond);
                bucket.lastNanos = now;
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                allowed.increment();
                return new Decision(true, (long) bucket.tokens, 0);
            }
            rejected.increment();
            return new Decision(false, 0, (1.0 - bucket.tokens) / ratePerSecond);
        }
    }

    /** Forgets clients that have been idle long enough for their bucket to be full again. */
    public int sweep() {
        long horizon = nanoClock.getAsLong() - (long) (burst / ratePerSecond * 1e9);
        int before = buckets.size();
        buckets.values().removeIf(bucket -> {
            synchronized (bucket) {
                return bucket.lastNanos < horizon;
            }
        });
        return before - buckets.size();
    }

    @Override
    public int trackedKeys() {
        return buckets.size();
    }

    public double ratePerSecond() {
        return ratePerSecond;
    }

    public double burst() {
        return burst;
    }

    public long allowedCount() {
        return allowed.sum();
    }

    public long rejectedCount() {
        return rejected.sum();
    }
}

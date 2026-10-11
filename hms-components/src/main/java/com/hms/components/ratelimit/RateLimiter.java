package com.hms.components.ratelimit;

/** Decides, per client key, whether one more request may proceed right now. */
public interface RateLimiter {

    Decision tryAcquire(String key);

    /** Number of client keys currently held in memory. */
    int trackedKeys();

    /**
     * @param allowed           whether the request may proceed
     * @param remaining         requests the client could still make immediately
     * @param retryAfterSeconds how long to wait before a retry can succeed; 0 when allowed
     */
    record Decision(boolean allowed, long remaining, double retryAfterSeconds) {
    }
}

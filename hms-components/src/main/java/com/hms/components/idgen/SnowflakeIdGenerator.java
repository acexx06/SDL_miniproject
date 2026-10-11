package com.hms.components.idgen;

import java.util.function.LongSupplier;

/**
 * Snowflake-style unique ID generator.
 *
 * <pre>
 * 64-bit layout, most significant bit first:
 *   1 bit    unused (keeps the value positive)
 *   41 bits  milliseconds since EPOCH_MS      (~69 years of range)
 *   10 bits  worker id                        (1024 independent generators)
 *   12 bits  per-millisecond sequence         (4096 IDs per ms per worker)
 * </pre>
 *
 * IDs are unique without any coordination between application servers and sort by creation time,
 * so "newest first" queries can sort on the primary key instead of needing a second index.
 */
public final class SnowflakeIdGenerator {

    public static final long EPOCH_MS = 1_735_689_600_000L; // 2025-01-01T00:00:00Z
    public static final int WORKER_BITS = 10;
    public static final int SEQUENCE_BITS = 12;
    public static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    /** 62^11 exceeds 2^63, so every ID fits in 11 base-62 characters. */
    public static final int TEXT_WIDTH = 11;

    private static final int TIME_SHIFT = WORKER_BITS + SEQUENCE_BITS;

    private final long workerId;
    private final LongSupplier clockMs;
    private long lastMs = -1;
    private long sequence;
    private long generated;

    public SnowflakeIdGenerator(long workerId) {
        this(workerId, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long workerId, LongSupplier clockMs) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be in [0, " + MAX_WORKER_ID + "]");
        }
        this.workerId = workerId;
        this.clockMs = clockMs;
    }

    public synchronized long nextId() {
        long now = clockMs.getAsLong();
        // If the wall clock stepped backwards (NTP), keep issuing from the last timestamp used
        // rather than risk repeating an ID.
        if (now < lastMs) {
            now = lastMs;
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) { // all 4096 IDs of this millisecond are used
                while (now <= lastMs) {
                    Thread.onSpinWait();
                    now = clockMs.getAsLong();
                }
            }
        } else {
            sequence = 0;
        }
        lastMs = now;
        generated++;
        return ((now - EPOCH_MS) << TIME_SHIFT) | (workerId << SEQUENCE_BITS) | sequence;
    }

    /** Compact, URL-safe, time-sortable ID such as {@code APT0B3xK9fQw2Za}. */
    public String nextId(String prefix) {
        return prefix + Base62.encode(nextId(), TEXT_WIDTH);
    }

    public long workerId() {
        return workerId;
    }

    public synchronized long generatedCount() {
        return generated;
    }

    public static Parts parse(long id) {
        return new Parts((id >> TIME_SHIFT) + EPOCH_MS, (id >> SEQUENCE_BITS) & MAX_WORKER_ID, id & MAX_SEQUENCE);
    }

    public record Parts(long timestampMs, long workerId, long sequence) {
    }
}

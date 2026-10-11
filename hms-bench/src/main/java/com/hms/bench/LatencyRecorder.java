package com.hms.bench;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collects response times from many threads and summarises them as average and percentiles. */
public final class LatencyRecorder {

    private long[] nanos = new long[4096];
    private int size;

    public synchronized void add(long elapsedNanos) {
        if (size == nanos.length) {
            nanos = Arrays.copyOf(nanos, size * 2);
        }
        nanos[size++] = elapsedNanos;
    }

    public synchronized int count() {
        return size;
    }

    private synchronized long[] sorted() {
        long[] copy = Arrays.copyOf(nanos, size);
        Arrays.sort(copy);
        return copy;
    }

    /** Nearest-rank percentile, in the unit given by {@code nanosPerUnit} (1e6 for ms, 1e3 for µs). */
    private static double percentile(long[] sorted, double p, double nanosPerUnit) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / nanosPerUnit;
    }

    public Map<String, Object> summary(double nanosPerUnit) {
        long[] sorted = sorted();
        double total = 0;
        for (long v : sorted) {
            total += v;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", sorted.length);
        result.put("avg", round(sorted.length == 0 ? 0 : total / sorted.length / nanosPerUnit));
        result.put("p50", round(percentile(sorted, 50, nanosPerUnit)));
        result.put("p95", round(percentile(sorted, 95, nanosPerUnit)));
        result.put("p99", round(percentile(sorted, 99, nanosPerUnit)));
        result.put("max", round(sorted.length == 0 ? 0 : sorted[sorted.length - 1] / nanosPerUnit));
        return result;
    }

    public Map<String, Object> summaryMs() {
        return summary(1e6);
    }

    public Map<String, Object> summaryMicros() {
        return summary(1e3);
    }

    public static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

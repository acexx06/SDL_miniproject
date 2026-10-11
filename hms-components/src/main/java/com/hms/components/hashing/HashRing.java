package com.hms.components.hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Consistent hashing ring with virtual nodes.
 *
 * Every server is placed on the ring {@code virtualNodes} times. A key belongs to the first
 * virtual node clockwise from its hash; its replicas are the next distinct servers after that.
 * Adding or removing one of N servers moves only about 1/N of the keys, where
 * {@code hash(key) % N} would move nearly all of them.
 *
 * Lookups read an immutable snapshot, so they never block while membership changes.
 */
public final class HashRing implements NodeRouter {

    private record Snapshot(long[] hashes, String[] owners, List<String> nodes) {
    }

    private final int virtualNodes;
    private final TreeSet<String> members = new TreeSet<>();
    private volatile Snapshot snapshot = new Snapshot(new long[0], new String[0], List.of());

    public HashRing(int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be at least 1");
        }
        this.virtualNodes = virtualNodes;
    }

    public HashRing(int virtualNodes, Iterable<String> nodes) {
        this(virtualNodes);
        synchronized (this) {
            nodes.forEach(members::add);
            rebuild();
        }
    }

    @Override
    public synchronized void addNode(String node) {
        if (members.add(node)) {
            rebuild();
        }
    }

    @Override
    public synchronized void removeNode(String node) {
        if (members.remove(node)) {
            rebuild();
        }
    }

    private void rebuild() {
        TreeMap<Long, String> ring = new TreeMap<>();
        for (String node : members) {
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(NodeRouter.hash64(node + "#" + i), node);
            }
        }
        long[] hashes = new long[ring.size()];
        String[] owners = new String[ring.size()];
        int i = 0;
        for (Map.Entry<Long, String> e : ring.entrySet()) {
            hashes[i] = e.getKey();
            owners[i] = e.getValue();
            i++;
        }
        snapshot = new Snapshot(hashes, owners, List.copyOf(members));
    }

    @Override
    public List<String> nodes() {
        return snapshot.nodes;
    }

    public int virtualNodes() {
        return virtualNodes;
    }

    private static int firstClockwise(long[] hashes, long hash) {
        int idx = Arrays.binarySearch(hashes, hash);
        idx = idx >= 0 ? idx : -idx - 1;
        return idx == hashes.length ? 0 : idx;
    }

    @Override
    public String route(String key) {
        Snapshot s = snapshot;
        if (s.hashes.length == 0) {
            return null;
        }
        return s.owners[firstClockwise(s.hashes, NodeRouter.hash64(key))];
    }

    @Override
    public List<String> routeN(String key, int n) {
        Snapshot s = snapshot;
        int wanted = Math.min(n, s.nodes.size());
        List<String> result = new ArrayList<>(wanted);
        if (wanted == 0) {
            return result;
        }
        int start = firstClockwise(s.hashes, NodeRouter.hash64(key));
        for (int step = 0; step < s.hashes.length && result.size() < wanted; step++) {
            String owner = s.owners[(start + step) % s.hashes.length];
            if (!result.contains(owner)) {
                result.add(owner);
            }
        }
        return result;
    }

    /** Fraction of the hash space each node is primary for. */
    public Map<String, Double> ownership() {
        Snapshot s = snapshot;
        Map<String, Double> share = new LinkedHashMap<>();
        s.nodes.forEach(node -> share.put(node, 0.0));
        int n = s.hashes.length;
        for (int i = 0; i < n; i++) {
            long arc = s.hashes[i] - s.hashes[(i + n - 1) % n]; // wraps correctly modulo 2^64
            double fraction = n == 1 ? 1.0 : (arc >>> 11) / (double) (1L << 53);
            share.merge(s.owners[i], fraction, Double::sum);
        }
        return share;
    }
}

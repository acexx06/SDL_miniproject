package com.hms.components.kv;

import com.hms.components.hashing.NodeRouter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Client-side view of a cluster of key-value nodes.
 *
 * A {@link NodeRouter} (the consistent-hash ring) picks which node holds each key. Writes go to
 * {@code replicas} nodes; reads try them in order. A node that fails a request is taken off the
 * ring immediately, so its keys fall through to the next node clockwise, and a background check
 * puts it back once it answers again. A returning node is emptied first so it can never serve
 * data that was changed while it was away.
 *
 * The cluster is a cache tier: when no node can answer, {@link #get} returns {@code null} and the
 * caller reads from the database instead. It never throws for an unreachable node.
 */
public final class KvCluster implements AutoCloseable {

    public record NodeStatus(String id, String host, int port, boolean up, long requests) {
    }

    private static final class Node {
        final String id;
        final KvNodeClient client;
        final LongAdder requests = new LongAdder();
        volatile boolean up = true;

        Node(String id, KvNodeClient client) {
            this.id = id;
            this.client = client;
        }
    }

    private final NodeRouter router;
    private final int replicas;
    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final LongAdder failovers = new LongAdder();
    private final ScheduledExecutorService health;

    public KvCluster(NodeRouter router, int replicas) {
        this(router, replicas, Duration.ofMillis(500));
    }

    public KvCluster(NodeRouter router, int replicas, Duration healthInterval) {
        this.router = router;
        this.replicas = Math.max(1, replicas);
        this.health = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kv-cluster-health");
            thread.setDaemon(true);
            return thread;
        });
        long ms = healthInterval.toMillis();
        health.scheduleWithFixedDelay(this::checkHealth, ms, ms, TimeUnit.MILLISECONDS);
    }

    public void addNode(String id, String host, int port) {
        Node previous = nodes.put(id, new Node(id, new KvNodeClient(host, port)));
        if (previous != null) {
            previous.client.close();
        }
        router.addNode(id);
    }

    public void removeNode(String id) {
        router.removeNode(id);
        Node node = nodes.remove(id);
        if (node != null) {
            node.client.close();
        }
    }

    private void markDown(Node node) {
        synchronized (node) {
            if (node.up) {
                node.up = false;
                router.removeNode(node.id);
                node.client.closeIdle();
                failovers.increment();
            }
        }
    }

    private void checkHealth() {
        for (Node node : nodes.values()) {
            try {
                node.client.ping();
                synchronized (node) {
                    if (!node.up) {
                        node.client.flush();
                        node.up = true;
                        router.addNode(node.id);
                    }
                }
            } catch (RuntimeException e) {
                markDown(node);
            }
        }
    }

    /** Nodes to try for {@code key}, re-read from the router so a just-failed node is skipped. */
    private List<Node> targets(String key) {
        List<Node> result = new ArrayList<>(replicas);
        for (String id : router.routeN(key, replicas)) {
            Node node = nodes.get(id);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    public String get(String key) {
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean failed = false;
            for (Node node : targets(key)) {
                try {
                    node.requests.increment();
                    String value = node.client.get(key);
                    if (value != null) {
                        return value;
                    }
                } catch (KvNodeClient.UnavailableException e) {
                    markDown(node);
                    failed = true;
                }
            }
            if (!failed) {
                return null;
            }
        }
        return null;
    }

    public void set(String key, String value, Duration ttl) {
        long ttlMs = ttl == null ? 0 : ttl.toMillis();
        for (Node node : targets(key)) {
            try {
                node.requests.increment();
                node.client.set(key, value, ttlMs);
            } catch (KvNodeClient.UnavailableException e) {
                markDown(node);
            }
        }
    }

    public void delete(String key) {
        for (Node node : targets(key)) {
            try {
                node.requests.increment();
                node.client.delete(key);
            } catch (KvNodeClient.UnavailableException e) {
                markDown(node);
            }
        }
    }

    public List<NodeStatus> status() {
        List<NodeStatus> result = new ArrayList<>();
        nodes.values().stream()
                .sorted((a, b) -> a.id.compareTo(b.id))
                .forEach(n -> result.add(new NodeStatus(n.id, n.client.host(), n.client.port(), n.up, n.requests.sum())));
        return result;
    }

    /** Asks one node for its storage statistics; {@code null} if it is unreachable. */
    public KvEngine.Stats nodeStats(String id) {
        Node node = nodes.get(id);
        if (node == null || !node.up) {
            return null;
        }
        try {
            return node.client.stats();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public NodeRouter router() {
        return router;
    }

    public int replicas() {
        return replicas;
    }

    public long failovers() {
        return failovers.sum();
    }

    @Override
    public void close() {
        health.shutdownNow();
        nodes.values().forEach(node -> node.client.close());
    }
}

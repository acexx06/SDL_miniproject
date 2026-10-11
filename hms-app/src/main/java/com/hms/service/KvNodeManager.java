package com.hms.service;

import com.hms.components.hashing.HashRing;
import com.hms.components.kv.KvCluster;
import com.hms.components.kv.KvEngine;
import com.hms.components.kv.KvNodeServer;
import com.hms.config.HmsProperties;
import com.hms.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the key-value cache cluster: the consistent-hash ring, the cluster client, and (in embedded
 * mode) the node servers themselves, each listening on its own port.
 *
 * Because the nodes are reached over TCP exactly as remote servers would be, stopping one here
 * looks to the rest of the application like a cache server crashing. That is what the failure
 * experiments use. The same servers can also run as separate processes ({@code KvNodeServer.main}).
 */
@Service
public class KvNodeManager {

    private static final Logger log = LoggerFactory.getLogger(KvNodeManager.class);

    private final HmsProperties.Kv config;
    private final HashRing ring;
    private final KvCluster cluster;
    private final Map<String, KvNodeServer> servers = new LinkedHashMap<>();
    private final Map<String, Integer> ports = new LinkedHashMap<>();

    public KvNodeManager(HmsProperties properties) throws IOException {
        this.config = properties.kv();
        this.ring = new HashRing(config.virtualNodes());
        this.cluster = new KvCluster(ring, config.replicas());
        for (int i = 1; i <= config.nodes(); i++) {
            String id = "n" + i;
            int port = config.basePort() + i - 1;
            ports.put(id, port);
            if (config.embedded()) {
                startServer(id, port);
            }
            cluster.addNode(id, "127.0.0.1", port);
        }
        log.info("Key-value cluster: {} nodes on ports {}, {} replicas, {} virtual nodes each",
                config.nodes(), ports.values(), config.replicas(), config.virtualNodes());
    }

    private void startServer(String id, int port) throws IOException {
        KvNodeServer server = new KvNodeServer(id, port, config.maxEntriesPerNode());
        server.start();
        servers.put(id, server);
    }

    public KvCluster cluster() {
        return cluster;
    }

    private void requireEmbedded() {
        if (!config.embedded()) {
            throw ApiException.badRequest("Nodes are external processes; start and stop them yourself");
        }
    }

    /** Starts one more node and puts it on the ring. About 1/N of the keys now belong to it. */
    public synchronized String addNode() {
        requireEmbedded();
        String id = "n" + (ports.size() + 1);
        int port = config.basePort() + ports.size();
        try {
            startServer(id, port);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not start a node on port " + port + ": " + e.getMessage());
        }
        ports.put(id, port);
        cluster.addNode(id, "127.0.0.1", port);
        log.info("Added key-value node {} on port {}", id, port);
        return id;
    }

    /** Simulates a crash: the node stops answering and everything it held in memory is gone. */
    public synchronized void kill(String id) {
        requireEmbedded();
        KvNodeServer server = servers.remove(id);
        if (server == null) {
            throw ApiException.notFound("Running node " + id);
        }
        server.close();
        log.warn("Killed key-value node {}", id);
    }

    /** Brings a killed node back, empty. The cluster client notices and returns it to the ring. */
    public synchronized void restart(String id) {
        requireEmbedded();
        Integer port = ports.get(id);
        if (port == null) {
            throw ApiException.notFound("Node " + id);
        }
        if (servers.containsKey(id)) {
            throw ApiException.conflict("Node " + id + " is already running");
        }
        try {
            startServer(id, port);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not restart node " + id + ": " + e.getMessage());
        }
        log.info("Restarted key-value node {}", id);
    }

    public Map<String, Object> describe() {
        Map<String, Double> ownership = ring.ownership();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (KvCluster.NodeStatus status : cluster.status()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", status.id());
            row.put("port", status.port());
            row.put("up", status.up());
            row.put("requests", status.requests());
            row.put("ringShare", ownership.getOrDefault(status.id(), 0.0));
            KvEngine.Stats stats = cluster.nodeStats(status.id());
            if (stats != null) {
                row.put("keys", stats.keys());
                row.put("hits", stats.hits());
                row.put("misses", stats.misses());
                row.put("evictions", stats.evictions());
            }
            nodes.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replicas", cluster.replicas());
        result.put("virtualNodes", ring.virtualNodes());
        result.put("failovers", cluster.failovers());
        result.put("nodes", nodes);
        return result;
    }

    @PreDestroy
    public synchronized void shutdown() {
        cluster.close();
        servers.values().forEach(KvNodeServer::close);
        servers.clear();
    }
}

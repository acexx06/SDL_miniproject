package com.hms.service;

import com.hms.config.HmsProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Cache-aside access to the key-value cluster, in front of MongoDB.
 *
 * Read: look in the cache; on a miss load from the database and store the result with a TTL.
 * Write: update the database, then evict the affected keys so the next read reloads them.
 * MongoDB stays the source of truth, so a lost cache node costs only some extra database reads.
 */
@Service
public class CacheService {

    private final KvNodeManager nodes;
    private final JsonMapper mapper;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private volatile boolean enabled;

    public CacheService(KvNodeManager nodes, JsonMapper mapper, HmsProperties properties) {
        this.nodes = nodes;
        this.mapper = mapper;
        this.enabled = properties.cache().enabled();
    }

    /** Returns the cached value for {@code key}, loading and caching it when absent. {@code null} is never cached. */
    public <T> T get(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return load(key, mapper.constructType(type), ttl, loader);
    }

    public <T> List<T> getList(String key, Class<T> elementType, Duration ttl, Supplier<List<T>> loader) {
        return load(key, mapper.getTypeFactory().constructCollectionType(List.class, elementType), ttl, loader);
    }

    private <T> T load(String key, JavaType type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached = nodes.cluster().get(key);
        if (cached != null) {
            hits.increment();
            return mapper.readValue(cached, type);
        }
        misses.increment();
        T value = loader.get();
        if (value != null) {
            nodes.cluster().set(key, mapper.writeValueAsString(value), ttl);
        }
        return value;
    }

    /** Evictions happen even while reads bypass the cache, so nothing stale is waiting when it is switched back on. */
    public void evict(String... keys) {
        for (String key : keys) {
            nodes.cluster().delete(key);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> metrics() {
        long h = hits.sum();
        long m = misses.sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("hits", h);
        result.put("misses", m);
        result.put("hitRatio", h + m == 0 ? 0 : (double) h / (h + m));
        return result;
    }
}

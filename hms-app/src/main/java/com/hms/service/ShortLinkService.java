package com.hms.service;

import com.hms.components.shortener.UrlShortener;
import com.hms.domain.ShortUrl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Short links to pages inside the portal, such as a bill, so they fit in a text message.
 *
 * The {@link UrlShortener} component generates and validates codes; this class gives it somewhere
 * to keep them. Links are stored in MongoDB, whose unique {@code _id} detects a code collision and
 * whose TTL index deletes expired links, and are cached in the key-value cluster because a
 * redirect is a pure lookup that should not need the database.
 */
@Service
public class ShortLinkService implements UrlShortener.Store {

    public static final int CODE_LENGTH = 8;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final UrlShortener shortener = new UrlShortener(this, CODE_LENGTH);

    public ShortLinkService(MongoTemplate mongo, CacheService cache) {
        this.mongo = mongo;
        this.cache = cache;
    }

    public String shorten(String target, Duration ttl) {
        return shortener.shorten(target, ttl);
    }

    public Optional<String> resolve(String code) {
        return shortener.resolve(code);
    }

    @Override
    public boolean saveIfAbsent(String code, String target, Instant expiresAt) {
        try {
            mongo.insert(new ShortUrl(code, target, Instant.now(), expiresAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<String> find(String code) {
        ShortUrl link = cache.get("short:" + code, ShortUrl.class, CACHE_TTL, () -> mongo.findById(code, ShortUrl.class));
        if (link == null || (link.expiresAt() != null && link.expiresAt().isBefore(Instant.now()))) {
            return Optional.empty();
        }
        return Optional.of(link.target());
    }

    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", shortener.createdCount());
        result.put("collisions", shortener.collisionCount());
        result.put("codeLength", CODE_LENGTH);
        return result;
    }
}

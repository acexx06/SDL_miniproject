package com.hms.components.shortener;

import com.hms.components.idgen.Base62;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL shortener: maps a short base-62 code to a longer link inside the hospital portal.
 *
 * Codes are drawn from a cryptographically secure random source rather than derived from a
 * counter, because these links point at bills and prescriptions and must not be guessable by
 * counting up from a known code. A collision is detected by the store's insert-if-absent and
 * simply retried with a new code. Only site-relative targets are accepted, so a short link can
 * never be used to bounce a patient to another website.
 */
public final class UrlShortener {

    /** Where code-to-target mappings are persisted. */
    public interface Store {

        /** Saves the mapping unless the code is already taken; returns whether it was saved. */
        boolean saveIfAbsent(String code, String target, Instant expiresAt);

        /** The target for a code, or empty if unknown or expired. */
        Optional<String> find(String code);
    }

    public static final int MAX_TARGET_LENGTH = 2048;
    private static final int MAX_ATTEMPTS = 8;

    private final Store store;
    private final int codeLength;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong collisions = new AtomicLong();

    public UrlShortener(Store store, int codeLength) {
        if (codeLength < 4 || codeLength > 16) {
            throw new IllegalArgumentException("codeLength must be between 4 and 16");
        }
        this.store = store;
        this.codeLength = codeLength;
    }

    public static boolean isSafeTarget(String target) {
        if (target == null || target.length() < 1 || target.length() > MAX_TARGET_LENGTH) {
            return false;
        }
        if (target.charAt(0) != '/' || target.startsWith("//")) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** @param ttl how long the link stays valid; {@code null} for no expiry */
    public String shorten(String target, Duration ttl) {
        if (!isSafeTarget(target)) {
            throw new IllegalArgumentException("target must be a path on this site, starting with a single '/'");
        }
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = Base62.random(random, codeLength);
            if (store.saveIfAbsent(code, target, expiresAt)) {
                created.incrementAndGet();
                return code;
            }
            collisions.incrementAndGet();
        }
        throw new IllegalStateException("could not allocate a short code; the code space is too full");
    }

    public Optional<String> resolve(String code) {
        if (code == null || code.length() != codeLength || !Base62.isValid(code)) {
            return Optional.empty();
        }
        return store.find(code);
    }

    public int codeLength() {
        return codeLength;
    }

    public long createdCount() {
        return created.get();
    }

    public long collisionCount() {
        return collisions.get();
    }

    /** Store that keeps everything in memory, for tests and benchmarks. */
    public static final class InMemoryStore implements Store {

        private record Link(String target, Instant expiresAt) {
        }

        private final Map<String, Link> links = new ConcurrentHashMap<>();

        @Override
        public boolean saveIfAbsent(String code, String target, Instant expiresAt) {
            return links.putIfAbsent(code, new Link(target, expiresAt)) == null;
        }

        @Override
        public Optional<String> find(String code) {
            Link link = links.get(code);
            if (link == null || (link.expiresAt != null && link.expiresAt.isBefore(Instant.now()))) {
                return Optional.empty();
            }
            return Optional.of(link.target);
        }

        public int size() {
            return links.size();
        }
    }
}

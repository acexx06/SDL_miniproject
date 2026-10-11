package com.hms.components;

import com.hms.components.autocomplete.Autocomplete;
import com.hms.components.hashing.HashRing;
import com.hms.components.hashing.ModuloRouter;
import com.hms.components.hashing.NodeRouter;
import com.hms.components.idgen.Base62;
import com.hms.components.idgen.SnowflakeIdGenerator;
import com.hms.components.ratelimit.FixedWindowRateLimiter;
import com.hms.components.ratelimit.SlidingWindowLogRateLimiter;
import com.hms.components.ratelimit.TokenBucketRateLimiter;
import com.hms.components.shortener.UrlShortener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreComponentsTest {

    // ---- unique ID generator ---------------------------------------------------------------

    @Test
    void idsAreUniqueAcrossThreadsAndIncreasing() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                long previous = 0;
                for (int i = 0; i < 50_000; i++) {
                    long id = generator.nextId();
                    assertTrue(id > previous, "ids seen by one thread must increase");
                    previous = id;
                    ids.add(id);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(400_000, ids.size());
        assertEquals(5, SnowflakeIdGenerator.parse(ids.iterator().next()).workerId());
    }

    @Test
    void idsStayUniqueWhenTheClockGoesBackwards() {
        AtomicLong clock = new AtomicLong(SnowflakeIdGenerator.EPOCH_MS + 10_000);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock::get);
        long first = generator.nextId();
        clock.addAndGet(-5_000);
        long second = generator.nextId();
        assertTrue(second > first);
    }

    @Test
    void differentWorkersNeverCollide() {
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(1, () -> SnowflakeIdGenerator.EPOCH_MS + 1);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(2, () -> SnowflakeIdGenerator.EPOCH_MS + 1);
        assertTrue(a.nextId() != b.nextId());
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }

    @Test
    void base62RoundTripsAndKeepsOrder() {
        for (long value : new long[]{0, 1, 61, 62, 123_456_789L, Long.MAX_VALUE}) {
            assertEquals(value, Base62.decode(Base62.encode(value, 11)));
        }
        assertEquals(11, Base62.encode(Long.MAX_VALUE, 11).length());
        assertTrue(Base62.encode(1000, 11).compareTo(Base62.encode(1001, 11)) < 0);
        assertTrue(Base62.encode(61, 11).compareTo(Base62.encode(62, 11)) < 0);
    }

    // ---- rate limiter ----------------------------------------------------------------------

    @Test
    void tokenBucketAllowsBurstThenRefillsAtTheConfiguredRate() {
        AtomicLong nanos = new AtomicLong();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10, nanos::get);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("a").allowed());
        }
        assertFalse(limiter.tryAcquire("a").allowed());
        assertEquals(0.2, limiter.tryAcquire("a").retryAfterSeconds(), 1e-9);
        assertTrue(limiter.tryAcquire("b").allowed(), "clients are limited independently");

        nanos.addAndGet(1_000_000_000L); // one second refills 5 tokens
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            allowed += limiter.tryAcquire("a").allowed() ? 1 : 0;
        }
        assertEquals(5, allowed);

        nanos.addAndGet(60_000_000_000L);
        assertEquals(2, limiter.sweep());
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    void fixedWindowLetsThroughADoubleBurstAtTheBoundaryButTheOthersDoNot() {
        AtomicLong nanos = new AtomicLong(990_000_000L); // 10 ms before a window boundary
        FixedWindowRateLimiter fixed = new FixedWindowRateLimiter(10, 1.0, nanos::get);
        SlidingWindowLogRateLimiter sliding = new SlidingWindowLogRateLimiter(10, 1.0, nanos::get);
        TokenBucketRateLimiter bucket = new TokenBucketRateLimiter(10, 10, nanos::get);
        int fixedAllowed = 0;
        int slidingAllowed = 0;
        int bucketAllowed = 0;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 10; i++) {
                fixedAllowed += fixed.tryAcquire("c").allowed() ? 1 : 0;
                slidingAllowed += sliding.tryAcquire("c").allowed() ? 1 : 0;
                bucketAllowed += bucket.tryAcquire("c").allowed() ? 1 : 0;
            }
            nanos.addAndGet(20_000_000L); // cross the boundary
        }
        assertEquals(20, fixedAllowed);
        assertEquals(10, slidingAllowed);
        assertEquals(10, bucketAllowed);
    }

    // ---- consistent hashing ----------------------------------------------------------------

    private static List<String> keys(int n) {
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add("patient:" + i);
        }
        return keys;
    }

    @Test
    void ringSpreadsKeysEvenly() {
        HashRing ring = new HashRing(150, List.of("n1", "n2", "n3", "n4", "n5"));
        Map<String, Integer> counts = new HashMap<>();
        keys(100_000).forEach(key -> counts.merge(ring.route(key), 1, Integer::sum));
        assertEquals(5, counts.size());
        counts.values().forEach(count -> assertTrue(Math.abs(count - 20_000) < 5_000, "got " + counts));
        assertEquals(1.0, ring.ownership().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-6);
    }

    @Test
    void addingANodeMovesOnlyItsShareOfKeysUnlikeModulo() {
        List<String> keys = keys(50_000);
        HashRing ring = new HashRing(150, List.of("n1", "n2", "n3", "n4"));
        ModuloRouter modulo = new ModuloRouter();
        List.of("n1", "n2", "n3", "n4").forEach(modulo::addNode);
        Map<String, String> ringBefore = new HashMap<>();
        Map<String, String> moduloBefore = new HashMap<>();
        keys.forEach(key -> {
            ringBefore.put(key, ring.route(key));
            moduloBefore.put(key, modulo.route(key));
        });
        ring.addNode("n5");
        modulo.addNode("n5");
        int ringMoved = 0;
        int moduloMoved = 0;
        for (String key : keys) {
            String now = ring.route(key);
            if (!now.equals(ringBefore.get(key))) {
                ringMoved++;
                assertEquals("n5", now, "a key may only move to the new node");
            }
            moduloMoved += modulo.route(key).equals(moduloBefore.get(key)) ? 0 : 1;
        }
        assertTrue(ringMoved < keys.size() * 0.30, "ring moved " + ringMoved);
        assertTrue(moduloMoved > keys.size() * 0.70, "modulo moved " + moduloMoved);
    }

    @Test
    void preferenceListHasDistinctNodesAndSurvivesRemoval() {
        HashRing ring = new HashRing(100, List.of("n1", "n2", "n3"));
        List<String> preference = ring.routeN("patient:42", 2);
        assertEquals(2, Set.copyOf(preference).size());
        assertEquals(ring.route("patient:42"), preference.get(0));
        ring.removeNode(preference.get(0));
        assertEquals(preference.get(1), ring.route("patient:42"), "the next replica takes over");
        assertEquals(3, new HashRing(10, List.of("a", "b", "c")).routeN("k", 9).size());
        assertEquals(null, new HashRing(10).route("k"));
        assertEquals(NodeRouter.hash64("same"), NodeRouter.hash64("same"));
    }

    // ---- autocomplete ----------------------------------------------------------------------

    @Test
    void suggestionsAreRankedMatchAnyWordAndTolerateATypo() {
        Autocomplete<String> index = new Autocomplete<>();
        index.add("d1", "Dr. Asha Rao", 4.0, "Cardiology");
        index.add("d2", "Dr. Ashok Rane", 9.0, "Neurology");
        index.add("d3", "Dr. Meera Nair", 5.0, "Cardiology");
        index.add("s1", "Cardiology", 50.0, null);

        assertEquals(List.of("d2", "d1"), index.suggest("ash", 5, false).stream().map(Autocomplete.Suggestion::id).toList());
        assertEquals("d1", index.suggest("rao", 5).get(0).id(), "matches a later word");
        assertEquals("s1", index.suggest("CARD", 5).get(0).id(), "case-insensitive");
        assertEquals("Neurology", index.suggest("ashok", 1).get(0).payload());
        assertEquals("d3", index.suggest("mera", 5).get(0).id(), "missing letter");
        assertEquals("d3", index.suggest("meeta", 5).get(0).id(), "wrong letter");
        assertEquals("d3", index.suggest("meear", 5).get(0).id(), "swapped letters");
        assertEquals(List.of("s1"), index.suggest("car", 5).stream().map(Autocomplete.Suggestion::id).toList(),
                "short prefixes are matched exactly, so 'car' does not drift to 'ar...'");
        assertTrue(index.suggest("emera", 5).isEmpty(), "the first letter is never corrected");
        assertTrue(index.suggest("zzz", 5).isEmpty());
        assertTrue(index.suggest("  ", 5).isEmpty());

        index.bump("d1", 10);
        assertEquals("d1", index.suggest("ash", 5).get(0).id(), "bumped entry now ranks first");
    }

    @Test
    void topKCacheStaysCorrectWhenMoreEntriesThanTheCacheHolds() {
        Autocomplete<Integer> index = new Autocomplete<>(3, true);
        for (int i = 0; i < 50; i++) {
            index.add("m" + i, "Medicine " + i, i, i);
        }
        assertEquals(List.of(49, 48, 47), index.suggest("med", 3).stream().map(Autocomplete.Suggestion::payload).toList());
        index.bump("m0", 1000);
        assertEquals(0, index.suggest("med", 3).get(0).payload());
        assertEquals(50, index.size());
    }

    // ---- URL shortener ---------------------------------------------------------------------

    @Test
    void shortenerOnlyAcceptsSiteRelativeTargets() {
        assertTrue(UrlShortener.isSafeTarget("/#/bills/BIL123"));
        assertFalse(UrlShortener.isSafeTarget("https://evil.example/"));
        assertFalse(UrlShortener.isSafeTarget("//evil.example/"));
        assertFalse(UrlShortener.isSafeTarget("/\\evil.example"));
        assertFalse(UrlShortener.isSafeTarget(""));
        UrlShortener shortener = new UrlShortener(new UrlShortener.InMemoryStore(), 8);
        assertThrows(IllegalArgumentException.class, () -> shortener.shorten("http://x.test/", null));
    }

    @Test
    void shortenerResolvesRetriesCollisionsAndExpires() throws Exception {
        UrlShortener.InMemoryStore backing = new UrlShortener.InMemoryStore();
        AtomicLong rejections = new AtomicLong(2);
        UrlShortener shortener = new UrlShortener(new UrlShortener.Store() {
            @Override
            public boolean saveIfAbsent(String code, String target, Instant expiresAt) {
                return rejections.getAndDecrement() <= 0 && backing.saveIfAbsent(code, target, expiresAt);
            }

            @Override
            public Optional<String> find(String code) {
                return backing.find(code);
            }
        }, 8);
        String code = shortener.shorten("/#/bills/1", null);
        assertEquals(8, code.length());
        assertEquals(2, shortener.collisionCount());
        assertEquals(Optional.of("/#/bills/1"), shortener.resolve(code));
        assertEquals(Optional.empty(), shortener.resolve("nope"));
        assertEquals(Optional.empty(), shortener.resolve("../../etc"));

        String shortLived = shortener.shorten("/#/rx/2", Duration.ofMillis(1));
        Thread.sleep(20);
        assertEquals(Optional.empty(), shortener.resolve(shortLived));
    }
}

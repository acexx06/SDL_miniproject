package com.hms.bench;

import com.hms.components.autocomplete.Autocomplete;
import com.hms.components.autocomplete.LinearScanAutocomplete;
import com.hms.components.crawler.CrawlConfig;
import com.hms.components.crawler.CrawlStats;
import com.hms.components.crawler.Crawler;
import com.hms.components.hashing.HashRing;
import com.hms.components.hashing.ModuloRouter;
import com.hms.components.hashing.NodeRouter;
import com.hms.components.idgen.SnowflakeIdGenerator;
import com.hms.components.kv.KvCluster;
import com.hms.components.kv.KvEngine;
import com.hms.components.kv.KvNodeClient;
import com.hms.components.kv.KvNodeServer;
import com.hms.components.ratelimit.FixedWindowRateLimiter;
import com.hms.components.ratelimit.RateLimiter;
import com.hms.components.ratelimit.SlidingWindowLogRateLimiter;
import com.hms.components.ratelimit.TokenBucketRateLimiter;
import com.hms.components.shortener.UrlShortener;
import com.hms.sim.MockSites;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Measures each of the seven components on its own, next to a simple baseline where one exists.
 * Nothing here needs the application to be running; the key-value section starts its own node
 * servers and the crawler section its own simulated websites.
 */
public final class ComponentBench {

    /** Results of timed loops are added here so the JIT cannot discard the work being measured. */
    private static volatile long blackhole;

    private final boolean quick;
    private final String mongoUri;

    public ComponentBench(boolean quick, String mongoUri) {
        this.quick = quick;
        this.mongoUri = mongoUri;
    }

    public Map<String, Object> runAll() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idGenerator", step("unique ID generator", this::idGenerator));
        result.put("rateLimiter", step("rate limiter", this::rateLimiter));
        result.put("consistentHashing", step("consistent hashing", this::consistentHashing));
        result.put("autocomplete", step("autocomplete", this::autocomplete));
        result.put("urlShortener", step("URL shortener", this::urlShortener));
        result.put("keyValueStore", step("key-value store", this::keyValueStore));
        result.put("clusterTopology", step("key-value cluster topology changes", this::clusterTopology));
        result.put("crawler", step("web crawler", this::crawler));
        return result;
    }

    @FunctionalInterface
    private interface Section {
        Object run() throws Exception;
    }

    private static Object step(String name, Section section) throws Exception {
        System.out.println("  [components] " + name + " ...");
        return section.run();
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static double r(double value) {
        return LatencyRecorder.round(value);
    }

    private static void parallel(int threads, IntConsumer body) throws InterruptedException {
        List<Thread> list = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int index = t;
            Thread thread = new Thread(() -> body.accept(index));
            list.add(thread);
            thread.start();
        }
        for (Thread thread : list) {
            thread.join();
        }
    }

    private static long duplicates(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        long dup = 0;
        for (int i = 1; i < sorted.length; i++) {
            dup += sorted[i] == sorted[i - 1] ? 1 : 0;
        }
        return dup;
    }

    // ---- unique ID generator ---------------------------------------------------------------

    private Object idGenerator() throws InterruptedException {
        int n = quick ? 400_000 : 2_000_000;
        List<Map<String, Object>> rows = new ArrayList<>();

        SnowflakeIdGenerator single = new SnowflakeIdGenerator(1);
        long[] ids = new long[n];
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ids[i] = single.nextId();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        boolean increasing = true;
        for (int i = 1; i < n; i++) {
            increasing &= ids[i] > ids[i - 1];
        }
        rows.add(row("generator", "Snowflake, 1 thread", "ids", n, "idsPerSecond", r(n / seconds),
                "collisions", duplicates(ids), "strictlyIncreasing", increasing, "bits", 64, "coordinationFree", true));

        SnowflakeIdGenerator shared = new SnowflakeIdGenerator(2);
        long[] sharedIds = new long[n];
        start = System.nanoTime();
        parallel(8, t -> {
            for (int i = t * (n / 8); i < (t + 1) * (n / 8); i++) {
                sharedIds[i] = shared.nextId();
            }
        });
        seconds = (System.nanoTime() - start) / 1e9;
        rows.add(row("generator", "Snowflake, 8 threads sharing 1 worker id", "ids", n, "idsPerSecond", r(n / seconds),
                "collisions", duplicates(sharedIds), "bits", 64, "coordinationFree", true));

        long[] workerIds = new long[n];
        start = System.nanoTime();
        parallel(4, t -> {
            SnowflakeIdGenerator own = new SnowflakeIdGenerator(10 + t);
            for (int i = t * (n / 4); i < (t + 1) * (n / 4); i++) {
                workerIds[i] = own.nextId();
            }
        });
        seconds = (System.nanoTime() - start) / 1e9;
        rows.add(row("generator", "Snowflake, 4 servers (4 worker ids)", "ids", n, "idsPerSecond", r(n / seconds),
                "collisions", duplicates(workerIds), "bits", 64, "coordinationFree", true));

        int uuidCount = n / 4; // fewer, because remembering them all to count collisions is memory-hungry
        UUID[] uuidArray = new UUID[uuidCount];
        start = System.nanoTime();
        for (int i = 0; i < uuidCount; i++) {
            uuidArray[i] = UUID.randomUUID();
        }
        seconds = (System.nanoTime() - start) / 1e9;
        Set<UUID> uuids = new java.util.HashSet<>(Arrays.asList(uuidArray));
        rows.add(row("generator", "Baseline: random UUID (v4)", "ids", uuidCount, "idsPerSecond", r(uuidCount / seconds),
                "collisions", uuidCount - uuids.size(), "strictlyIncreasing", false, "bits", 128, "coordinationFree", true));
        uuids.clear();

        Random random = new Random(42);
        long[] random32 = new long[n];
        start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            random32[i] = random.nextInt() & 0xFFFFFFFFL;
        }
        seconds = (System.nanoTime() - start) / 1e9;
        rows.add(row("generator", "Baseline: random 32-bit number", "ids", n, "idsPerSecond", r(n / seconds),
                "collisions", duplicates(random32), "strictlyIncreasing", false, "bits", 32, "coordinationFree", true));

        AtomicLong counter = new AtomicLong();
        start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            counter.incrementAndGet();
        }
        seconds = (System.nanoTime() - start) / 1e9;
        rows.add(row("generator", "Baseline: single shared counter", "ids", n, "idsPerSecond", r(n / seconds),
                "collisions", 0, "strictlyIncreasing", true, "bits", 64, "coordinationFree", false));

        return row("theoreticalMaxPerWorkerPerSecond", 4_096_000, "generators", rows);
    }

    // ---- rate limiter ----------------------------------------------------------------------

    private static final int SIMULATED_SECONDS = 3600;

    private Object rateLimiter() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Rejection rate against offered load, on a simulated clock (limit 10 requests/second).
        List<Map<String, Object>> offered = new ArrayList<>();
        for (double multiple : new double[]{0.5, 0.8, 1, 1.5, 2, 5, 10}) {
            Map<String, Object> entry = row("offeredLoadMultiple", multiple);
            long arrivals = 0;
            for (String algorithm : List.of("tokenBucket", "fixedWindow", "slidingWindowLog")) {
                long[] now = {0};
                RateLimiter limiter = limiter(algorithm, () -> now[0]);
                Random rng = new Random(7);
                double t = 0;
                long total = 0;
                long rejected = 0;
                while (t < SIMULATED_SECONDS) { // Poisson arrivals: random gaps with the chosen average rate
                    t += -Math.log(1 - rng.nextDouble()) / (10 * multiple);
                    now[0] = (long) (t * 1e9);
                    total++;
                    rejected += limiter.tryAcquire("client").allowed() ? 0 : 1;
                }
                entry.put(algorithm, r((double) rejected / total));
                arrivals = total; // identical for each algorithm: they are fed the same arrival sequence
            }
            // The least any limiter could reject: everything beyond 10 per second of what actually arrived.
            entry.put("expectedRejection", r(Math.max(0, 1 - 10.0 * SIMULATED_SECONDS / arrivals)));
            entry.put("arrivals", arrivals);
            offered.add(entry);
        }
        result.put("rejectionVsOfferedLoad", offered);

        // 2. Burst straddling a window boundary: 10 requests 10 ms before it, 10 more 10 ms after.
        Map<String, Object> boundary = new LinkedHashMap<>();
        for (String algorithm : List.of("tokenBucket", "fixedWindow", "slidingWindowLog")) {
            long[] now = {990_000_000L};
            RateLimiter limiter = limiter(algorithm, () -> now[0]);
            int admitted = 0;
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < 10; i++) {
                    admitted += limiter.tryAcquire("client").allowed() ? 1 : 0;
                }
                now[0] += 20_000_000L;
            }
            boundary.put(algorithm, admitted);
        }
        result.put("admittedIn20msAcrossWindowBoundary", boundary);
        result.put("intendedLimitPerSecond", 10);

        // 3. Cost per decision and memory per tracked client.
        int calls = quick ? 500_000 : 3_000_000;
        int clients = quick ? 20_000 : 100_000;
        List<Map<String, Object>> cost = new ArrayList<>();
        for (String algorithm : List.of("tokenBucket", "fixedWindow", "slidingWindowLog")) {
            RateLimiter timed = limiter(algorithm, System::nanoTime);
            String[] keys = new String[1000];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = "client-" + i;
            }
            for (int i = 0; i < 200_000; i++) {
                timed.tryAcquire(keys[i % keys.length]); // warm up the JIT
            }
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                timed.tryAcquire(keys[i % keys.length]);
            }
            double nanosPerCall = (System.nanoTime() - start) / (double) calls;

            long before = usedHeap();
            long[] now = {0};
            RateLimiter sized = limiter(algorithm, () -> now[0]);
            for (int c = 0; c < clients; c++) {
                String key = "client-" + c;
                for (int i = 0; i < 10; i++) {
                    sized.tryAcquire(key);
                }
            }
            long bytesPerClient = Math.max(0, (usedHeap() - before) / clients);
            cost.add(row("algorithm", algorithm, "nanosPerDecision", r(nanosPerCall),
                    "decisionsPerSecond", r(1e9 / nanosPerCall), "bytesPerClient", bytesPerClient,
                    "trackedClients", sized.trackedKeys()));
        }
        result.put("cost", cost);
        return result;
    }

    private static RateLimiter limiter(String algorithm, LongSupplier clock) {
        return switch (algorithm) {
            case "tokenBucket" -> new TokenBucketRateLimiter(10, 10, clock);
            case "fixedWindow" -> new FixedWindowRateLimiter(10, 1.0, clock);
            default -> new SlidingWindowLogRateLimiter(10, 1.0, clock);
        };
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    // ---- consistent hashing ----------------------------------------------------------------

    private static List<String> keys(String prefix, int n) {
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(prefix + i);
        }
        return keys;
    }

    private static List<String> nodeNames(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            names.add("server-" + i);
        }
        return names;
    }

    private Object consistentHashing() {
        List<String> keys = keys("patient:", quick ? 50_000 : 200_000);
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. How evenly 5 servers share the keys, for different numbers of virtual nodes. Where a server's
        //    virtual nodes land is a matter of luck, so each setting is averaged over many differently
        //    named sets of servers; one ring alone would give a misleadingly jagged curve.
        int trials = quick ? 10 : 30;
        List<String> sample = keys.subList(0, Math.min(keys.size(), 50_000));
        List<Map<String, Object>> balance = new ArrayList<>();
        for (int vnodes : new int[]{1, 5, 10, 25, 50, 100, 150, 300, 500}) {
            double stdDevSum = 0;
            double largest = 0;
            double smallest = 100;
            List<Integer> firstTrial = null;
            for (int trial = 0; trial < trials; trial++) {
                List<String> servers = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    servers.add("server-" + i + (trial == 0 ? "" : "-set" + trial));
                }
                HashRing ring = new HashRing(vnodes, servers);
                Map<String, Integer> counts = new LinkedHashMap<>();
                servers.forEach(node -> counts.put(node, 0));
                sample.forEach(key -> counts.merge(ring.route(key), 1, Integer::sum));
                double mean = sample.size() / 5.0;
                double variance = counts.values().stream().mapToDouble(c -> (c - mean) * (c - mean)).sum() / 5;
                stdDevSum += Math.sqrt(variance) / mean * 100;
                largest = Math.max(largest, counts.values().stream().mapToInt(Integer::intValue).max().orElse(0) * 100.0 / sample.size());
                smallest = Math.min(smallest, counts.values().stream().mapToInt(Integer::intValue).min().orElse(0) * 100.0 / sample.size());
                if (trial == 0) {
                    firstTrial = new ArrayList<>(counts.values());
                }
            }
            balance.add(row("virtualNodes", vnodes, "stdDevPercentOfMean", r(stdDevSum / trials),
                    "largestSharePercent", r(largest), "smallestSharePercent", r(smallest), "keysPerServer", firstTrial));
        }
        result.put("balanceVsVirtualNodes", balance);
        result.put("balanceTrials", trials);
        result.put("idealSharePercent", 20.0);

        // 2. How many keys change server when the membership changes.
        List<Map<String, Object>> remap = new ArrayList<>();
        for (int[] change : new int[][]{{4, 5}, {5, 4}, {10, 11}, {3, 2}}) {
            int from = change[0];
            int to = change[1];
            Map<String, Object> entry = row("change", from + " to " + to + " servers", "from", from, "to", to,
                    "idealPercent", r(100.0 * Math.abs(to - from) / Math.max(from, to)));
            for (boolean consistent : new boolean[]{true, false}) {
                NodeRouter router = consistent ? new HashRing(150) : new ModuloRouter();
                nodeNames(from).forEach(router::addNode);
                Map<String, String> before = new HashMap<>();
                keys.forEach(key -> before.put(key, router.route(key)));
                if (to > from) {
                    router.addNode("server-" + to);
                } else {
                    router.removeNode("server-" + from);
                }
                long moved = keys.stream().filter(key -> !router.route(key).equals(before.get(key))).count();
                entry.put(consistent ? "consistentHashingPercent" : "moduloPercent", r(moved * 100.0 / keys.size()));
            }
            remap.add(entry);
        }
        result.put("keysMovedOnMembershipChange", remap);

        // 3. Lookup cost.
        HashRing ring = new HashRing(150, nodeNames(5));
        ModuloRouter modulo = new ModuloRouter();
        nodeNames(5).forEach(modulo::addNode);
        Map<String, Object> lookup = new LinkedHashMap<>();
        for (NodeRouter router : List.of(ring, modulo)) {
            for (int round = 0; round < 3; round++) { // the last round is the measured one
                long sink = 0;
                long start = System.nanoTime();
                for (String key : keys) {
                    sink += router.route(key).length();
                }
                lookup.put(router == ring ? "consistentHashingNanos" : "moduloNanos",
                        r((System.nanoTime() - start) / (double) keys.size()));
                blackhole = sink;
            }
        }
        result.put("lookupCost", lookup);
        return result;
    }

    // ---- autocomplete ----------------------------------------------------------------------

    private static final String[] SYLLABLES = {"an", "ar", "bha", "chi", "dev", "esh", "fa", "gan", "har", "in", "jay",
            "kar", "lak", "man", "nav", "om", "pra", "qui", "raj", "san", "tan", "um", "vik", "war", "xa", "yash", "zo",
            "mi", "ni", "pal", "ro", "shi", "tri", "ven", "vas", "pre", "kri", "su", "la", "de"};

    /** {@code n} distinct two-word names with a popularity weight that falls off steeply, like real search demand. */
    private static List<String[]> dictionary(int n, Random rng) {
        Set<String> names = new LinkedHashSet<>();
        while (names.size() < n) {
            StringBuilder sb = new StringBuilder();
            for (int word = 0; word < 2; word++) {
                StringBuilder w = new StringBuilder();
                for (int s = 0; s < 2 + rng.nextInt(2); s++) {
                    w.append(SYLLABLES[rng.nextInt(SYLLABLES.length)]);
                }
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(word == 0 ? " " : "");
            }
            names.add(sb.toString());
        }
        List<String[]> entries = new ArrayList<>(n);
        int rank = 1;
        for (String name : names) {
            entries.add(new String[]{"e" + rank, name, String.valueOf(1_000_000.0 / rank)});
            rank++;
        }
        return entries;
    }

    private Object autocomplete() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> scaling = new ArrayList<>();
        int[] sizes = quick ? new int[]{1_000, 10_000} : new int[]{1_000, 10_000, 100_000};
        Autocomplete<String> largest = null;
        LinearScanAutocomplete largestLinear = null;
        List<String[]> largestEntries = null;

        // Exercise both implementations first so the JIT has compiled them before anything is timed.
        Autocomplete<String> warmTrie = new Autocomplete<>(10, true);
        LinearScanAutocomplete warmLinear = new LinearScanAutocomplete();
        for (String[] e : dictionary(2_000, new Random(1))) {
            warmTrie.add(e[0], e[1], Double.parseDouble(e[2]), e[1]);
            warmLinear.add(e[0], e[1]);
        }
        for (int i = 0; i < 60_000; i++) {
            String q = SYLLABLES[i % SYLLABLES.length];
            blackhole += warmTrie.suggest(q, 5).size() + (i % 20 == 0 ? warmLinear.suggest(q, 5).size() : 0);
        }

        for (int size : sizes) {
            Random rng = new Random(size);
            List<String[]> entries = dictionary(size, rng);
            long heapBefore = usedHeap();
            long start = System.nanoTime();
            Autocomplete<String> trie = new Autocomplete<>(10, true);
            for (String[] e : entries) {
                trie.add(e[0], e[1], Double.parseDouble(e[2]), e[1]);
            }
            double buildMs = (System.nanoTime() - start) / 1e6;
            long heapAfter = usedHeap();
            LinearScanAutocomplete linear = new LinearScanAutocomplete();
            entries.forEach(e -> linear.add(e[0], e[1]));

            // Prefixes of 2 to 5 letters taken from the start of either word of a random entry.
            List<String> queries = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                String[] words = entries.get(rng.nextInt(size))[1].split(" ");
                String word = words[rng.nextInt(words.length)];
                queries.add(word.substring(0, Math.min(word.length(), 2 + rng.nextInt(4))));
            }
            for (int i = 0; i < 20_000; i++) {
                trie.suggest(queries.get(i), 5, false); // warm up
                trie.suggest(queries.get(i), 5, true);
            }
            // Timed twice: exact prefix matching only (the same job the linear scan does), and with the
            // typo-tolerant fallback, which costs extra whenever a prefix has fewer than 5 exact matches.
            LatencyRecorder trieLatency = new LatencyRecorder();
            LatencyRecorder typoLatency = new LatencyRecorder();
            for (String q : queries) {
                long t = System.nanoTime();
                trie.suggest(q, 5, false);
                trieLatency.add(System.nanoTime() - t);
                t = System.nanoTime();
                trie.suggest(q, 5, true);
                typoLatency.add(System.nanoTime() - t);
            }
            int linearQueries = Math.max(200, Math.min(queries.size(), 20_000_000 / size));
            LatencyRecorder linearLatency = new LatencyRecorder();
            for (int i = 0; i < linearQueries; i++) {
                long t = System.nanoTime();
                linear.suggest(queries.get(i), 5);
                linearLatency.add(System.nanoTime() - t);
            }
            scaling.add(row("entries", size, "trieMicros", trieLatency.summaryMicros(),
                    "trieWithTypoToleranceMicros", typoLatency.summaryMicros(), "linearScanMicros",
                    linearLatency.summaryMicros(), "buildMs", r(buildMs), "trieNodes", trie.nodeCount(),
                    "approxHeapMb", r(Math.max(0, heapAfter - heapBefore) / 1048576.0)));
            largest = trie;
            largestLinear = linear;
            largestEntries = entries;
        }
        result.put("latencyVsDictionarySize", scaling);

        // Accuracy, on the largest dictionary.
        Random rng = new Random(99);
        int size = largestEntries.size();
        Map<String, Double> weights = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (String[] e : largestEntries) {
            weights.put(e[0], Double.parseDouble(e[2]));
            names.put(e[0], Autocomplete.normalize(e[1]));
        }

        // (a) Correctness: is the trie's top 5 exactly the 5 heaviest matching entries?
        int checks = quick ? 200 : 500;
        int exact = 0;
        for (int i = 0; i < checks; i++) {
            String name = largestEntries.get(rng.nextInt(size))[1];
            String prefix = name.substring(0, 2 + rng.nextInt(3));
            String q = Autocomplete.normalize(prefix);
            List<String> truth = names.entrySet().stream()
                    .filter(e -> e.getValue().startsWith(q) || e.getValue().contains(" " + q))
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.comparingDouble((String id) -> -weights.get(id)))
                    .limit(5).toList();
            List<String> got = largest.suggest(prefix, 5, false).stream().map(Autocomplete.Suggestion::id).toList();
            exact += truth.equals(got) ? 1 : 0;
        }
        result.put("top5MatchesBruteForcePercent", r(exact * 100.0 / checks));

        // (b) Usefulness: a user wants one entry (popular ones more often) and types part of its name.
        int trials = quick ? 1_000 : 3_000;
        List<Map<String, Object>> usefulness = new ArrayList<>();
        for (int typed : new int[]{2, 3, 4, 5}) {
            int trieHits = 0;
            int linearHits = 0;
            for (int i = 0; i < trials; i++) {
                String[] target = largestEntries.get(zipf(size, rng));
                String prefix = target[1].substring(0, Math.min(typed, target[1].length()));
                trieHits += largest.suggest(prefix, 5, false).stream().anyMatch(s -> s.id().equals(target[0])) ? 1 : 0;
                linearHits += largestLinear.suggest(prefix, 5).contains(target[0]) ? 1 : 0;
            }
            usefulness.add(row("lettersTyped", typed, "rankedTriePercent", r(trieHits * 100.0 / trials),
                    "alphabeticalBaselinePercent", r(linearHits * 100.0 / trials)));
        }
        result.put("targetInTop5", usefulness);

        // (c) Typos: the same, but one letter of a 6-letter prefix is wrong, missing, extra or swapped.
        int withTolerance = 0;
        int withoutTolerance = 0;
        int baseline = 0;
        for (int i = 0; i < trials; i++) {
            String[] target = largestEntries.get(zipf(size, rng));
            String typo = typo(target[1].substring(0, Math.min(6, target[1].length())), rng);
            withTolerance += largest.suggest(typo, 5, true).stream().anyMatch(s -> s.id().equals(target[0])) ? 1 : 0;
            withoutTolerance += largest.suggest(typo, 5, false).stream().anyMatch(s -> s.id().equals(target[0])) ? 1 : 0;
            baseline += largestLinear.suggest(typo, 5).contains(target[0]) ? 1 : 0;
        }
        result.put("targetInTop5WithOneTypo", row("typoTolerantTriePercent", r(withTolerance * 100.0 / trials),
                "exactPrefixTriePercent", r(withoutTolerance * 100.0 / trials),
                "alphabeticalBaselinePercent", r(baseline * 100.0 / trials)));
        result.put("accuracyDictionarySize", size);
        return result;
    }

    /** Index drawn with probability proportional to 1/rank, matching the weights given to the entries. */
    private static int zipf(int n, Random rng) {
        return (int) Math.min(n - 1, Math.floor(Math.exp(rng.nextDouble() * Math.log(n))) - 1);
    }

    private static String typo(String text, Random rng) {
        int i = 1 + rng.nextInt(Math.max(1, text.length() - 2)); // never the first letter
        char other = (char) ('a' + rng.nextInt(26));
        return switch (rng.nextInt(4)) {
            case 0 -> text.substring(0, i) + other + text.substring(i + 1); // wrong letter
            case 1 -> text.substring(0, i) + text.substring(i + 1); // missing letter
            case 2 -> text.substring(0, i) + other + text.substring(i); // extra letter
            default -> i + 1 < text.length()
                    ? text.substring(0, i) + text.charAt(i + 1) + text.charAt(i) + text.substring(i + 2) // swapped
                    : text.substring(0, i) + other + text.substring(i + 1);
        };
    }

    // ---- URL shortener ---------------------------------------------------------------------

    private Object urlShortener() {
        int n = quick ? 100_000 : 500_000;
        List<Map<String, Object>> byLength = new ArrayList<>();
        for (int length : new int[]{4, 5, 6, 8}) {
            UrlShortener shortener = new UrlShortener(new UrlShortener.InMemoryStore(), length);
            List<String> codes = new ArrayList<>(n);
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                codes.add(shortener.shorten("/#/bills/" + i, null));
            }
            double createSeconds = (System.nanoTime() - start) / 1e9;
            start = System.nanoTime();
            int resolved = 0;
            for (String code : codes) {
                resolved += shortener.resolve(code).isPresent() ? 1 : 0;
            }
            double resolveNanos = (System.nanoTime() - start) / (double) n;
            byLength.add(row("codeLength", length, "codeSpace", Math.pow(62, length), "linksCreated", n,
                    "collisionsRetried", shortener.collisionCount(),
                    "collisionRatePercent", r(shortener.collisionCount() * 100.0 / n),
                    "createdPerSecond", r(n / createSeconds), "resolveNanos", r(resolveNanos), "allResolved", resolved == n));
        }
        return row("inMemory", byLength, "note", "End-to-end redirect latency through the application is in load.json");
    }

    // ---- key-value store -------------------------------------------------------------------

    private static String value(int i) {
        return "{\"id\":\"PAT" + i + "\",\"name\":\"Synthetic Patient " + i + "\",\"bloodGroup\":\"O+\",\"phone\":\"9000000000\","
                + "\"notes\":\"" + "x".repeat(120) + "\"}";
    }

    private Object keyValueStore() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        int[] sizes = quick ? new int[]{10_000, 100_000} : new int[]{10_000, 100_000, 1_000_000};
        List<Map<String, Object>> rows = new ArrayList<>();

        try (KvNodeServer server = new KvNodeServer("bench", 0, 4_000_000)) {
            server.start();
            // A generous read timeout: a long pause should show up as a large "max" latency in the
            // results, not end the measurement. (The application keeps the short default, because
            // there a slow cache node should be skipped quickly in favour of the database.)
            try (KvNodeClient client = new KvNodeClient("127.0.0.1", server.port(), 500, 10_000)) {
                int loaded = 0;
                for (int size : sizes) {
                    int from = loaded;
                    parallel(8, t -> {
                        for (int i = from + t; i < size; i += 8) {
                            client.set("patient:" + i, value(i), 0);
                        }
                    });
                    if (server.engine().size() != size) {
                        throw new IllegalStateException("expected " + size + " keys, node holds " + server.engine().size());
                    }
                    loaded = size;
                    Random rng = new Random(size);
                    for (int i = 0; i < 20_000; i++) {
                        client.get("patient:" + rng.nextInt(size)); // warm up
                    }
                    LatencyRecorder get = new LatencyRecorder();
                    for (int i = 0; i < 30_000; i++) {
                        String key = "patient:" + rng.nextInt(size);
                        long t = System.nanoTime();
                        client.get(key);
                        get.add(System.nanoTime() - t);
                    }
                    LatencyRecorder set = new LatencyRecorder();
                    for (int i = 0; i < 10_000; i++) {
                        int k = rng.nextInt(size);
                        long t = System.nanoTime();
                        client.set("patient:" + k, value(k), 0);
                        set.add(System.nanoTime() - t);
                    }
                    long[] done = new long[16];
                    long until = System.nanoTime() + 2_000_000_000L;
                    parallel(16, t -> {
                        Random r2 = new Random(t);
                        while (System.nanoTime() < until) {
                            client.get("patient:" + r2.nextInt(size));
                            done[t]++;
                        }
                    });
                    KvEngine engine = server.engine();
                    String[] probes = new String[100_000];
                    for (int i = 0; i < probes.length; i++) {
                        probes[i] = "patient:" + rng.nextInt(size);
                    }
                    long start = System.nanoTime();
                    for (int i = 0; i < 1_000_000; i++) {
                        engine.get(probes[i % probes.length]);
                    }
                    double engineNanos = (System.nanoTime() - start) / 1e6; // total ns over 1e6 calls = ns per call
                    rows.add(row("keys", size, "getMicros", get.summaryMicros(), "setMicros", set.summaryMicros(),
                            "getsPerSecond16Threads", r(Arrays.stream(done).sum() / 2.0),
                            "engineOnlyGetNanos", r(engineNanos)));
                }
            }
        }
        result.put("networkedNode", rows);

        // Baseline 1: the same data in an unindexed list, found by scanning.
        List<Map<String, Object>> scan = new ArrayList<>();
        for (int size : sizes) {
            List<String[]> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(new String[]{"patient:" + i, "v"});
            }
            Random rng = new Random(size);
            int lookups = Math.max(100, Math.min(20_000, 100_000_000 / size));
            LatencyRecorder latency = new LatencyRecorder();
            for (int i = 0; i < lookups; i++) {
                String key = "patient:" + rng.nextInt(size);
                long t = System.nanoTime();
                for (String[] entry : list) {
                    if (entry[0].equals(key)) {
                        break;
                    }
                }
                latency.add(System.nanoTime() - t);
            }
            scan.add(row("keys", size, "lookupMicros", latency.summaryMicros()));
        }
        result.put("linearScanBaseline", scan);

        // Baseline 2: the same lookup answered by MongoDB (by _id, its fastest path) on the same machine.
        List<Map<String, Object>> mongo = new ArrayList<>();
        try (MongoClient mongoClient = MongoClients.create(mongoUri)) {
            MongoCollection<Document> collection = mongoClient.getDatabase("hms_bench_kv").getCollection("lookup");
            collection.drop();
            int loaded = 0;
            for (int size : sizes) {
                List<Document> batch = new ArrayList<>();
                for (int i = loaded; i < size; i++) {
                    batch.add(new Document("_id", "patient:" + i).append("value", value(i)));
                    if (batch.size() == 5000 || i == size - 1) {
                        collection.insertMany(batch);
                        batch = new ArrayList<>();
                    }
                }
                loaded = size;
                Random rng = new Random(size);
                // Let MongoDB finish the work the bulk insert left behind, and warm its cache and the driver.
                long warmUntil = System.nanoTime() + 6_000_000_000L;
                parallel(16, t -> {
                    Random r2 = new Random(100 + t);
                    while (System.nanoTime() < warmUntil) {
                        collection.find(new Document("_id", "patient:" + r2.nextInt(size))).first();
                    }
                });
                LatencyRecorder latency = new LatencyRecorder();
                for (int i = 0; i < 10_000; i++) {
                    Document query = new Document("_id", "patient:" + rng.nextInt(size));
                    long t = System.nanoTime();
                    collection.find(query).first();
                    latency.add(System.nanoTime() - t);
                }
                long[] done = new long[16];
                long until = System.nanoTime() + 2_000_000_000L;
                parallel(16, t -> {
                    Random r2 = new Random(t);
                    while (System.nanoTime() < until) {
                        collection.find(new Document("_id", "patient:" + r2.nextInt(size))).first();
                        done[t]++;
                    }
                });
                mongo.add(row("keys", size, "findByIdMicros", latency.summaryMicros(),
                        "findsPerSecond16Threads", r(Arrays.stream(done).sum() / 2.0)));
            }
            mongoClient.getDatabase("hms_bench_kv").drop();
        } catch (RuntimeException e) {
            mongo.add(row("skipped", "MongoDB not reachable: " + e.getMessage()));
        }
        result.put("mongoFindByIdBaseline", mongo);
        return result;
    }

    // ---- key-value cluster: what a topology change costs --------------------------------------

    private Object clusterTopology() throws Exception {
        int keyCount = quick ? 10_000 : 40_000;
        List<String> keys = keys("patient:", keyCount);
        List<Map<String, Object>> rows = new ArrayList<>();
        String[][] setups = {{"consistent", "1"}, {"consistent", "2"}, {"modulo", "1"}};
        for (String event : List.of("one of 4 servers fails", "a 5th server is added")) {
            for (String[] setup : setups) {
                boolean consistent = setup[0].equals("consistent");
                int replicas = Integer.parseInt(setup[1]);
                List<KvNodeServer> servers = new ArrayList<>();
                NodeRouter router = consistent ? new HashRing(150) : new ModuloRouter();
                try (KvCluster cluster = new KvCluster(router, replicas, Duration.ofMillis(100))) {
                    for (int i = 1; i <= 4; i++) {
                        KvNodeServer server = new KvNodeServer("server-" + i, 0, 1_000_000);
                        server.start();
                        servers.add(server);
                        cluster.addNode(server.nodeId(), "127.0.0.1", server.port());
                    }
                    parallel(8, t -> {
                        for (int i = t; i < keyCount; i += 8) {
                            cluster.set(keys.get(i), "v", null);
                        }
                    });
                    List<Integer> spread = servers.stream().map(s -> s.engine().size()).toList();

                    long detectMs = 0;
                    if (event.startsWith("one")) {
                        servers.get(1).close();
                        long start = System.nanoTime();
                        cluster.get(keys.get(0));
                        while (cluster.router().nodes().size() == 4) {
                            Thread.sleep(5);
                        }
                        detectMs = (System.nanoTime() - start) / 1_000_000;
                    } else {
                        KvNodeServer extra = new KvNodeServer("server-5", 0, 1_000_000);
                        extra.start();
                        servers.add(extra);
                        cluster.addNode(extra.nodeId(), "127.0.0.1", extra.port());
                    }
                    long[] hits = new long[8];
                    long[] errors = new long[8];
                    parallel(8, t -> {
                        for (int i = t; i < keyCount; i += 8) {
                            try {
                                hits[t] += cluster.get(keys.get(i)) != null ? 1 : 0;
                            } catch (RuntimeException e) {
                                errors[t]++;
                            }
                        }
                    });
                    rows.add(row("event", event, "routing", consistent ? "consistent hashing" : "modulo (baseline)",
                            "replicas", replicas, "keysPerServerBefore", spread,
                            "cacheHitPercentAfter", r(Arrays.stream(hits).sum() * 100.0 / keyCount),
                            "errors", Arrays.stream(errors).sum(), "failureDetectedMs", detectMs));
                } finally {
                    servers.forEach(KvNodeServer::close);
                }
            }
        }
        return row("keys", keyCount, "results", rows);
    }

    // ---- web crawler -----------------------------------------------------------------------

    private Object crawler() throws Exception {
        int drugs = quick ? 100 : 300;
        int latencyMs = 20;
        List<Map<String, Object>> rows = new ArrayList<>();
        int[][] configs = quick ? new int[][]{{1, 0}, {8, 0}, {8, 50}} : new int[][]{{1, 0}, {2, 0}, {4, 0}, {8, 0}, {16, 0}, {32, 0}, {16, 25}};
        for (int[] config : configs) {
            try (MockSites sites = new MockSites(0, 0, latencyMs, drugs)) {
                sites.start();
                AtomicLong drugPages = new AtomicLong();
                AtomicLong planPages = new AtomicLong();
                CrawlStats stats = new Crawler(CrawlConfig.of(sites.seeds()).workers(config[0]).perHostConcurrency(config[0])
                        .perHostDelayMs(config[1]), page -> {
                    if ("drug".equals(page.type())) {
                        drugPages.incrementAndGet();
                    } else if ("insurance-plan".equals(page.type())) {
                        planPages.incrementAndGet();
                    }
                }).run();
                rows.add(row("workers", config[0], "perHostDelayMs", config[1], "pagesFetched", stats.pagesFetched(),
                        "pagesPerSecond", r(stats.pagesPerSecond()), "elapsedMs", stats.elapsedMs(),
                        "drugPages", drugPages.get(), "planPages", planPages.get(), "retries", stats.retries(), "failed", stats.failed(),
                        "duplicateUrlsSkipped", stats.duplicateUrls(), "duplicateContentSkipped", stats.duplicateContent(),
                        "blockedByRobots", stats.robotsBlocked(), "requestsToDisallowedPaths", sites.disallowedHits(),
                        "megabytes", r(stats.bytes() / 1048576.0)));
            }
        }
        return row("simulatedServerLatencyMs", latencyMs, "drugPagesOnSite", drugs, "runs", rows);
    }
}

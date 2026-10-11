package com.hms.bench;

import com.hms.sim.SyntheticData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Every measurement that goes through the running application over HTTP:
 *
 * <ol>
 *   <li>data growth: response times as the number of patients rises;</li>
 *   <li>the mixed workload at several load levels;</li>
 *   <li>comparisons with a baseline: cache on/off, search index vs database query,
 *       rate limiter on/off while one client misbehaves, short-link redirects;</li>
 *   <li>a timeline during which a cache server is killed, restarted and another one added.</li>
 * </ol>
 */
public final class EndToEndBench {

    private static final long THINK_MIN_MS = 100;
    private static final long THINK_MAX_MS = 300;

    private final boolean quick;
    private final Path appJar;
    private final String mongoUri;
    private final Path outDir;

    /** Called with everything measured so far after each stage, so an interrupted run still leaves results. */
    private java.util.function.Consumer<Map<String, Object>> checkpoint = partial -> { };

    private Http http;
    private String admin;
    private final List<String> patientIds = new ArrayList<>();
    private final List<String> patientEmails = new ArrayList<>();
    private List<String> doctorIds;
    private List<String> doctorQueries;

    public EndToEndBench(boolean quick, Path appJar, String mongoUri, Path outDir) {
        this.quick = quick;
        this.appJar = appJar;
        this.mongoUri = mongoUri;
        this.outDir = outDir;
    }

    public EndToEndBench onCheckpoint(java.util.function.Consumer<Map<String, Object>> checkpoint) {
        this.checkpoint = checkpoint;
        return this;
    }

    private void stage(Map<String, Object> result, String name, Object value) {
        result.put(name, value);
        checkpoint.accept(result);
    }

    // ---- small helpers -------------------------------------------------------------------------

    private Object adminCall(String method, String path, Object body) {
        return http.send(method, path, admin, null, body).require().json();
    }

    private void configure(Boolean cacheEnabled, Boolean rateLimitEnabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (cacheEnabled != null) {
            body.put("cacheEnabled", cacheEnabled);
        }
        if (rateLimitEnabled != null) {
            body.put("rateLimitEnabled", rateLimitEnabled);
        }
        adminCall("POST", "/api/admin/config", body);
    }

    private void connect(AppProcess app) {
        http = app.http();
        admin = app.adminToken();
        patientIds.clear();
        patientEmails.clear();
    }

    private void seedPatients(int count) {
        Object seeded = adminCall("POST", "/api/admin/seed/patients?count=" + count, null);
        patientIds.addAll(Json.<List<String>>at(seeded, "sampleIds"));
        patientEmails.addAll(Json.<List<String>>at(seeded, "sampleEmails"));
    }

    private static void parallel(int threads, Runnable body) throws InterruptedException {
        List<Thread> list = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(body);
            thread.setDaemon(true);
            list.add(thread);
            thread.start();
        }
        for (Thread thread : list) {
            thread.join();
        }
    }

    /** {@code threads} clients each sending requests back to back for {@code seconds}, with no think time. */
    private Map<String, Object> closedLoop(int threads, double seconds, Function<Random, Http.Response> request)
            throws InterruptedException {
        long warmUntil = System.nanoTime() + (long) (Math.min(1.0, seconds / 4) * 1e9);
        long until = warmUntil + (long) (seconds * 1e9);
        LatencyRecorder latency = new LatencyRecorder();
        LongAdder errors = new LongAdder();
        LongAdder limited = new LongAdder();
        parallel(threads, () -> {
            Random rng = ThreadLocalRandom.current();
            while (System.nanoTime() < until) {
                long start = System.nanoTime();
                Http.Response response = request.apply(rng);
                long end = System.nanoTime();
                if (end < warmUntil) {
                    continue;
                }
                if (response.status() == 429) {
                    limited.increment();
                } else if (response.status() < 200 || response.status() >= 400) {
                    errors.increment();
                } else {
                    latency.add(end - start);
                }
            }
        });
        long total = latency.count() + errors.sum() + limited.sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threads", threads);
        result.put("requestsPerSecond", LatencyRecorder.round(total / seconds));
        result.put("servedPerSecond", LatencyRecorder.round(latency.count() / seconds));
        result.put("rateLimited", limited.sum());
        result.put("rejectionRate", total == 0 ? 0 : LatencyRecorder.round((double) limited.sum() / total));
        result.put("errors", errors.sum());
        result.put("latencyMs", latency.summaryMs());
        return result;
    }

    /** Logs in {@code count} of the seeded patients and gathers the ids and search terms the workload draws from. */
    private Workload prepareWorkload(int count) throws InterruptedException {
        configure(null, false); // preparation is not part of the experiment, so it should not be throttled
        List<Workload.Account> accounts = Collections.synchronizedList(new ArrayList<>());
        List<Integer> todo = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < Math.min(count, patientEmails.size()); i++) {
            todo.add(i);
        }
        parallel(24, () -> {
            while (true) {
                Integer i;
                synchronized (todo) {
                    i = todo.isEmpty() ? null : todo.remove(todo.size() - 1);
                }
                if (i == null) {
                    return;
                }
                Workload.Account account = new Workload.Account(patientEmails.get(i), "Password@123", i + 1);
                Http.Response login = http.post("/api/auth/login", null, Map.of("email", account.email, "password", account.password));
                if (login.ok()) {
                    account.token = Json.at(login.json(), "token");
                    account.patientId = Json.at(login.json(), "user", "id");
                    accounts.add(account);
                }
            }
        });

        Set<String> ids = new LinkedHashSet<>();
        Set<String> queries = new LinkedHashSet<>();
        List<String> specializations = Json.at(http.get("/api/doctors/specializations", null).require().json());
        for (String specialization : specializations) {
            queries.add(specialization.substring(0, Math.min(specialization.length(), 4)).toLowerCase());
            List<Map<String, Object>> found = Json.at(http.get("/api/doctors/search?limit=50&specialization="
                    + Http.enc(specialization), null).require().json());
            for (Map<String, Object> doctor : found) {
                ids.add((String) doctor.get("id"));
                String[] words = ((String) doctor.get("name")).replace("Dr. ", "").split(" ");
                queries.add(words[0].substring(0, Math.min(3, words[0].length())).toLowerCase());
                queries.add(words[words.length - 1].substring(0, Math.min(4, words[words.length - 1].length())).toLowerCase());
            }
        }
        queries.addAll(List.of("cardoilogy", "nuerology", "pediatrcs", "dermatolgy")); // a few typos
        doctorIds = new ArrayList<>(ids);
        doctorQueries = new ArrayList<>(queries);

        List<String> medicineIds = new ArrayList<>();
        List<String> medicineQueries = new ArrayList<>();
        List<SyntheticData.Drug> drugs = SyntheticData.drugs(400);
        for (int i = 0; i < drugs.size(); i++) {
            String name = drugs.get(i).name();
            medicineQueries.add(name.substring(0, 3 + i % 3).toLowerCase());
            if (i % 5 == 0) {
                List<Map<String, Object>> found = Json.at(http.get("/api/medicines/suggest?limit=1&q=" + Http.enc(name), null).require().json());
                if (!found.isEmpty()) {
                    medicineIds.add((String) found.get(0).get("id"));
                }
            }
        }
        configure(null, true);
        return new Workload(http, new ArrayList<>(accounts), doctorIds, medicineIds, doctorQueries, medicineQueries);
    }

    /** Samples the application's own CPU, heap and cache counters around a run and attaches them to its result. */
    private Map<String, Object> measured(double warmupSeconds, double measureSeconds, Run run) throws Exception {
        List<Double> heap = new ArrayList<>();
        List<Double> threads = new ArrayList<>();
        Object[] edges = new Object[2];
        long[] edgeTimes = new long[2];
        AtomicBoolean stop = new AtomicBoolean();
        Thread sampler = new Thread(() -> {
            try {
                Thread.sleep((long) (warmupSeconds * 1000));
                edges[0] = http.get("/api/admin/metrics", admin).json();
                edgeTimes[0] = System.nanoTime();
                long end = System.nanoTime() + (long) (measureSeconds * 1e9);
                while (!stop.get() && System.nanoTime() < end - 600_000_000L) {
                    Thread.sleep(500);
                    Object m = http.get("/api/admin/metrics", admin).json();
                    heap.add(Json.num(m, "resources", "heapUsedMb"));
                    threads.add(Json.num(m, "resources", "threads"));
                }
                Thread.sleep(Math.max(0, (end - System.nanoTime()) / 1_000_000));
                edges[1] = http.get("/api/admin/metrics", admin).json();
                edgeTimes[1] = System.nanoTime();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        Map<String, Object> result = run.execute();
        stop.set(true);
        sampler.join(5000);
        if (edges[0] != null && edges[1] != null) {
            double wallMs = (edgeTimes[1] - edgeTimes[0]) / 1e6;
            double cpuMs = Json.num(edges[1], "resources", "processCpuTimeMs") - Json.num(edges[0], "resources", "processCpuTimeMs");
            double cores = Json.num(edges[1], "resources", "availableProcessors");
            double hits = Json.num(edges[1], "cache", "hits") - Json.num(edges[0], "cache", "hits");
            double misses = Json.num(edges[1], "cache", "misses") - Json.num(edges[0], "cache", "misses");
            Map<String, Object> resources = new LinkedHashMap<>();
            resources.put("appCpuCoresUsed", LatencyRecorder.round(cpuMs / wallMs));
            resources.put("appCpuPercentOfMachine", LatencyRecorder.round(cpuMs / wallMs / cores * 100));
            resources.put("machineCores", (int) cores);
            resources.put("heapUsedMbAvg", LatencyRecorder.round(heap.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            resources.put("heapUsedMbMax", LatencyRecorder.round(heap.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
            resources.put("threadsMax", (int) threads.stream().mapToDouble(Double::doubleValue).max().orElse(0));
            result.put("resources", resources);
            result.put("cacheHitRatio", hits + misses == 0 ? 0 : LatencyRecorder.round(hits / (hits + misses)));
        }
        return result;
    }

    @FunctionalInterface
    private interface Run {
        Map<String, Object> execute() throws Exception;
    }

    // ---- the experiments -----------------------------------------------------------------------

    public Map<String, Object> runLoadAndBaselines() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try (AppProcess app = AppProcess.start(appJar, mongoUri, outDir.resolve("app.log"))) {
            connect(app);
            stage(result, "dataGrowth", dataGrowth());
            int[] levels = quick ? new int[]{10, 40, 120} : new int[]{25, 100, 400, 1000};
            String[] names = quick ? new String[]{"low", "medium", "high"} : new String[]{"low", "medium", "high", "stress"};
            Workload workload = prepareWorkload(levels[levels.length - 1]);
            System.out.println("  [load] " + workload.accounts().size() + " patient logins ready, "
                    + doctorIds.size() + " doctors, " + Json.num(adminCall("GET", "/api/admin/metrics", null), "data", "patients")
                    + " patients in the database");
            double warmup = quick ? 3 : 6;
            double measure = quick ? 8 : 25;

            List<Map<String, Object>> levelResults = new ArrayList<>();
            for (int i = 0; i < levels.length; i++) {
                int users = levels[i];
                System.out.println("  [load] level " + names[i] + ": " + users + " concurrent users ...");
                Map<String, Object> level = measured(warmup, measure,
                        () -> workload.run(users, warmup, measure, THINK_MIN_MS, THINK_MAX_MS, null));
                level.put("level", names[i]);
                levelResults.add(level);
                stage(result, "levels", levelResults);
                System.out.println("      " + level.get("throughputRps") + " req/s, avg " + Json.num(level, "latencyMs", "avg")
                        + " ms, p95 " + Json.num(level, "latencyMs", "p95") + " ms, errors " + level.get("errorRate"));
            }

            int baselineUsers = levels[Math.min(1, levels.length - 1)];
            stage(result, "cacheBaseline", cacheBaseline(workload, baselineUsers));
            stage(result, "redirect", redirectLatency());
            stage(result, "searchBaseline", searchBaseline());
            stage(result, "rateLimiterUnderAbuse", abuseExperiment(workload, baselineUsers));
        }
        return result;
    }

    private Object dataGrowth() throws InterruptedException {
        System.out.println("  [growth] response time as the number of patients grows ...");
        int[] targets = quick ? new int[]{1_000, 5_000, 20_000} : new int[]{1_000, 10_000, 100_000};
        List<Map<String, Object>> rows = new ArrayList<>();
        configure(null, false);
        int have = 0;
        for (int target : targets) {
            long start = System.nanoTime();
            seedPatients(target - have);
            have = target;
            double seedSeconds = (System.nanoTime() - start) / 1e9;
            Object data = Json.at(adminCall("GET", "/api/admin/metrics", null), "data");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("patients", Json.num(data, "patients"));
            row.put("records", Json.num(data, "records"));
            row.put("appointments", Json.num(data, "appointments"));
            row.put("seedSeconds", LatencyRecorder.round(seedSeconds));
            double seconds = quick ? 2 : 4;
            if (rows.isEmpty()) {
                // The application has only just started. Exercise the same code paths, unmeasured, until
                // the JVM has compiled them; otherwise the smallest data size would look the slowest.
                double warm = quick ? 4 : 10;
                for (boolean cache : new boolean[]{false, true}) {
                    configure(cache, null);
                    closedLoop(16, warm, rng -> http.get("/api/patients/" + patientIds.get(rng.nextInt(patientIds.size()))
                            + (rng.nextBoolean() ? "/records" : ""), admin));
                }
                closedLoop(16, warm / 2, rng -> http.get(rng.nextBoolean() ? "/api/doctors/suggest?q=ca" : "/api/patients/lookup?email="
                        + Http.enc(patientEmails.get(rng.nextInt(patientEmails.size()))), admin));
            }
            for (boolean cache : new boolean[]{false, true}) {
                configure(cache, null);
                String mode = cache ? "cacheOn" : "cacheOff";
                row.put("recordAccess_" + mode, closedLoop(16, seconds, rng -> http.get("/api/patients/"
                        + patientIds.get(rng.nextInt(patientIds.size())) + "/records", admin)));
                row.put("profile_" + mode, closedLoop(16, seconds, rng -> http.get("/api/patients/"
                        + patientIds.get(rng.nextInt(patientIds.size())), admin)));
            }
            row.put("lookupByEmail", closedLoop(16, seconds, rng -> http.get("/api/patients/lookup?email="
                    + Http.enc(patientEmails.get(rng.nextInt(patientEmails.size()))), admin)));
            row.put("doctorSuggest", closedLoop(16, seconds, rng -> http.get("/api/doctors/suggest?q="
                    + (char) ('a' + rng.nextInt(26)) + (char) ('a' + rng.nextInt(26)), admin)));
            rows.add(row);
            System.out.println("      " + target + " patients: record access p95 "
                    + Json.num(row, "recordAccess_cacheOff", "latencyMs", "p95") + " ms from MongoDB, "
                    + Json.num(row, "recordAccess_cacheOn", "latencyMs", "p95") + " ms with the cache");
        }
        configure(true, true);
        return rows;
    }

    private Object cacheBaseline(Workload workload, int users) throws Exception {
        System.out.println("  [baseline] cache on vs off ...");
        Map<String, Object> result = new LinkedHashMap<>();
        double warmup = quick ? 2 : 4;
        double measure = quick ? 6 : 15;
        for (boolean cache : new boolean[]{true, false}) {
            configure(cache, null);
            result.put(cache ? "cacheOn" : "cacheOff", measured(warmup, measure,
                    () -> workload.run(users, warmup, measure, THINK_MIN_MS, THINK_MAX_MS, null)));
        }
        configure(true, null);
        result.put("users", users);
        return result;
    }

    private Object redirectLatency() throws InterruptedException {
        System.out.println("  [baseline] short-link redirects ...");
        configure(true, false);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            codes.add(Json.at(adminCall("POST", "/api/shorten", Map.of("target", "/#/bills/BIL" + i, "ttlHours", 1)), "code"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        double seconds = quick ? 2 : 5;
        for (boolean cache : new boolean[]{true, false}) {
            configure(cache, null);
            Map<String, Object> run = closedLoop(8, seconds, rng -> {
                Http.Response response = http.get("/s/" + codes.get(rng.nextInt(codes.size())), null);
                return response.status() == 302 && response.location() != null ? response : new Http.Response(500, "", null);
            });
            result.put(cache ? "cacheOn" : "cacheOff", run);
        }
        result.put("unknownCodeStatus", http.get("/s/AAAAAAAA", null).status());
        result.put("links", codes.size());
        configure(true, true);
        return result;
    }

    private Object searchBaseline() throws InterruptedException {
        System.out.println("  [baseline] search index vs database query ...");
        configure(true, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> queries = doctorQueries.stream().filter(q -> q.length() <= 4).toList();
        double seconds = quick ? 2 : 5;
        int[] extraDoctors = quick ? new int[]{0, 2_000} : new int[]{0, 5_000, 20_000};
        for (int extra : extraDoctors) {
            if (extra > 0) {
                adminCall("POST", "/api/admin/seed/doctors?count=" + extra, null);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("doctors", Json.num(adminCall("GET", "/api/admin/metrics", null), "data", "doctors"));
            row.put("index", closedLoop(16, seconds, rng -> http.get("/api/doctors/search?q="
                    + Http.enc(queries.get(rng.nextInt(queries.size()))), null)));
            row.put("database", closedLoop(16, seconds, rng -> http.get("/api/doctors/search?mode=db&q="
                    + Http.enc(queries.get(rng.nextInt(queries.size()))), null)));
            row.put("suggest", closedLoop(16, seconds, rng -> http.get("/api/doctors/suggest?q="
                    + Http.enc(queries.get(rng.nextInt(queries.size()))), null)));
            rows.add(row);
        }
        configure(true, true);
        return rows;
    }

    /**
     * Ordinary users keep using the portal while one client floods the most expensive endpoint.
     * Run once with the rate limiter off and once with it on.
     */
    private Object abuseExperiment(Workload workload, int users) throws Exception {
        System.out.println("  [baseline] rate limiter on vs off while one client floods the API ...");
        Map<String, Object> result = new LinkedHashMap<>();
        double warmup = quick ? 2 : 4;
        double measure = quick ? 6 : 15;
        int floodThreads = 64;
        // The flooding client gets its own HTTP client and connections, as a separate machine would have,
        // so that its traffic cannot slow the ordinary users down inside this load generator.
        Http floodHttp = new Http(http.baseUrl());
        for (boolean limiter : new boolean[]{false, true}) {
            configure(true, limiter);
            Map<String, Object>[] flood = new Map[1];
            Thread attacker = new Thread(() -> {
                try {
                    flood[0] = closedLoop(floodThreads, warmup + measure - 1, rng ->
                            floodHttp.send("GET", "/api/doctors/search?mode=db&limit=50&q=" + (char) ('a' + rng.nextInt(26)),
                                    null, "203.0.113.66", null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            attacker.start();
            Map<String, Object> normal = measured(warmup, measure,
                    () -> workload.run(users, warmup, measure, THINK_MIN_MS, THINK_MAX_MS, null));
            attacker.join();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("normalUsers", normal);
            entry.put("floodingClient", flood[0]);
            result.put(limiter ? "limiterOn" : "limiterOff", entry);
        }
        configure(true, true);
        result.put("normalUserCount", users);
        result.put("floodThreads", floodThreads);
        return result;
    }

    // ---- failure and scale-out timeline --------------------------------------------------------

    /**
     * Steady traffic for {@code seconds}; during it cache server n2 is killed, later restarted, and
     * finally a new server is added. Run for each replication factor in {@code replicaSettings}.
     */
    public Map<String, Object> runTimelines() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        int seconds = quick ? 24 : 45;
        int killAt = quick ? 6 : 12;
        int restartAt = quick ? 12 : 23;
        int addAt = quick ? 18 : 34;
        int users = quick ? 60 : 200;
        result.put("users", users);
        result.put("events", List.of(Map.of("second", killAt, "event", "cache server n2 killed"),
                Map.of("second", restartAt, "event", "n2 restarted (empty)"),
                Map.of("second", addAt, "event", "cache server n4 added")));
        for (int replicas : new int[]{2, 1}) {
            System.out.println("  [timeline] failure and scale-out with " + replicas + " replica(s) per key ...");
            try (AppProcess app = AppProcess.start(appJar, mongoUri, outDir.resolve("app-timeline-r" + replicas + ".log"),
                    "--hms.kv.replicas=" + replicas)) {
                connect(app);
                configure(null, false);
                seedPatients(quick ? 2_000 : 5_000);
                Workload workload = prepareWorkload(users);
                // Run the same traffic unmeasured first: it fills the cache and lets the newly started
                // application finish compiling its hot paths, so the timeline starts from a steady state.
                workload.run(users, 0, quick ? 6 : 25, THINK_MIN_MS, THINK_MAX_MS, null);

                Workload.Timeline timeline = new Workload.Timeline(seconds);
                double[] hitRatio = new double[seconds];
                double[] nodesUp = new double[seconds];
                Thread events = new Thread(() -> {
                    try {
                        long start = System.nanoTime();
                        Object previous = http.get("/api/admin/metrics", admin).json();
                        boolean killed = false;
                        boolean restarted = false;
                        boolean added = false;
                        for (int second = 0; second < seconds; second++) {
                            long wake = start + (second + 1) * 1_000_000_000L;
                            Thread.sleep(Math.max(0, (wake - System.nanoTime()) / 1_000_000));
                            Object now = http.get("/api/admin/metrics", admin).json();
                            double hits = Json.num(now, "cache", "hits") - Json.num(previous, "cache", "hits");
                            double misses = Json.num(now, "cache", "misses") - Json.num(previous, "cache", "misses");
                            hitRatio[second] = hits + misses == 0 ? 0 : hits / (hits + misses);
                            previous = now;
                            List<Map<String, Object>> nodes = Json.at(http.get("/api/admin/cluster", admin).json(), "nodes");
                            nodesUp[second] = nodes == null ? 0 : nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("up"))).count();
                            if (!killed && second + 1 >= killAt) {
                                http.post("/api/admin/cluster/nodes/n2/kill", admin, null);
                                killed = true;
                            } else if (!restarted && second + 1 >= restartAt) {
                                http.post("/api/admin/cluster/nodes/n2/restart", admin, null);
                                restarted = true;
                            } else if (!added && second + 1 >= addAt) {
                                http.post("/api/admin/cluster/nodes", admin, null);
                                added = true;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                events.setDaemon(true);
                events.start();
                Map<String, Object> overall = workload.run(users, 0, seconds, THINK_MIN_MS, THINK_MAX_MS, timeline);
                events.join(5000);

                List<Map<String, Object>> rows = new ArrayList<>();
                for (int s = 0; s < seconds; s++) {
                    Map<String, Object> latency = timeline.latency[s].summaryMs();
                    long requests = timeline.requests[s].sum();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("second", s + 1);
                    row.put("requests", requests);
                    row.put("errors", timeline.errors[s].sum());
                    row.put("avgMs", latency.get("avg"));
                    row.put("p95Ms", latency.get("p95"));
                    row.put("maxMs", latency.get("max"));
                    row.put("cacheHitRatio", LatencyRecorder.round(hitRatio[s]));
                    row.put("cacheServersUp", (int) nodesUp[s]);
                    rows.add(row);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("replicas", replicas);
                entry.put("overall", overall);
                entry.put("perSecond", rows);
                entry.put("clusterAfter", adminCall("GET", "/api/admin/cluster", null));
                stage(result, "replicas" + replicas, entry);
                System.out.println("      requests " + overall.get("requests") + ", error rate " + overall.get("errorRate")
                        + ", p95 " + Json.num(overall, "latencyMs", "p95") + " ms");
            }
        }
        return result;
    }
}

package com.hms.bench;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures and inspects the local MongoDB sharded cluster started by {@code scripts/sharded-cluster.ps1}.
 *
 * <pre>
 * hms-bench shard init-rs NAME:PORT[:config] ...   initiate one-member replica sets (config server and shards)
 * hms-bench shard setup NAME:PORT ...              register the shards with mongos and shard the collections
 * hms-bench shard add NAME:PORT                    add one more shard and wait until the balancer has used it
 * hms-bench shard status [LABEL]                   documents, data and chunks per shard; saved under LABEL
 * hms-bench shard explain                          which shards a targeted and a broadcast query reach
 * </pre>
 *
 * The router (mongos) listens on 27100. Snapshots are kept in results/sharding.json.
 */
public final class ShardingTool {

    /** The router; '-Dshard.uri=... -Dshard.db=...' points 'sample' and 'load' at an unsharded database for comparison. */
    public static final String ROUTER = System.getProperty("shard.uri", "mongodb://127.0.0.1:27100");
    public static final String DATABASE = System.getProperty("shard.db", "hms_sharded");

    /**
     * The collections that are sharded, with their shard keys. Everything a patient owns is spread by a hash of the
     * patient's id, so all of one patient's records, bills and prescriptions sit together on one shard. Appointments
     * are spread by doctor, because the rule that prevents double booking is a unique index on doctor, date and
     * time, and MongoDB can only enforce uniqueness on a sharded collection when the index starts with the shard key.
     */
    static final Map<String, Document> SHARD_KEYS = new LinkedHashMap<>();

    static {
        SHARD_KEYS.put("patients", new Document("_id", "hashed"));
        SHARD_KEYS.put("records", new Document("patientId", "hashed"));
        SHARD_KEYS.put("bills", new Document("patientId", "hashed"));
        SHARD_KEYS.put("prescriptions", new Document("patientId", "hashed"));
        SHARD_KEYS.put("appointments", new Document("doctorId", "hashed"));
        SHARD_KEYS.put("sessions", new Document("_id", "hashed"));
    }

    private final Path outDir;

    public ShardingTool(Path outDir) {
        this.outDir = outDir;
    }

    public void run(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("usage: shard <init-rs|setup|add|status|explain> ...");
        }
        List<String> rest = args.subList(1, args.size());
        switch (args.get(0)) {
            case "init-rs" -> rest.forEach(ShardingTool::initiateReplicaSet);
            case "setup" -> setup(rest);
            case "add" -> addShard(rest.get(0));
            case "status" -> status(rest.isEmpty() ? null : rest.get(0));
            case "explain" -> explain();
            case "sample" -> sample(Integer.parseInt(rest.get(0)));
            case "failure-check" -> failureCheck(rest.get(0), rest.get(1), rest.get(2));
            case "charts" -> {
                Charts charts = new Charts(Path.of("docs", "img"));
                charts.sharding(load());
                System.out.println("  wrote " + charts.written());
            }
            case "load" -> load(rest.get(0), Integer.parseInt(rest.get(1)), Integer.parseInt(rest.get(2)), rest.get(3));
            default -> throw new IllegalArgumentException("unknown shard command " + args.get(0));
        }
    }

    // ---- cluster set-up ------------------------------------------------------------------------

    /** NAME:PORT or NAME:PORT:config. Starting a member is the script's job; this makes it a (one-member) replica set. */
    static void initiateReplicaSet(String spec) {
        String[] parts = spec.split(":");
        String name = parts[0];
        String host = "127.0.0.1:" + parts[1];
        boolean config = parts.length > 2 && parts[2].equals("config");
        try (MongoClient client = MongoClients.create("mongodb://" + host + "/?directConnection=true")) {
            MongoDatabase admin = client.getDatabase("admin");
            Document conf = new Document("_id", name).append("members", List.of(new Document("_id", 0).append("host", host)));
            if (config) {
                conf.append("configsvr", true);
            }
            try {
                admin.runCommand(new Document("replSetInitiate", conf));
                System.out.println("  initiated replica set " + name + " on " + host);
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != 23) { // 23 = AlreadyInitialized
                    throw e;
                }
                System.out.println("  replica set " + name + " was already initiated");
            }
            long deadline = System.currentTimeMillis() + 60_000;
            while (!Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(name + " did not elect a primary within 60 s");
                }
                sleep(500);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void registerShard(MongoDatabase admin, String spec) {
        String[] parts = spec.split(":");
        Document reply = admin.runCommand(new Document("addShard", parts[0] + "/127.0.0.1:" + parts[1]).append("name", parts[0]));
        System.out.println("  shard " + reply.getString("shardAdded") + " registered with the router");
    }

    private void setup(List<String> shards) throws Exception {
        try (MongoClient client = MongoClients.create(ROUTER)) {
            MongoDatabase admin = client.getDatabase("admin");
            for (String shard : shards) {
                registerShard(admin, shard);
            }
            // Small ranges (1 MB instead of 128 MB) so that a laptop-sized data set is enough to see data move.
            client.getDatabase("config").getCollection("settings")
                    .updateOne(Filters.eq("_id", "chunksize"), Updates.set("value", 1), new UpdateOptions().upsert(true));
            admin.runCommand(new Document("enableSharding", DATABASE));
            for (Map.Entry<String, Document> e : SHARD_KEYS.entrySet()) {
                admin.runCommand(new Document("shardCollection", DATABASE + "." + e.getKey()).append("key", e.getValue()));
                System.out.println("  " + DATABASE + "." + e.getKey() + " sharded on " + e.getValue().toJson());
            }
            // Create the double-booking index now, to prove it is accepted on the sharded collection before the
            // application (which creates the same index at start-up) ever connects.
            client.getDatabase(DATABASE).getCollection("appointments").createIndex(
                    new Document("doctorId", 1).append("date", 1).append("time", 1),
                    new com.mongodb.client.model.IndexOptions().unique(true)
                            .partialFilterExpression(new Document("status", "BOOKED")));
            System.out.println("  unique index (doctorId, date, time) for BOOKED appointments accepted on the sharded collection");
        }
        status("after-setup");
    }

    private void addShard(String spec) throws Exception {
        initiateReplicaSet(spec);
        Map<String, Object> before = snapshot();
        Map<String, Object> timing = new LinkedHashMap<>();
        try (MongoClient client = MongoClients.create(ROUTER)) {
            long start = System.currentTimeMillis();
            timing.put("addedAtEpochMs", start);
            registerShard(client.getDatabase("admin"), spec);
            System.out.println("  waiting for the balancer to move data onto " + spec.split(":")[0] + " ...");
            long deadline = start + 600_000;
            while (System.currentTimeMillis() < deadline) {
                sleep(5000);
                boolean allBalanced = true;
                for (String coll : SHARD_KEYS.keySet()) {
                    Document st = client.getDatabase("admin").runCommand(new Document("balancerCollectionStatus", DATABASE + "." + coll));
                    allBalanced &= Boolean.TRUE.equals(st.getBoolean("balancerCompliant"));
                }
                long seconds = (System.currentTimeMillis() - start) / 1000;
                if (allBalanced) {
                    System.out.println("  every sharded collection balanced after " + seconds + " s");
                    timing.put("balancedAtEpochMs", System.currentTimeMillis());
                    timing.put("secondsToBalance", seconds);
                    break;
                }
                System.out.println("    still balancing (" + seconds + " s)");
            }
        }
        Map<String, Object> after = snapshot();
        Map<String, Object> saved = load();
        saved.put("before-add-shard", before);
        saved.put("after-add-shard", after);
        saved.put("add-shard-timing", timing);
        save(saved);
        print(after);
    }

    // ---- inspection ------------------------------------------------------------------------------

    /** Documents, data size and ranges (chunks) on each shard, for every sharded collection. */
    static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("takenAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        try (MongoClient client = MongoClients.create(ROUTER)) {
            MongoDatabase config = client.getDatabase("config");
            List<String> shards = new ArrayList<>();
            config.getCollection("shards").find().forEach(d -> shards.add(d.getString("_id")));
            result.put("shards", shards);
            Document db = config.getCollection("databases").find(Filters.eq("_id", DATABASE)).first();
            result.put("primaryShard", db == null ? null : db.getString("primary"));
            Map<String, Object> collections = new LinkedHashMap<>();
            for (String coll : SHARD_KEYS.keySet()) {
                Map<String, Object> perShard = new LinkedHashMap<>();
                for (String s : shards) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("documents", 0L);
                    row.put("megabytes", 0.0);
                    row.put("orphaned", 0L);
                    row.put("chunks", 0L);
                    perShard.put(s, row);
                }
                // Documents each shard owns according to the routing table. A shard that has just handed a range to
                // another one keeps its copy ("orphaned") for a while before deleting it; those are not counted.
                Document distribution = client.getDatabase("admin").aggregate(List.of(
                        new Document("$shardedDataDistribution", new Document()),
                        new Document("$match", new Document("ns", DATABASE + "." + coll)))).first();
                if (distribution != null) {
                    @SuppressWarnings("unchecked")
                    List<Document> rows = (List<Document>) distribution.get("shards");
                    for (Document d : rows) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) perShard.get(d.getString("shardName"));
                        if (row == null) {
                            continue;
                        }
                        row.put("documents", ((Number) d.get("numOwnedDocuments")).longValue());
                        row.put("megabytes", Math.round(((Number) d.get("ownedSizeBytes")).doubleValue() / 1048576.0 * 100) / 100.0);
                        row.put("orphaned", ((Number) d.get("numOrphanedDocs")).longValue());
                    }
                }
                Document meta = config.getCollection("collections").find(Filters.eq("_id", DATABASE + "." + coll)).first();
                if (meta != null) {
                    for (String s : shards) {
                        long chunks = config.getCollection("chunks").countDocuments(Filters.and(Filters.eq("uuid", meta.get("uuid")), Filters.eq("shard", s)));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) perShard.get(s);
                        row.put("chunks", chunks);
                    }
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("shardKey", SHARD_KEYS.get(coll).toJson());
                entry.put("perShard", perShard);
                collections.put(coll, entry);
            }
            result.put("collections", collections);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void print(Map<String, Object> snap) {
        List<String> shards = (List<String>) snap.get("shards");
        StringBuilder header = new StringBuilder(String.format("  %-14s", "collection"));
        shards.forEach(s -> header.append(String.format("%28s", s + " (docs / MB / ranges)")));
        System.out.println(header);
        Map<String, Object> collections = (Map<String, Object>) snap.get("collections");
        collections.forEach((coll, value) -> {
            Map<String, Object> perShard = (Map<String, Object>) ((Map<String, Object>) value).get("perShard");
            StringBuilder line = new StringBuilder(String.format("  %-14s", coll));
            for (String s : shards) {
                Map<String, Object> row = (Map<String, Object>) perShard.getOrDefault(s, Map.of());
                line.append(String.format("%28s", row.getOrDefault("documents", 0) + " / " + row.getOrDefault("megabytes", 0) + " / " + row.getOrDefault("chunks", 0)));
                long orphaned = ((Number) row.getOrDefault("orphaned", 0L)).longValue();
                if (orphaned > 0) {
                    line.append(" (+").append(orphaned).append(" moved, not yet deleted)");
                }
            }
            System.out.println(line);
        });
        System.out.println("  unsharded collections (doctors, medicines, users, ...) live on the primary shard: " + snap.get("primaryShard"));
    }

    private void status(String label) throws Exception {
        Map<String, Object> snap = snapshot();
        print(snap);
        if (label != null) {
            Map<String, Object> saved = load();
            saved.put(label, snap);
            save(saved);
            System.out.println("  saved as \"" + label + "\" in " + outDir.resolve("sharding.json"));
        }
    }

    /** Shows the router sending a query to one shard when it includes the shard key, and to all of them when not. */
    private void explain() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try (MongoClient client = MongoClients.create(ROUTER)) {
            MongoDatabase db = client.getDatabase(DATABASE);
            Document any = db.getCollection("records").find().first();
            if (any == null) {
                throw new IllegalStateException("no records yet: load some data first");
            }
            String patientId = any.getString("patientId");
            Object[][] queries = {
                    {"records of one patient (filter on the shard key)", "records", new Document("patientId", patientId)},
                    {"one patient by id (filter on the shard key)", "patients", new Document("_id", patientId)},
                    {"all diagnoses (no shard key in the filter)", "records", new Document("type", "DIAGNOSIS")},
                    {"bills over Rs 1,000 (no shard key in the filter)", "bills", new Document("payablePaise", new Document("$gt", 100_000))}};
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object[] q : queries) {
                Document plan = db.runCommand(new Document("explain", new Document("find", q[1]).append("filter", q[2]))
                        .append("verbosity", "queryPlanner"));
                Document winning = (Document) ((Document) plan.get("queryPlanner")).get("winningPlan");
                List<String> reached = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Document> shards = (List<Document>) winning.get("shards");
                if (shards != null) {
                    shards.forEach(s -> reached.add(s.getString("shardName")));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", q[0]);
                row.put("routerStage", winning.getString("stage"));
                row.put("shardsReached", reached);
                rows.add(row);
                System.out.printf("  %-52s %-14s %s%n", q[0], winning.getString("stage"), reached);
            }
            result.put("patientIdUsed", patientId);
            result.put("queries", rows);
        }
        Map<String, Object> saved = load();
        saved.put("explain", result);
        save(saved);
    }

    // ---- failure test ------------------------------------------------------------------------------

    /** The shard a query on {@code coll} with {@code filter} is routed to (the filter must contain the shard key). */
    private static String owner(MongoDatabase db, String coll, Document filter) {
        Document plan = db.runCommand(new Document("explain", new Document("find", coll).append("filter", filter))
                .append("verbosity", "queryPlanner"));
        @SuppressWarnings("unchecked")
        List<Document> shards = (List<Document>) ((Document) ((Document) plan.get("queryPlanner")).get("winningPlan")).get("shards");
        if (shards == null) {
            return "unsharded";
        }
        return shards.size() == 1 ? shards.get(0).getString("shardName") : "several";
    }

    /**
     * Before a failure test: picks {@code count} bulk-seeded patients at random and notes which shard holds each
     * one's patient document and medical records.
     */
    private void sample(int count) throws Exception {
        List<Map<String, Object>> sample = new ArrayList<>();
        try (MongoClient client = MongoClients.create(ROUTER)) {
            MongoDatabase db = client.getDatabase(DATABASE);
            for (Document p : db.getCollection("patients").aggregate(List.of(
                    new Document("$match", new Document("email", new Document("$regex", "@synthetic\\.test$"))),
                    new Document("$sample", new Document("size", count))))) {
                String id = p.getString("_id");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("email", p.getString("email"));
                row.put("patientShard", owner(db, "patients", new Document("_id", id)));
                row.put("recordsShard", owner(db, "records", new Document("patientId", id)));
                sample.add(row);
            }
        }
        Map<String, Long> perShard = new LinkedHashMap<>();
        sample.forEach(r -> perShard.merge((String) r.get("recordsShard"), 1L, Long::sum));
        System.out.println("  " + sample.size() + " patients picked; their records are on " + perShard);
        Map<String, Object> saved = load();
        saved.put("sample", sample);
        save(saved);
    }

    /**
     * During a failure test: signs in each sampled patient through the application and reads their profile and
     * records, with the application's cache switched off so that every read reaches MongoDB. Results are grouped by
     * whether the patient's data lives on the stopped shard. Also checks the functions whose data is not sharded.
     */
    @SuppressWarnings("unchecked")
    private void failureCheck(String url, String stoppedShard, String label) throws Exception {
        Http http = new Http(url);
        // Each sign-in stores a new session, placed by a hash of its random token; one that falls on the stopped
        // shard fails, so the administrator simply tries again.
        String admin = null;
        int adminAttempts = 0;
        while (admin == null && adminAttempts++ < 20) {
            Http.Response r = http.post("/api/auth/login", null, Map.of("email", "admin@hms.test", "password", "Admin@12345"));
            admin = r.ok() ? Json.at(r.json(), "token") : null;
        }
        if (admin == null) {
            throw new IllegalStateException("administrator could not sign in after 20 attempts");
        }
        http.post("/api/admin/config", admin, Map.of("cacheEnabled", false, "rateLimitEnabled", false)).require();
        List<Map<String, Object>> sample = (List<Map<String, Object>>) load().get("sample");
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        try {
            // Patients are checked in parallel: a request that needs the stopped shard waits for the router to give up
            // on it (15 s by default), and one after another that would take many minutes.
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(40);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Map<String, Object> patient : sample) {
                futures.add(pool.submit(() -> {
                    boolean affected = stoppedShard.equals(patient.get("patientShard")) || stoppedShard.equals(patient.get("recordsShard"));
                    String group = affected ? "data on " + stoppedShard : "data on other shards";
                    Map<String, Long> outcome = new LinkedHashMap<>();
                    outcome.put("patients", 1L);
                    // A patient whose sign-in fails tries again, up to five times, as a person would.
                    String token = null;
                    for (int attempt = 1; attempt <= 5 && token == null; attempt++) {
                        Http.Response login = http.post("/api/auth/login", null, Map.of("email", patient.get("email"), "password", "Password@123"));
                        if (login.ok()) {
                            token = Json.at(login.json(), "token");
                            outcome.put(attempt == 1 ? "signedInFirstTry" : "signedInAfterRetry", 1L);
                        }
                    }
                    if (token != null) {
                        long t0 = System.nanoTime();
                        boolean profile = http.get("/api/patients/" + patient.get("id"), token).ok();
                        long t1 = System.nanoTime();
                        boolean records = http.get("/api/patients/" + patient.get("id") + "/records", token).ok();
                        long t2 = System.nanoTime();
                        outcome.put("profileRead", profile ? 1L : 0L);
                        outcome.put("recordsRead", records ? 1L : 0L);
                        outcome.put("slowestReadMs", Math.max(t1 - t0, t2 - t1) / 1_000_000);
                    }
                    synchronized (groups) {
                        Map<String, Object> g = groups.computeIfAbsent(group, k -> new LinkedHashMap<>(Map.of(
                                "patients", 0L, "signedInFirstTry", 0L, "signedInAfterRetry", 0L,
                                "profileRead", 0L, "recordsRead", 0L, "slowestReadMs", 0L)));
                        outcome.forEach((key, value) -> g.merge(key, value, (a, b) -> key.equals("slowestReadMs")
                                ? Math.max((Long) a, (Long) b) : (Long) a + (Long) b));
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
            // Data that is not sharded lives on the primary shard; check it is still served.
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("doctor search", http.get("/api/doctors/search?q=" + Http.enc("cardio"), admin).status());
            Http.Response emergency = http.post("/api/emergency", null, Map.of(
                    "callerName", "Failure test", "phone", "9000000001", "zone", "Central", "severity", 2,
                    "description", "Synthetic shard failure test"));
            other.put("emergency request (no login)", emergency.status());
            if (emergency.ok()) {
                // Release the ambulance again so the test leaves the system as it found it.
                http.post("/api/emergency/" + Json.at(emergency.json(), "id") + "/resolve", admin, Map.of());
            }
            groups.put("unsharded functions (HTTP status)", other);
        } finally {
            http.post("/api/admin/config", admin, Map.of("cacheEnabled", true, "rateLimitEnabled", true));
        }
        groups.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        Map<String, Object> saved = load();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stoppedShard", stoppedShard);
        result.put("cacheEnabled", false);
        result.put("adminSignInAttempts", adminAttempts);
        result.put("groups", groups);
        saved.put(label, result);
        save(saved);
    }

    /**
     * Steady read load through the application: {@code threads} workers, each acting as one of the sampled patients,
     * repeatedly open their profile and records for {@code seconds}. The cache and the rate limiter are switched off
     * so that every request reaches the sharded database. Reports latency, errors and requests per second.
     */
    @SuppressWarnings("unchecked")
    private void load(String url, int seconds, int threads, String label) throws Exception {
        Http http = new Http(url);
        String admin = Json.at(http.post("/api/auth/login", null, Map.of("email", "admin@hms.test", "password", "Admin@12345")).require().json(), "token");
        http.post("/api/admin/config", admin, Map.of("cacheEnabled", false, "rateLimitEnabled", false)).require();
        List<Map<String, Object>> sample = (List<Map<String, Object>>) load().get("sample");
        String[] tokens = new String[sample.size()];
        java.util.stream.IntStream.range(0, sample.size()).parallel().forEach(i -> tokens[i] = Json.at(http.post("/api/auth/login", null,
                Map.of("email", sample.get(i).get("email"), "password", "Password@123")).require().json(), "token"));

        LatencyRecorder latency = new LatencyRecorder();
        java.util.concurrent.atomic.AtomicLongArray okPerSecond = new java.util.concurrent.atomic.AtomicLongArray(seconds + 2);
        java.util.concurrent.atomic.AtomicLongArray errorsPerSecond = new java.util.concurrent.atomic.AtomicLongArray(seconds + 2);
        Map<Integer, Long> statuses = new java.util.concurrent.ConcurrentHashMap<>();
        long start = System.nanoTime();
        long end = start + seconds * 1_000_000_000L;
        List<Thread> workers = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int worker = t;
                Thread thread = new Thread(() -> {
                    java.util.Random rng = new java.util.Random(worker);
                    while (System.nanoTime() < end) {
                        int i = rng.nextInt(sample.size());
                        String path = "/api/patients/" + sample.get(i).get("id") + (rng.nextBoolean() ? "/records" : "");
                        long t0 = System.nanoTime();
                        Http.Response response = http.get(path, tokens[i]);
                        long t1 = System.nanoTime();
                        int second = (int) Math.min(seconds + 1, (t1 - start) / 1_000_000_000L);
                        if (response.ok()) {
                            latency.add(t1 - t0);
                            okPerSecond.incrementAndGet(second);
                        } else {
                            errorsPerSecond.incrementAndGet(second);
                            statuses.merge(response.status(), 1L, Long::sum);
                        }
                    }
                });
                thread.start();
                workers.add(thread);
            }
            for (Thread w : workers) {
                w.join();
            }
        } finally {
            http.post("/api/admin/config", admin, Map.of("cacheEnabled", true, "rateLimitEnabled", true));
        }
        long ok = 0;
        long errors = 0;
        List<Long> okTimeline = new ArrayList<>();
        List<Long> errorTimeline = new ArrayList<>();
        for (int s = 0; s < seconds; s++) {
            ok += okPerSecond.get(s);
            errors += errorsPerSecond.get(s);
            okTimeline.add(okPerSecond.get(s));
            errorTimeline.add(errorsPerSecond.get(s));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startedAtEpochMs", System.currentTimeMillis() - (System.nanoTime() - start) / 1_000_000);
        result.put("seconds", seconds);
        result.put("threads", threads);
        result.put("patients", sample.size());
        result.put("requests", ok + errors);
        result.put("errors", errors);
        result.put("errorStatuses", new LinkedHashMap<>(statuses));
        result.put("throughput", Math.round((ok + errors) / (double) seconds));
        result.put("latencyMs", latency.summaryMs());
        result.put("okPerSecond", okTimeline);
        result.put("errorsPerSecond", errorTimeline);
        System.out.printf("  %d requests in %d s (%d/s), %d errors, latency %s%n", ok + errors, seconds,
                Math.round((ok + errors) / (double) seconds), errors, latency.summaryMs());
        Map<String, Object> saved = load();
        saved.put(label, result);
        save(saved);
    }

    // ---- results file ------------------------------------------------------------------------------

    private Map<String, Object> load() throws Exception {
        Path file = outDir.resolve("sharding.json");
        return Files.isRegularFile(file) ? Json.load(file) : new LinkedHashMap<>();
    }

    private void save(Map<String, Object> data) throws Exception {
        Json.save(outDir.resolve("sharding.json"), data);
    }
}

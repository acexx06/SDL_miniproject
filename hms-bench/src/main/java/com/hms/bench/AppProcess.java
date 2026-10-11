package com.hms.bench;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts a private copy of the HMS application for a benchmark run and stops it afterwards.
 *
 * The copy uses its own database ({@value #DATABASE}, dropped before each start) and its own
 * ports, so a benchmark never touches the data or the ports of a normally running instance.
 */
public final class AppProcess implements AutoCloseable {

    public static final String DATABASE = "hms_bench";
    public static final int PORT = 8095;
    public static final String ADMIN_EMAIL = "admin@hms.test";
    public static final String ADMIN_PASSWORD = "Admin@12345";

    private final Process process;
    private final Http http = new Http("http://127.0.0.1:" + PORT);
    private String adminToken;

    private AppProcess(Process process) {
        this.process = process;
    }

    public static Path findJar(String override) {
        List<Path> candidates = new ArrayList<>();
        if (override != null) {
            candidates.add(Path.of(override));
        }
        candidates.add(Path.of("hms-app", "target", "hms-app.jar"));
        candidates.add(Path.of("..", "hms-app", "target", "hms-app.jar"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("hms-app.jar not found; run 'mvn package' first or pass --app-jar <path>");
    }

    public static void dropDatabase(String mongoUri) {
        try (MongoClient client = MongoClients.create(mongoUri)) {
            client.getDatabase(DATABASE).drop();
        }
    }

    /** @param extraArgs additional {@code --property=value} arguments for the application */
    public static AppProcess start(Path jar, String mongoUri, Path logFile, String... extraArgs)
            throws IOException, InterruptedException {
        dropDatabase(mongoUri);
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g", "-jar", jar.toString(),
                "--server.port=" + PORT,
                "--spring.mongodb.uri=" + mongoUri + "/" + DATABASE,
                "--hms.trust-proxy=true",
                "--hms.kv.base-port=7301",
                "--hms.mock-sites.base-port=9301",
                "--hms.mock-sites.latency-ms=5",
                "--logging.level.root=WARN"));
        command.addAll(List.of(extraArgs));
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        File log = logFile.toFile();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
        AppProcess app = new AppProcess(process);
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
        app.awaitReady();
        return app;
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("the application exited during start-up; see results/app.log");
            }
            if (http.get("/api/health", null).ok()) {
                // Health answers before start-up work ends: wait for the demo accounts and the first crawl.
                if (adminToken == null) {
                    Http.Response login = http.post("/api/auth/login", null,
                            Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
                    adminToken = login.ok() ? Json.at(login.json(), "token") : null;
                }
                if (adminToken != null) {
                    Object crawl = http.get("/api/admin/crawl", adminToken).json();
                    Object plans = http.get("/api/insurance/plans", null).json();
                    if (Boolean.FALSE.equals(Json.at(crawl, "running")) && plans instanceof List<?> list && !list.isEmpty()) {
                        return;
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("the application did not become ready within 90 seconds");
    }

    public Http http() {
        return http;
    }

    public String adminToken() {
        return adminToken;
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }
}

package com.hms.bench;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point for the evaluation.
 *
 * <pre>
 * java -jar hms-bench/target/hms-bench.jar &lt;command&gt; [options]
 *
 *   demo        walk through every workflow against a running application (--url)
 *   components  measure the seven components in isolation      -> results/components.json
 *   load        load levels and baseline comparisons, end to end -> results/load.json
 *   timeline    kill, restart and add a cache server under load   -> results/timeline.json
 *   compare     the same load on the base system and the final scaled system -> results/compare.json
 *   report      draw the graphs and write docs/RESULTS.md from the JSON files
 *   all         components, load, timeline, report
 *
 *   --quick           shorter runs and smaller data sets (for trying things out)
 *   --url URL         application for 'demo' (default http://localhost:8088)
 *   --mongo URI       MongoDB server, without a database name (default mongodb://localhost:27017)
 *   --app-jar PATH    application jar to launch (default hms-app/target/hms-app.jar)
 *   --out DIR         where JSON results go (default results)
 *   --docs DIR        where the report and graphs go (default docs)
 * </pre>
 *
 * 'load' and 'timeline' start their own copy of the application on port 8095 with a scratch
 * database called hms_bench, so they never disturb a normally running instance or its data.
 */
public final class BenchMain {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println("usage: hms-bench <demo|components|load|timeline|compare|report|all> "
                    + "[--quick] [--url URL] [--mongo URI] [--app-jar PATH] [--out DIR] [--docs DIR]");
            System.out.println("       hms-bench shard <init-rs|setup|add|status|explain> ...   (MongoDB sharded cluster)");
            return;
        }
        String command = args[0];
        if (command.equals("shard")) {
            // Sharded-cluster set-up and inspection; see ShardingTool and scripts/sharded-cluster.ps1.
            new ShardingTool(Path.of("results")).run(List.of(args).subList(1, args.length));
            System.exit(0);
        }
        boolean quick = false;
        String url = "http://localhost:8088";
        String mongo = "mongodb://localhost:27017";
        String appJar = null;
        Path out = Path.of("results");
        Path docs = Path.of("docs");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--url" -> url = args[++i];
                case "--mongo" -> mongo = args[++i];
                case "--app-jar" -> appJar = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--docs" -> docs = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        long start = System.nanoTime();
        List<String> commands = command.equals("all") ? List.of("components", "load", "timeline", "compare", "report") : List.of(command);
        for (String current : commands) {
            System.out.println("== " + current + (quick ? " (quick)" : "") + " ==");
            switch (current) {
                case "demo" -> new Demo(url).run();
                case "components" -> save(out.resolve("components.json"), quick, new ComponentBench(quick, mongo).runAll());
                case "load" -> {
                    EndToEndBench bench = new EndToEndBench(quick, AppProcess.findJar(appJar), mongo, out)
                            .onCheckpoint(checkpointTo(out.resolve("load.partial.json"), quick));
                    save(out.resolve("load.json"), quick, bench.runLoadAndBaselines());
                    Files.deleteIfExists(out.resolve("load.partial.json"));
                }
                case "timeline" -> {
                    EndToEndBench bench = new EndToEndBench(quick, AppProcess.findJar(appJar), mongo, out)
                            .onCheckpoint(checkpointTo(out.resolve("timeline.partial.json"), quick));
                    save(out.resolve("timeline.json"), quick, bench.runTimelines());
                    Files.deleteIfExists(out.resolve("timeline.partial.json"));
                }
                case "compare" -> {
                    EndToEndBench bench = new EndToEndBench(quick, AppProcess.findJar(appJar), mongo, out)
                            .onCheckpoint(checkpointTo(out.resolve("compare.partial.json"), quick));
                    save(out.resolve("compare.json"), quick, bench.runComparison());
                    Files.deleteIfExists(out.resolve("compare.partial.json"));
                }
                case "report" -> report(out, docs);
                default -> throw new IllegalArgumentException("unknown command " + current);
            }
        }
        System.out.printf("Done in %.0f seconds.%n", (System.nanoTime() - start) / 1e9);
        System.exit(0); // HTTP client and pool threads would otherwise keep the JVM alive
    }

    private static void save(Path file, boolean quick, Map<String, Object> results) throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("environment", environment(quick));
        document.putAll(results);
        Json.save(file, document);
        System.out.println("  wrote " + file);
    }

    /**
     * Writes what has been measured so far to a ".partial.json" file after every stage. If a long run is
     * interrupted, 'report' uses that file, so the stages that did finish are not lost.
     */
    private static java.util.function.Consumer<Map<String, Object>> checkpointTo(Path file, boolean quick) {
        return partial -> {
            try {
                Map<String, Object> document = new LinkedHashMap<>();
                Map<String, Object> env = environment(quick);
                env.put("incomplete", true);
                document.put("environment", env);
                document.putAll(partial);
                Json.save(file, document);
            } catch (Exception e) {
                System.out.println("  (could not save partial results: " + e.getMessage() + ")");
            }
        };
    }

    private static Map<String, Object> environment(boolean quick) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("measuredAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        env.put("quickMode", quick);
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        env.put("logicalCores", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            env.put("memoryGb", Math.round(os.getTotalMemorySize() / 1073741824.0 * 10) / 10.0);
        }
        env.put("note", "Load generator, application, cache nodes and MongoDB all ran on this one machine.");
        String power = powerSource();
        if (power != null) {
            env.put("powerSource", power);
        }
        return env;
    }

    /** On Windows laptops, whether the machine was on battery (which throttles the processor) or on mains power. */
    private static String powerSource() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Battery).BatteryStatus")
                    .redirectErrorStream(true).start();
            String status = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return status.isEmpty() ? "mains power (no battery)" : status.equals("1") ? "battery" : "mains power";
        } catch (Exception e) {
            return null;
        }
    }

    private static void report(Path out, Path docs) throws Exception {
        Charts charts = new Charts(docs.resolve("img"));
        Map<String, Object> components = loadIfPresent(out.resolve("components.json"));
        Map<String, Object> load = loadIfPresent(out.resolve("load.json"));
        Map<String, Object> timeline = loadIfPresent(out.resolve("timeline.json"));
        if (components != null) {
            charts.components(components);
        }
        if (load != null) {
            charts.load(load);
        }
        if (timeline != null) {
            charts.timeline(timeline);
        }
        Map<String, Object> compare = loadIfPresent(out.resolve("compare.json"));
        if (compare != null) {
            charts.compare(compare);
            Files.writeString(docs.resolve("TEST_REPORT.md"), new CompareReport(compare).render());
            System.out.println("  wrote " + docs.resolve("TEST_REPORT.md"));
        }
        Map<String, Object> sharding = loadIfPresent(out.resolve("sharding.json"));
        if (sharding != null) {
            charts.sharding(sharding);
        }
        ArchitectureDiagram.write(docs.resolve("img").resolve("architecture.png"));
        System.out.println("  wrote " + charts.written().size() + " graphs and the architecture diagram to " + docs.resolve("img"));
        Path results = docs.resolve("RESULTS.md");
        Files.writeString(results, new ResultsReport(components, load, timeline).render());
        System.out.println("  wrote " + results);
    }

    private static Map<String, Object> loadIfPresent(Path file) throws Exception {
        if (Files.isRegularFile(file)) {
            return Json.load(file);
        }
        Path partial = file.resolveSibling(file.getFileName().toString().replace(".json", ".partial.json"));
        if (Files.isRegularFile(partial)) {
            System.out.println("  (using " + partial + ": that run did not finish, so some sections will be missing)");
            return Json.load(partial);
        }
        System.out.println("  (no " + file + " yet; skipping that part)");
        return null;
    }
}

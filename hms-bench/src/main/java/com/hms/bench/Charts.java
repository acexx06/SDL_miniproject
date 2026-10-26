package com.hms.bench;

import org.knowm.xchart.AnnotationLine;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.CategorySeries;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.AxesChartStyler;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the JSON written by the benchmarks into the PNG graphs used in the report.
 *
 * One visual language throughout: the proposed design is always blue, the baseline it is compared
 * with is always orange, and a third option (when there is one) is green. Each chart has a single
 * y-axis; anything needing a second scale gets its own chart.
 */
public final class Charts {

    private static final Color SURFACE = new Color(0xfcfcfb);
    private static final Color INK = new Color(0x0b0b0b);
    private static final Color INK_SECONDARY = new Color(0x52514e);
    private static final Color GRID = new Color(0xe1e0d9);
    private static final Color AXIS = new Color(0xc3c2b7);
    private static final Color REFERENCE = new Color(0x898781);
    static final Color BLUE = new Color(0x2a78d6);
    static final Color ORANGE = new Color(0xeb6834);
    static final Color GREEN = new Color(0x1baf7a);

    private static final Font TITLE = new Font("Segoe UI", Font.BOLD, 17);
    private static final Font AXIS_TITLE = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font TICKS = new Font("Segoe UI", Font.PLAIN, 12);

    /** One data series: its name, its colour, and y values (bars) or x/y points (lines). */
    private record Series(String name, Color color, List<? extends Number> x, List<? extends Number> y) {
        static Series bars(String name, Color color, List<? extends Number> values) {
            return new Series(name, color, null, values);
        }
    }

    private final Path dir;
    private final List<String> written = new ArrayList<>();

    public Charts(Path dir) {
        this.dir = dir;
    }

    public List<String> written() {
        return written;
    }

    private void style(AxesChartStyler styler, boolean legend) {
        styler.setChartBackgroundColor(SURFACE);
        styler.setPlotBackgroundColor(SURFACE);
        styler.setLegendBackgroundColor(SURFACE);
        styler.setLegendBorderColor(SURFACE);
        styler.setPlotBorderVisible(false);
        styler.setChartTitleBoxVisible(false);
        styler.setChartFontColor(INK);
        styler.setChartTitleFont(TITLE);
        styler.setAxisTitleFont(AXIS_TITLE);
        styler.setAxisTickLabelsFont(TICKS);
        styler.setAxisTickLabelsColor(INK_SECONDARY);
        styler.setAxisTickMarksColor(AXIS);
        styler.setLegendFont(AXIS_TITLE);
        styler.setPlotGridLinesColor(GRID);
        styler.setPlotGridLinesStroke(new BasicStroke(1f));
        styler.setPlotGridVerticalLinesVisible(false);
        styler.setLegendVisible(legend);
        styler.setLegendPosition(Styler.LegendPosition.OutsideS);
        styler.setLegendLayout(Styler.LegendLayout.Horizontal);
        styler.setAntiAlias(true);
        styler.setChartPadding(18);
    }

    private void save(Object chart, String name) throws IOException {
        Files.createDirectories(dir);
        String path = dir.resolve(name).toString();
        if (chart instanceof CategoryChart c) {
            BitmapEncoder.saveBitmap(c, path, BitmapEncoder.BitmapFormat.PNG);
        } else {
            BitmapEncoder.saveBitmap((XYChart) chart, path, BitmapEncoder.BitmapFormat.PNG);
        }
        written.add(name + ".png");
    }

    private void barChart(String file, String title, String xTitle, String yTitle, List<String> categories,
                          List<Series> series) throws IOException {
        CategoryChart chart = new CategoryChartBuilder().width(940).height(540).title(title)
                .xAxisTitle(xTitle).yAxisTitle(yTitle).build();
        style(chart.getStyler(), series.size() > 1);
        chart.getStyler().setAvailableSpaceFill(series.size() > 1 ? 0.62 : 0.42);
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setYAxisDecimalPattern("#,##0.##");
        // No value printed on each bar: small bars cannot hold one, and RESULTS.md has every number in a table.
        chart.getStyler().setLabelsVisible(false);
        chart.getStyler().setSeriesColors(series.stream().map(Series::color).toArray(Color[]::new));
        if (categories.size() > 6) {
            chart.getStyler().setXAxisLabelRotation(20);
        }
        for (Series s : series) {
            CategorySeries added = chart.addSeries(s.name(), categories, s.y());
            added.setFillColor(s.color());
        }
        save(chart, file);
    }

    private void lineChart(String file, String title, String xTitle, String yTitle, boolean logX, boolean logY,
                           List<Series> series, List<Double> verticalMarkers) throws IOException {
        XYChart chart = new XYChartBuilder().width(940).height(540).title(title).xAxisTitle(xTitle).yAxisTitle(yTitle).build();
        style(chart.getStyler(), series.size() > 1);
        chart.getStyler().setXAxisLogarithmic(logX);
        chart.getStyler().setYAxisLogarithmic(logY);
        chart.getStyler().setMarkerSize(8);
        chart.getStyler().setXAxisDecimalPattern("#,##0.##");
        chart.getStyler().setYAxisDecimalPattern("#,##0.##");
        if (!logY) {
            chart.getStyler().setYAxisMin(0.0);
        }
        for (Series s : series) {
            XYSeries added = chart.addSeries(s.name(), s.x(), s.y());
            added.setLineColor(s.color());
            added.setMarkerColor(s.color());
            added.setLineWidth(2f);
            added.setMarker(s.x().size() > 30 ? SeriesMarkers.NONE : SeriesMarkers.CIRCLE);
            if (s.color().equals(REFERENCE)) {
                added.setMarker(SeriesMarkers.NONE);
                added.setLineWidth(1.5f);
            }
        }
        if (verticalMarkers != null) {
            chart.getStyler().setAnnotationLineColor(REFERENCE);
            chart.getStyler().setAnnotationLineStroke(new BasicStroke(1.5f));
            for (double x : verticalMarkers) {
                chart.addAnnotation(new AnnotationLine(x, true, false));
            }
        }
        save(chart, file);
    }

    private static List<Double> column(List<Map<String, Object>> rows, Object... path) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            values.add(Json.num(row, path));
        }
        return values;
    }

    // ---- component charts ----------------------------------------------------------------------

    public void components(Map<String, Object> c) throws IOException {
        List<Map<String, Object>> offered = Json.at(c, "rateLimiter", "rejectionVsOfferedLoad");
        List<Double> multiples = column(offered, "offeredLoadMultiple");
        lineChart("ratelimit_rejection", "Rate limiter: share of requests rejected as a client exceeds its limit",
                "Request rate as a multiple of the allowed rate (1 = exactly at the limit)", "Requests rejected (%)", false, false,
                List.of(new Series("Token bucket (measured)", BLUE, multiples, percent(column(offered, "tokenBucket"))),
                        new Series("Least any limiter could reject", REFERENCE, multiples, percent(column(offered, "expectedRejection")))), null);

        Map<String, Object> boundary = Json.at(c, "rateLimiter", "admittedIn20msAcrossWindowBoundary");
        barChart("ratelimit_boundary", "Requests admitted in a 20 ms burst across a window boundary (limit: 10 per second)",
                "Rate limiting algorithm", "Requests admitted",
                List.of("Token bucket (used)", "Sliding window log", "Fixed window (baseline)"),
                List.of(Series.bars("Token bucket", BLUE, nullable(Json.num(boundary, "tokenBucket"), Json.num(boundary, "slidingWindowLog"), null)),
                        Series.bars("Fixed window (baseline)", ORANGE, nullable(null, null, Json.num(boundary, "fixedWindow")))));

        List<Map<String, Object>> balance = Json.at(c, "consistentHashing", "balanceVsVirtualNodes");
        lineChart("hash_balance", "Consistent hashing: load imbalance across 5 servers falls as virtual nodes are added",
                "Virtual nodes per server (log scale)", "Std. deviation of keys per server (% of mean)", true, false,
                List.of(new Series("Consistent hash ring", BLUE, column(balance, "virtualNodes"), column(balance, "stdDevPercentOfMean"))), null);

        List<Map<String, Object>> remap = Json.at(c, "consistentHashing", "keysMovedOnMembershipChange");
        barChart("hash_remap", "Keys that change server when the number of servers changes",
                "Membership change", "Keys moved (%)", remap.stream().map(r -> (String) r.get("change")).toList(),
                List.of(Series.bars("Consistent hashing", BLUE, column(remap, "consistentHashingPercent")),
                        Series.bars("hash mod N (baseline)", ORANGE, column(remap, "moduloPercent"))));

        List<Map<String, Object>> kv = Json.at(c, "keyValueStore", "networkedNode");
        List<Map<String, Object>> scan = Json.at(c, "keyValueStore", "linearScanBaseline");
        List<Map<String, Object>> mongo = Json.at(c, "keyValueStore", "mongoFindByIdBaseline");
        List<Series> kvSeries = new ArrayList<>(List.of(
                new Series("Key-value node (over TCP)", BLUE, column(kv, "keys"), column(kv, "getMicros", "avg")),
                new Series("Unindexed list scan (baseline)", ORANGE, column(scan, "keys"), column(scan, "lookupMicros", "avg"))));
        if (!mongo.isEmpty() && mongo.get(0).containsKey("keys")) {
            kvSeries.add(new Series("MongoDB find by _id (same machine)", GREEN, column(mongo, "keys"), column(mongo, "findByIdMicros", "avg")));
        }
        lineChart("kv_lookup", "Key-value store: lookup latency as the number of stored keys grows",
                "Keys stored (log scale)", "Average lookup latency in microseconds (log scale)", true, true, kvSeries, null);

        List<Map<String, Object>> ac = Json.at(c, "autocomplete", "latencyVsDictionarySize");
        lineChart("autocomplete_latency", "Autocomplete: time to produce 5 suggestions as the dictionary grows",
                "Entries in the dictionary (log scale)", "Average latency in microseconds (log scale)", true, true,
                List.of(new Series("Trie with cached top-k", BLUE, column(ac, "entries"), column(ac, "trieMicros", "avg")),
                        new Series("Linear scan (baseline)", ORANGE, column(ac, "entries"), column(ac, "linearScanMicros", "avg"))), null);

        List<Map<String, Object>> useful = Json.at(c, "autocomplete", "targetInTop5");
        Map<String, Object> typo = Json.at(c, "autocomplete", "targetInTop5WithOneTypo");
        List<String> categories = new ArrayList<>(useful.stream().map(u -> ((Number) u.get("lettersTyped")).intValue() + " letters typed").toList());
        categories.add("6 letters, one typo");
        List<Double> trie = column(useful, "rankedTriePercent");
        trie.add(Json.num(typo, "typoTolerantTriePercent"));
        List<Double> alphabetical = column(useful, "alphabeticalBaselinePercent");
        alphabetical.add(Json.num(typo, "alphabeticalBaselinePercent"));
        barChart("autocomplete_accuracy", "Autocomplete accuracy: how often the wanted entry is among the 5 suggestions",
                "What the user has typed", "Wanted entry in top 5 (%)", categories,
                List.of(Series.bars("Popularity-ranked trie", BLUE, trie), Series.bars("Alphabetical matches (baseline)", ORANGE, alphabetical)));

        List<Map<String, Object>> topology = Json.at(c, "clusterTopology", "results");
        List<String> events = topology.stream().map(t -> (String) t.get("event")).distinct().toList();
        barChart("topology_hitratio", "Cache entries still found after the cluster changes",
                "Change to a 4-server cache cluster", "Lookups still answered from the cache (%)", events,
                List.of(Series.bars("Consistent hashing, 1 copy", BLUE, topologyColumn(topology, events, "consistent hashing", 1)),
                        Series.bars("hash mod N, 1 copy (baseline)", ORANGE, topologyColumn(topology, events, "modulo (baseline)", 1)),
                        Series.bars("Consistent hashing, 2 copies", GREEN, topologyColumn(topology, events, "consistent hashing", 2))));

        List<Map<String, Object>> crawl = Json.<List<Map<String, Object>>>at(c, "crawler", "runs").stream()
                .filter(r -> Json.num(r, "perHostDelayMs") == 0).toList();
        barChart("crawler_throughput", "Web crawler: pages fetched per second by number of worker threads",
                "Worker threads (simulated sites answer in " + (int) Json.num(c, "crawler", "simulatedServerLatencyMs") + " ms)",
                "Pages per second", crawl.stream().map(r -> ((Number) r.get("workers")).intValue() == 1
                        ? "1 (sequential baseline)" : String.valueOf(((Number) r.get("workers")).intValue())).toList(),
                List.of(Series.bars("Pages per second", BLUE, column(crawl, "pagesPerSecond"))));
    }

    private static List<Double> percent(List<Double> fractions) {
        return fractions.stream().map(f -> LatencyRecorder.round(f * 100)).toList();
    }

    private static List<Double> nullable(Double... values) {
        return new ArrayList<>(java.util.Arrays.asList(values));
    }

    private static List<Double> topologyColumn(List<Map<String, Object>> rows, List<String> events, String routing, int replicas) {
        List<Double> values = new ArrayList<>();
        for (String event : events) {
            values.add(rows.stream().filter(r -> event.equals(r.get("event")) && routing.equals(r.get("routing"))
                    && Json.num(r, "replicas") == replicas).findFirst().map(r -> Json.num(r, "cacheHitPercentAfter")).orElse(0.0));
        }
        return values;
    }

    // ---- end-to-end charts ---------------------------------------------------------------------

    public void load(Map<String, Object> l) throws IOException {
        List<Map<String, Object>> levels = Json.at(l, "levels");
        if (levels != null) {
        List<String> names = levels.stream().map(v -> v.get("level") + " (" + ((Number) v.get("users")).intValue() + " users)").toList();
        barChart("load_latency", "Response time of the mixed workload at each load level",
                "Load level (concurrent users)", "Response time (ms)", names,
                List.of(Series.bars("Average", BLUE, column(levels, "latencyMs", "avg")),
                        Series.bars("95th percentile", ORANGE, column(levels, "latencyMs", "p95"))));
        barChart("load_throughput", "Throughput of the mixed workload at each load level",
                "Load level (concurrent users)", "Requests per second", names,
                List.of(Series.bars("Requests per second", BLUE, column(levels, "throughputRps"))));
        }

        if (Json.at(l, "cacheBaseline") != null) {
        List<String> ops = List.of("MY_RECORDS", "MY_APPOINTMENTS", "MY_BILLS", "MY_PROFILE", "VIEW_SLOTS");
        Object on = Json.at(l, "cacheBaseline", "cacheOn", "perOperation");
        Object off = Json.at(l, "cacheBaseline", "cacheOff", "perOperation");
        barChart("cache_baseline", "Read operations with and without the key-value cache ("
                        + (int) Json.num(l, "cacheBaseline", "users") + " concurrent users)",
                "Operation", "Average response time (ms)",
                List.of("My records", "My appointments", "My bills", "My profile", "Doctor slots"),
                List.of(Series.bars("With key-value cache", BLUE, ops.stream().map(o -> Json.num(on, o, "avg")).toList()),
                        Series.bars("MongoDB only (baseline)", ORANGE, ops.stream().map(o -> Json.num(off, o, "avg")).toList())));
        }

        List<Map<String, Object>> search = Json.at(l, "searchBaseline");
        if (search != null) {
        barChart("search_baseline", "Doctor search: autocomplete index against a database query, 16 clients",
                "Doctors in the system", "Average response time (ms)",
                search.stream().map(s -> String.format("%,d doctors", (long) Json.num(s, "doctors"))).toList(),
                List.of(Series.bars("Autocomplete index", BLUE, column(search, "index", "latencyMs", "avg")),
                        Series.bars("MongoDB regex query (baseline)", ORANGE, column(search, "database", "latencyMs", "avg"))));
        }

        if (Json.at(l, "rateLimiterUnderAbuse") != null) {
        Object abuseOn = Json.at(l, "rateLimiterUnderAbuse", "limiterOn", "normalUsers", "latencyMs");
        Object abuseOff = Json.at(l, "rateLimiterUnderAbuse", "limiterOff", "normalUsers", "latencyMs");
        barChart("ratelimit_abuse", "Ordinary users' response time while one client floods the API",
                "Response time of the " + (int) Json.num(l, "rateLimiterUnderAbuse", "normalUserCount") + " ordinary users",
                "Response time (ms)", List.of("Average", "95th percentile", "99th percentile"),
                List.of(Series.bars("Rate limiter on", BLUE, List.of(Json.num(abuseOn, "avg"), Json.num(abuseOn, "p95"), Json.num(abuseOn, "p99"))),
                        Series.bars("Rate limiter off (baseline)", ORANGE, List.of(Json.num(abuseOff, "avg"), Json.num(abuseOff, "p95"), Json.num(abuseOff, "p99")))));
        }

        List<Map<String, Object>> growth = Json.at(l, "dataGrowth");
        if (growth != null) {
        lineChart("growth_latency", "Patient record access as the number of patients grows (16 clients)",
                "Patients in the database (log scale)", "95th percentile response time (ms)", true, false,
                List.of(new Series("With key-value cache", BLUE, column(growth, "patients"), column(growth, "recordAccess_cacheOn", "latencyMs", "p95")),
                        new Series("MongoDB only (baseline)", ORANGE, column(growth, "patients"), column(growth, "recordAccess_cacheOff", "latencyMs", "p95"))), null);
        }
    }

    /** Graphs for docs/TEST_REPORT.md: the base system against the final scaled system. */
    public void compare(Map<String, Object> c) throws IOException {
        List<Map<String, Object>> base = Json.at(c, "base");
        List<Map<String, Object>> scaled = Json.at(c, "scaled");
        if (base == null || scaled == null || base.isEmpty() || scaled.isEmpty()) {
            return;
        }
        int levels = Math.min(base.size(), scaled.size());
        List<Map<String, Object>> b = base.subList(0, levels);
        List<Map<String, Object>> s = scaled.subList(0, levels);
        List<String> names = b.stream().map(v -> v.get("level") + " (" + ((Number) v.get("users")).intValue() + " users)").toList();
        barChart("compare_p95", "95th percentile response time: base system against final scaled system",
                "Load level (concurrent users)", "95th percentile response time (ms)", names,
                List.of(Series.bars("Final scaled system", BLUE, column(s, "latencyMs", "p95")),
                        Series.bars("Base system (baseline)", ORANGE, column(b, "latencyMs", "p95"))));
        barChart("compare_throughput", "Requests served per second: base system against final scaled system",
                "Load level (concurrent users)", "Requests per second", names,
                List.of(Series.bars("Final scaled system", BLUE, column(s, "throughputRps")),
                        Series.bars("Base system (baseline)", ORANGE, column(b, "throughputRps"))));
        Map<String, Object> topBase = b.get(levels - 1);
        Map<String, Object> topScaled = s.get(levels - 1);
        List<String> ops = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> op : CompareReport.OPERATIONS.entrySet()) {
            if (List.of("LOGIN", "PAY_BILL", "EMERGENCY").contains(op.getKey())) {
                continue; // dominated by password hashing or writes, which both systems do the same way
            }
            if (Json.at(topBase, "perOperation", op.getKey()) != null && Json.at(topScaled, "perOperation", op.getKey()) != null) {
                ops.add(op.getKey());
                labels.add(op.getValue());
            }
        }
        barChart("compare_ops", "Average response time per operation at " + ((Number) topBase.get("users")).intValue() + " users",
                "Operation", "Average response time (ms)", labels,
                List.of(Series.bars("Final scaled system", BLUE, ops.stream().map(o -> Json.num(topScaled, "perOperation", o, "avg")).toList()),
                        Series.bars("Base system (baseline)", ORANGE, ops.stream().map(o -> Json.num(topBase, "perOperation", o, "avg")).toList())));
    }

    /** Graphs for docs/SHARDING.md, from results/sharding.json. */
    public void sharding(Map<String, Object> s) throws IOException {
        Map<String, Object> before = Json.at(s, "before-add-shard", "collections");
        Map<String, Object> after = Json.at(s, "after-add-shard", "collections");
        if (before != null && after != null) {
            List<String> shards = Json.at(s, "after-add-shard", "shards");
            List<Double> beforeCounts = new ArrayList<>();
            List<Double> afterCounts = new ArrayList<>();
            for (String shard : shards) {
                Object row = Json.at(before, "patients", "perShard", shard);
                beforeCounts.add(row == null ? null : Json.num(row, "documents"));
                afterCounts.add(Json.num(after, "patients", "perShard", shard, "documents"));
            }
            barChart("sharding_distribution", "Patients owned by each shard, before and after adding a third shard",
                    "Shard", "Patient documents", shards,
                    List.of(Series.bars("After adding shard3", BLUE, afterCounts),
                            Series.bars("Before: two shards", ORANGE, beforeCounts)));
        }
        Map<String, Object> during = Json.at(s, "load-while-adding-shard3");
        Map<String, Object> timing = Json.at(s, "add-shard-timing");
        if (during != null && timing != null) {
            List<Number> perSecond = Json.at(during, "okPerSecond");
            List<Double> seconds = new ArrayList<>();
            for (int i = 0; i < perSecond.size(); i++) {
                seconds.add((double) i);
            }
            double start = Json.num(during, "startedAtEpochMs");
            double added = Math.round((Json.num(timing, "addedAtEpochMs") - start) / 1000.0);
            double balanced = Math.round((Json.num(timing, "balancedAtEpochMs") - start) / 1000.0);
            lineChart("sharding_timeline", "Requests served per second while a shard is added and data moves ("
                            + (long) Json.num(during, "errors") + " errors)",
                    "Seconds (vertical lines: shard3 added at " + (int) added + " s, balanced at " + (int) balanced + " s)",
                    "Successful requests per second", false, false,
                    List.of(new Series("Requests per second", BLUE, seconds, perSecond)), List.of(added, balanced));
        }
    }

    public void timeline(Map<String, Object> t) throws IOException {
        List<Map<String, Object>> two = Json.at(t, "replicas2", "perSecond");
        List<Map<String, Object>> one = Json.at(t, "replicas1", "perSecond");
        if (two == null || one == null) {
            return; // the comparison needs both runs
        }
        List<Map<String, Object>> events = Json.at(t, "events");
        List<Double> markers = column(events, "second");
        StringBuilder when = new StringBuilder();
        for (Map<String, Object> e : events) {
            when.append(when.length() == 0 ? "" : ", ").append(e.get("event")).append(" at ").append(((Number) e.get("second")).intValue()).append(" s");
        }
        String xTitle = "Seconds (vertical lines: " + when + ")";
        List<Double> seconds = column(two, "second");
        lineChart("timeline_hitratio", "Cache hit ratio while a cache server fails, returns, and another is added",
                xTitle, "Cache hit ratio (%)", false, false,
                List.of(new Series("2 copies of each key", BLUE, seconds, percent(column(two, "cacheHitRatio"))),
                        new Series("1 copy of each key", ORANGE, seconds, percent(column(one, "cacheHitRatio")))), markers);
        lineChart("timeline_p95", "95th percentile response time during the same events",
                xTitle, "95th percentile response time (ms)", false, false,
                List.of(new Series("2 copies of each key", BLUE, seconds, column(two, "p95Ms")),
                        new Series("1 copy of each key", ORANGE, seconds, column(one, "p95Ms"))), markers);
        lineChart("timeline_throughput", "Requests served per second during the same events (" + (int) Json.num(t, "users") + " users)",
                xTitle, "Requests per second", false, false,
                List.of(new Series("2 copies of each key", BLUE, seconds, column(two, "requests")),
                        new Series("1 copy of each key", ORANGE, seconds, column(one, "requests"))), markers);
    }
}

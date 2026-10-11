package com.hms.sim;

import com.hms.components.crawler.HtmlParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Two small simulated websites for the crawler to work on, standing in for external sources:
 *
 * <ul>
 *   <li><b>MedInfo</b>: a drug-information site with paginated listings, category pages and one
 *       page per drug.</li>
 *   <li><b>InsureCo</b>: insurance providers and their plans, with coverage percentages.</li>
 * </ul>
 *
 * The sites deliberately include what makes real crawling awkward: a robots.txt with disallowed
 * areas, an endless "calendar" trap, tracking parameters, the same content at two URLs, broken
 * links, pages that fail on the first request, and an artificial response delay.
 */
public final class MockSites implements AutoCloseable {

    private static final int PAGE_SIZE = 25;
    private static final String FOOTER = "<footer>Synthetic data generated for a student project. Not medical or insurance advice.</footer>";

    private final HttpServer medInfo;
    private final HttpServer insure;
    private final ExecutorService pool;
    private final int latencyMs;
    private final List<SyntheticData.Drug> drugs;
    private final List<SyntheticData.Plan> plans = SyntheticData.plans();
    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger disallowedHits = new AtomicInteger();

    public MockSites(int medInfoPort, int insurePort, int latencyMs, int drugCount) throws IOException {
        this.latencyMs = latencyMs;
        this.drugs = SyntheticData.drugs(drugCount);
        this.pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-site");
            thread.setDaemon(true);
            return thread;
        });
        medInfo = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), medInfoPort), 256);
        medInfo.createContext("/", exchange -> serve(exchange, true));
        medInfo.setExecutor(pool);
        insure = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), insurePort), 256);
        insure.createContext("/", exchange -> serve(exchange, false));
        insure.setExecutor(pool);
    }

    public void start() {
        medInfo.start();
        insure.start();
    }

    @Override
    public void close() {
        medInfo.stop(0);
        insure.stop(0);
        pool.shutdownNow();
    }

    public String medInfoUrl() {
        return "http://127.0.0.1:" + medInfo.getAddress().getPort() + "/";
    }

    public String insureUrl() {
        return "http://127.0.0.1:" + insure.getAddress().getPort() + "/";
    }

    public List<String> seeds() {
        return List.of(medInfoUrl(), insureUrl());
    }

    public int drugCount() {
        return drugs.size();
    }

    public int planCount() {
        return plans.size();
    }

    public int requestCount() {
        return requests.get();
    }

    /** Requests received for paths robots.txt disallows; stays 0 if the crawler behaves. */
    public int disallowedHits() {
        return disallowedHits.get();
    }

    private record Reply(int status, String contentType, String body) {
        static Reply html(String title, String body) {
            return new Reply(200, "text/html; charset=utf-8", "<!doctype html><html><head><title>"
                    + HtmlParser.escape(title) + "</title></head><body>" + body + FOOTER + "</body></html>");
        }

        static Reply notFound() {
            return new Reply(404, "text/html; charset=utf-8", "<html><body><h1>Not found</h1></body></html>");
        }
    }

    private void serve(HttpExchange exchange, boolean isMedInfo) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            if (latencyMs > 0) {
                Thread.sleep(latencyMs);
            }
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            Reply reply = isMedInfo ? medInfoPage(path, query) : insurePage(path);
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType);
            exchange.sendResponseHeaders(reply.status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String link(String href, String text) {
        return "<a href=\"" + href + "\">" + HtmlParser.escape(text) + "</a>";
    }

    // ---- MedInfo ---------------------------------------------------------------------------

    private Reply medInfoPage(String path, String query) {
        if (path.equals("/robots.txt")) {
            return new Reply(200, "text/plain", "User-agent: *\nDisallow: /private/\nDisallow: /calendar\n");
        }
        if (path.startsWith("/private/") || path.startsWith("/calendar")) {
            disallowedHits.incrementAndGet();
            int day = query != null && query.startsWith("day=") ? Integer.parseInt(query.substring(4)) : 1;
            return Reply.html("Calendar", "<p>Day " + day + "</p>" + link("/calendar?day=" + (day + 1), "next day"));
        }
        if (path.equals("/")) {
            return Reply.html("MedInfo", "<h1>MedInfo drug reference</h1><nav>" + link("/drugs?page=1", "All drugs")
                    + " " + link("/about", "About") + " " + link("/private/staff", "Staff area")
                    + " " + link("/calendar?day=1", "Calendar") + " " + link("/#top", "Top") + "</nav>");
        }
        if (path.equals("/about")) {
            return Reply.html("About MedInfo", "<p>A simulated reference site.</p>" + link("/", "Home"));
        }
        if (path.equals("/drugs")) {
            return drugListPage(query);
        }
        if (path.startsWith("/category/")) {
            String category = path.substring("/category/".length());
            String items = drugs.stream().filter(d -> SyntheticData.slug(d.category()).equals(category))
                    .map(d -> "<li>" + link("/drug/" + d.slug(), d.name()) + "</li>").collect(Collectors.joining());
            return items.isEmpty() ? Reply.notFound() : Reply.html("Category " + category, "<ul>" + items + "</ul>");
        }
        if (path.startsWith("/drug/") || path.startsWith("/print/")) {
            String slug = path.substring(path.indexOf('/', 1) + 1);
            for (int i = 0; i < drugs.size(); i++) {
                if (drugs.get(i).slug().equals(slug)) {
                    // Every 20th drug page fails the first time it is requested.
                    if (i % 20 == 0 && path.startsWith("/drug/") && failedOnce.add(slug)) {
                        return new Reply(503, "text/plain", "temporarily unavailable");
                    }
                    return drugPage(i);
                }
            }
        }
        return Reply.notFound();
    }

    private Reply drugListPage(String query) {
        int pages = (drugs.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("page=")) {
                    page = Integer.parseInt(pair.substring(5));
                }
            }
        }
        if (page < 1 || page > pages) {
            return Reply.notFound();
        }
        StringBuilder body = new StringBuilder("<h1>Drugs, page " + page + " of " + pages + "</h1><ul>");
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(drugs.size(), page * PAGE_SIZE); i++) {
            SyntheticData.Drug drug = drugs.get(i);
            // Same page, different spelling of the URL: the crawler should fetch it only once.
            String href = "/drug/" + drug.slug() + (i % 3 == 0 ? "?utm_source=listing" : "");
            body.append("<li>").append(link(href, drug.name())).append("</li>");
        }
        body.append("</ul>");
        if (page < pages) {
            body.append(link("/drugs?page=" + (page + 1), "Next page"));
        }
        return Reply.html("Drugs page " + page, body.toString());
    }

    private Reply drugPage(int index) {
        SyntheticData.Drug drug = drugs.get(index);
        StringBuilder body = new StringBuilder("<article data-type=\"drug\">");
        body.append("<h1 data-field=\"name\">").append(HtmlParser.escape(drug.name())).append("</h1>");
        body.append("<p>Category: <a href=\"/category/").append(SyntheticData.slug(drug.category()))
                .append("\"><span data-field=\"category\">").append(HtmlParser.escape(drug.category())).append("</span></a></p>");
        body.append("<p>Form: <span data-field=\"form\">").append(drug.form()).append("</span>, strength <span data-field=\"strength\">")
                .append(drug.strength()).append("</span></p>");
        body.append("<p data-field=\"uses\">").append(HtmlParser.escape(drug.uses())).append("</p>");
        body.append("<p>Possible side effects: <span data-field=\"side-effects\">").append(HtmlParser.escape(drug.sideEffects())).append("</span></p>");
        body.append("</article><h2>Related</h2><ul>");
        for (int k = 1; k <= 3; k++) {
            SyntheticData.Drug related = drugs.get((index + k * 7) % drugs.size());
            body.append("<li>").append(link("/drug/" + related.slug() + "?ref=related", related.name())).append("</li>");
        }
        body.append("</ul>");
        if (index % 8 == 0) { // identical content under a second URL
            body.append(link("/print/" + drug.slug(), "Printable version"));
        }
        if (index % 40 == 0) {
            body.append(link("/missing/" + index, "Archived leaflet"));
        }
        return Reply.html(drug.name() + " - MedInfo", body.toString());
    }

    // ---- InsureCo --------------------------------------------------------------------------

    private Reply insurePage(String path) {
        if (path.equals("/robots.txt")) {
            return new Reply(200, "text/plain", "User-agent: *\nDisallow: /agents/\n");
        }
        if (path.startsWith("/agents/")) {
            disallowedHits.incrementAndGet();
            return Reply.html("Agents", "<p>Agent portal</p>");
        }
        if (path.equals("/")) {
            String items = plans.stream().map(SyntheticData.Plan::provider).distinct()
                    .map(p -> "<li>" + link("/providers/" + SyntheticData.slug(p), p) + "</li>").collect(Collectors.joining());
            return Reply.html("InsureCo", "<h1>Health insurance providers</h1><ul>" + items + "</ul>"
                    + link("/agents/login", "Agent login"));
        }
        if (path.startsWith("/providers/")) {
            String provider = path.substring("/providers/".length());
            String items = plans.stream().filter(p -> SyntheticData.slug(p.provider()).equals(provider))
                    .map(p -> "<li>" + link("/plans/" + p.id(), p.provider() + " " + p.name()) + "</li>")
                    .collect(Collectors.joining());
            return items.isEmpty() ? Reply.notFound() : Reply.html("Plans", "<ul>" + items + "</ul>" + link("/", "All providers"));
        }
        if (path.startsWith("/plans/")) {
            String id = path.substring("/plans/".length());
            for (SyntheticData.Plan plan : plans) {
                if (plan.id().equals(id)) {
                    return Reply.html(plan.provider() + " " + plan.name(), "<article data-type=\"insurance-plan\">"
                            + "<h1><span data-field=\"provider\">" + HtmlParser.escape(plan.provider()) + "</span> "
                            + "<span data-field=\"plan-name\">" + plan.name() + "</span></h1>"
                            + "<p>Plan code <span data-field=\"plan-id\">" + plan.id() + "</span></p><table>"
                            + "<tr><td>Consultation</td><td data-field=\"coverage-consultation\">" + plan.consultation() + "</td></tr>"
                            + "<tr><td>Pharmacy</td><td data-field=\"coverage-pharmacy\">" + plan.pharmacy() + "</td></tr>"
                            + "<tr><td>Lab</td><td data-field=\"coverage-lab\">" + plan.lab() + "</td></tr>"
                            + "<tr><td>Emergency</td><td data-field=\"coverage-emergency\">" + plan.emergency() + "</td></tr>"
                            + "<tr><td>Annual limit (paise)</td><td data-field=\"annual-limit\">" + plan.annualLimitPaise() + "</td></tr>"
                            + "</table></article>" + link("/providers/" + SyntheticData.slug(plan.provider()), "Back"));
                }
            }
        }
        return Reply.notFound();
    }

    /** Runs the two sites on their own: {@code java -cp hms-components.jar com.hms.sim.MockSites 9101 9102}. */
    public static void main(String[] args) throws Exception {
        int medInfoPort = args.length > 0 ? Integer.parseInt(args[0]) : 9101;
        int insurePort = args.length > 1 ? Integer.parseInt(args[1]) : 9102;
        MockSites sites = new MockSites(medInfoPort, insurePort, 20, 600);
        sites.start();
        System.out.println("MedInfo at " + sites.medInfoUrl() + ", InsureCo at " + sites.insureUrl());
        Thread.currentThread().join();
    }
}

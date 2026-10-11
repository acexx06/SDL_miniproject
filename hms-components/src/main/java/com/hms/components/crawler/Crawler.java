package com.hms.components.crawler;

import com.hms.components.hashing.NodeRouter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Breadth-first web crawler.
 *
 * <pre>
 * seeds -> URL frontier (FIFO queue) -> worker threads:
 *            robots.txt check -> per-host politeness -> download (with retries)
 *            -> "content seen?" check -> parse -> handler
 *            -> extract links -> normalise -> "URL seen?" check -> back into the frontier
 * </pre>
 *
 * The FIFO frontier gives breadth-first order. Politeness is enforced per host: a cap on parallel
 * requests and a minimum delay between them, so a fast crawl of many hosts never hammers one.
 */
public final class Crawler {

    /** What a successfully fetched, not-seen-before page looks like to the application. */
    public record Page(String url, int depth, String title, String type, Map<String, String> fields) {
    }

    @FunctionalInterface
    public interface PageHandler {
        void onPage(Page page);
    }

    private record Task(String url, int depth) {
    }

    private static final class HostState {
        final Semaphore slots;
        final RobotsRules rules;
        final long delayMs;
        final AtomicInteger fetched = new AtomicInteger();
        long nextAllowedAt;

        HostState(int concurrency, RobotsRules rules, long delayMs) {
            this.slots = new Semaphore(concurrency);
            this.rules = rules;
            this.delayMs = delayMs;
        }
    }

    private final CrawlConfig config;
    private final PageHandler handler;
    private final HttpClient http;
    private final LinkedBlockingQueue<Task> frontier = new LinkedBlockingQueue<>();
    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenContent = ConcurrentHashMap.newKeySet();
    private final Map<String, HostState> hosts = new ConcurrentHashMap<>();
    /** Tasks queued or being processed; the crawl is finished when this reaches zero. */
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();
    private final AtomicInteger parsed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger duplicateUrls = new AtomicInteger();
    private final AtomicInteger duplicateContent = new AtomicInteger();
    private final AtomicInteger robotsBlocked = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();

    public Crawler(CrawlConfig config, PageHandler handler) {
        this.config = config;
        this.handler = handler;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(config.timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CrawlStats run() {
        long start = System.nanoTime();
        for (String seed : config.seeds) {
            enqueue(null, seed, 0);
        }
        ExecutorService pool = Executors.newFixedThreadPool(config.workers, runnable -> {
            Thread thread = new Thread(runnable, "crawler-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < config.workers; i++) {
                workers.add(pool.submit(this::workLoop));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("crawler worker failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> perHost = new TreeMap<>();
        hosts.forEach((host, state) -> perHost.put(host, state.fetched.get()));
        return new CrawlStats(fetched.get(), parsed.get(), failed.get(), retries.get(), duplicateUrls.get(),
                duplicateContent.get(), robotsBlocked.get(), bytes.get(),
                (System.nanoTime() - start) / 1_000_000, perHost);
    }

    private void enqueue(String baseUrl, String href, int depth) {
        String url = UrlNormalizer.normalize(baseUrl, href);
        if (url == null || !config.allowedHosts.contains(URI.create(url).getAuthority())) {
            return;
        }
        if (!seenUrls.add(url)) {
            duplicateUrls.incrementAndGet();
            return;
        }
        outstanding.incrementAndGet();
        frontier.add(new Task(url, depth));
    }

    private void workLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            Task task;
            try {
                task = frontier.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (task == null) {
                if (outstanding.get() == 0) {
                    return;
                }
                continue;
            }
            try {
                process(task);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                failed.incrementAndGet(); // one bad page must not stop the crawl
            } finally {
                outstanding.decrementAndGet();
            }
        }
    }

    private HostState hostState(URI uri) {
        return hosts.computeIfAbsent(uri.getAuthority(), authority -> {
            RobotsRules rules = RobotsRules.ALLOW_ALL;
            if (config.obeyRobots) {
                try {
                    HttpResponse<String> response = get(uri.getScheme() + "://" + authority + "/robots.txt");
                    if (response.statusCode() == 200) {
                        rules = RobotsRules.parse(response.body(), config.userAgent);
                    }
                } catch (IOException e) {
                    // no reachable robots.txt: nothing is disallowed
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new HostState(config.perHostConcurrency, rules, Math.max(config.perHostDelayMs, rules.crawlDelayMs()));
        });
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.timeoutMs))
                .header("User-Agent", config.userAgent)
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void process(Task task) throws InterruptedException {
        URI uri = URI.create(task.url);
        HostState host = hostState(uri);
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (!host.rules.isAllowed(path)) {
            robotsBlocked.incrementAndGet();
            return;
        }
        if (fetched.incrementAndGet() > config.maxPages) {
            fetched.decrementAndGet();
            return;
        }
        host.fetched.incrementAndGet();

        HttpResponse<String> response = null;
        host.slots.acquire();
        try {
            for (int attempt = 0; attempt <= config.maxRetries; attempt++) {
                waitForTurn(host);
                try {
                    response = get(task.url);
                    if (response.statusCode() < 500) {
                        break;
                    }
                } catch (IOException e) {
                    response = null;
                }
                if (attempt < config.maxRetries) {
                    retries.incrementAndGet();
                    Thread.sleep(50L * (attempt + 1));
                }
            }
        } finally {
            host.slots.release();
        }
        if (response == null || response.statusCode() != 200) {
            failed.incrementAndGet();
            return;
        }
        String body = response.body();
        bytes.addAndGet(body.length());
        if (!seenContent.add(NodeRouter.hash64(body))) {
            duplicateContent.incrementAndGet();
            return;
        }
        HtmlParser.Parsed html = HtmlParser.parse(body);
        parsed.incrementAndGet();
        handler.onPage(new Page(task.url, task.depth, html.title(), html.type(), html.fields()));
        if (task.depth < config.maxDepth) {
            for (String link : html.links()) {
                enqueue(task.url, link, task.depth + 1);
            }
        }
    }

    /** Spaces out requests to one host by reserving the next free time slot, then sleeping until it. */
    private void waitForTurn(HostState host) throws InterruptedException {
        if (host.delayMs <= 0) {
            return;
        }
        long wait;
        synchronized (host) {
            long now = System.currentTimeMillis();
            long turn = Math.max(now, host.nextAllowedAt);
            host.nextAllowedAt = turn + host.delayMs;
            wait = turn - now;
        }
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }
}

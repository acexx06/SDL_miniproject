package com.hms.service;

import com.hms.components.crawler.CrawlConfig;
import com.hms.components.crawler.CrawlStats;
import com.hms.components.crawler.Crawler;
import com.hms.config.HmsProperties;
import com.hms.domain.DrugInfo;
import com.hms.domain.InsurancePlan;
import com.hms.sim.MockSites;
import com.hms.sim.SyntheticData;
import com.hms.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the web crawler over the external sources and stores what it finds: drug reference pages
 * become {@link DrugInfo}, insurance plan pages become {@link InsurancePlan}. Afterwards medicine
 * search and billing are refreshed so the new data is used immediately.
 *
 * The "external sources" are the two simulated websites in {@link MockSites}, which this service
 * also starts. No real site is contacted and no patient data is involved.
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    private final MongoTemplate mongo;
    private final BillingService billing;
    private final PharmacyService pharmacy;
    private final MockSites sites;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crawl-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> lastRun = Map.of();

    public CrawlService(MongoTemplate mongo, BillingService billing, PharmacyService pharmacy,
                        HmsProperties properties) throws IOException {
        this.mongo = mongo;
        this.billing = billing;
        this.pharmacy = pharmacy;
        HmsProperties.MockSites config = properties.mockSites();
        if (config.enabled()) {
            sites = new MockSites(config.basePort(), config.basePort() + 1, config.latencyMs(), config.drugs());
            sites.start();
            log.info("Simulated external sites: {} and {}", sites.medInfoUrl(), sites.insureUrl());
        } else {
            sites = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Starts a crawl in the background; refuses if one is already in progress. */
    public void start(int workers, long perHostDelayMs) {
        if (sites == null) {
            throw ApiException.badRequest("The simulated external sites are disabled (hms.mock-sites.enabled)");
        }
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("A crawl is already running");
        }
        executor.execute(() -> {
            try {
                crawl(workers, perHostDelayMs);
            } catch (RuntimeException e) {
                log.error("Crawl failed", e);
                lastRun = Map.of("error", String.valueOf(e.getMessage()), "finishedAt", Instant.now().toString());
            } finally {
                running.set(false);
            }
        });
    }

    private static int percent(Map<String, String> fields, String name) {
        return Integer.parseInt(fields.getOrDefault(name, "0").trim());
    }

    private void crawl(int workers, long perHostDelayMs) {
        AtomicInteger drugs = new AtomicInteger();
        AtomicInteger plans = new AtomicInteger();
        Instant startedAt = Instant.now();
        CrawlConfig config = CrawlConfig.of(sites.seeds()).workers(workers).perHostDelayMs(perHostDelayMs);
        CrawlStats stats = new Crawler(config, page -> {
            Map<String, String> f = page.fields();
            if ("drug".equals(page.type()) && f.containsKey("name")) {
                mongo.save(new DrugInfo(SyntheticData.slug(f.get("name")), f.get("name"), f.get("category"),
                        f.get("form"), f.get("strength"), f.get("uses"), f.get("side-effects"), page.url(), Instant.now()));
                drugs.incrementAndGet();
            } else if ("insurance-plan".equals(page.type()) && f.containsKey("plan-id")) {
                mongo.save(new InsurancePlan(f.get("plan-id"), f.get("provider"), f.get("plan-name"),
                        percent(f, "coverage-consultation"), percent(f, "coverage-pharmacy"), percent(f, "coverage-lab"),
                        percent(f, "coverage-emergency"), Long.parseLong(f.getOrDefault("annual-limit", "0").trim()),
                        page.url(), Instant.now()));
                plans.incrementAndGet();
            }
        }).run();
        billing.reloadPlans();
        pharmacy.reload();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("startedAt", startedAt.toString());
        summary.put("finishedAt", Instant.now().toString());
        summary.put("workers", workers);
        summary.put("perHostDelayMs", perHostDelayMs);
        summary.put("drugPagesStored", drugs.get());
        summary.put("insurancePlansStored", plans.get());
        summary.put("pagesFetched", stats.pagesFetched());
        summary.put("pagesParsed", stats.pagesParsed());
        summary.put("pagesPerSecond", stats.pagesPerSecond());
        summary.put("elapsedMs", stats.elapsedMs());
        summary.put("retries", stats.retries());
        summary.put("failed", stats.failed());
        summary.put("duplicateUrlsSkipped", stats.duplicateUrls());
        summary.put("duplicateContentSkipped", stats.duplicateContent());
        summary.put("blockedByRobots", stats.robotsBlocked());
        summary.put("bytes", stats.bytes());
        summary.put("perHost", stats.perHost());
        lastRun = summary;
        log.info("Crawl finished: {} pages in {} ms ({} drugs, {} plans)", stats.pagesFetched(), stats.elapsedMs(),
                drugs.get(), plans.get());
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running.get());
        result.put("sources", sites == null ? List.of() : sites.seeds());
        result.put("lastRun", lastRun);
        return result;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        if (sites != null) {
            sites.close();
        }
    }
}

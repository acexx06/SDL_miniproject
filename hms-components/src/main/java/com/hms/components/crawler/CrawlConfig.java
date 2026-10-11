package com.hms.components.crawler;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings for one crawl. Fields are public and the setters chain, e.g. {@code CrawlConfig.of(seeds).workers(8)}. */
public final class CrawlConfig {

    public final List<String> seeds;
    /** Only URLs on these {@code host:port} authorities are followed. Defaults to the seeds' own. */
    public final Set<String> allowedHosts = new HashSet<>();
    public int maxPages = 5000;
    public int maxDepth = 8;
    public int workers = 8;
    /** Minimum gap between two requests to the same host (politeness). */
    public long perHostDelayMs = 0;
    public int perHostConcurrency = 8;
    public int timeoutMs = 5000;
    public int maxRetries = 2;
    public String userAgent = "HmsCrawler/1.0";
    public boolean obeyRobots = true;

    private CrawlConfig(List<String> seeds) {
        this.seeds = List.copyOf(seeds);
        for (String seed : seeds) {
            allowedHosts.add(URI.create(seed).getAuthority().toLowerCase());
        }
    }

    public static CrawlConfig of(List<String> seeds) {
        return new CrawlConfig(seeds);
    }

    public CrawlConfig maxPages(int value) {
        this.maxPages = value;
        return this;
    }

    public CrawlConfig maxDepth(int value) {
        this.maxDepth = value;
        return this;
    }

    public CrawlConfig workers(int value) {
        this.workers = value;
        return this;
    }

    public CrawlConfig perHostDelayMs(long value) {
        this.perHostDelayMs = value;
        return this;
    }

    public CrawlConfig perHostConcurrency(int value) {
        this.perHostConcurrency = value;
        return this;
    }

    public CrawlConfig obeyRobots(boolean value) {
        this.obeyRobots = value;
        return this;
    }
}

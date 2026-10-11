package com.hms.components.crawler;

import java.util.Map;

/**
 * Outcome of one crawl.
 *
 * @param pagesFetched      URLs requested (each counted once, however many retries it took)
 * @param pagesParsed       pages that returned 200 with new content and were handed to the handler
 * @param failed            URLs given up on (4xx, or still failing after the retries)
 * @param retries           extra attempts made after a timeout or 5xx response
 * @param duplicateUrls     links skipped because the normalised URL had already been seen
 * @param duplicateContent  pages skipped because identical content was already processed
 * @param robotsBlocked     URLs not requested because robots.txt disallows them
 * @param bytes             total body bytes downloaded
 * @param perHost           pages fetched from each host
 */
public record CrawlStats(int pagesFetched, int pagesParsed, int failed, int retries, int duplicateUrls,
                         int duplicateContent, int robotsBlocked, long bytes, long elapsedMs,
                         Map<String, Integer> perHost) {

    public double pagesPerSecond() {
        return elapsedMs == 0 ? 0 : pagesFetched * 1000.0 / elapsedMs;
    }
}

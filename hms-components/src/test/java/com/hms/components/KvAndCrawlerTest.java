package com.hms.components;

import com.hms.components.crawler.CrawlConfig;
import com.hms.components.crawler.CrawlStats;
import com.hms.components.crawler.Crawler;
import com.hms.components.crawler.HtmlParser;
import com.hms.components.crawler.RobotsRules;
import com.hms.components.crawler.UrlNormalizer;
import com.hms.components.hashing.HashRing;
import com.hms.components.kv.KvCluster;
import com.hms.components.kv.KvEngine;
import com.hms.components.kv.KvNodeClient;
import com.hms.components.kv.KvNodeServer;
import com.hms.sim.MockSites;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KvAndCrawlerTest {

    // ---- key-value store -------------------------------------------------------------------

    @Test
    void engineExpiresEntriesAndEvictsTheLeastRecentlyUsed() {
        AtomicLong clock = new AtomicLong(1_000);
        KvEngine engine = new KvEngine(3, clock::get);
        engine.set("session", "s", 500);
        engine.set("a", "1", 0);
        assertEquals("s", engine.get("session"));
        clock.addAndGet(500);
        assertNull(engine.get("session"), "expired after its TTL");

        engine.set("b", "2", 0);
        engine.set("c", "3", 0);
        assertEquals("1", engine.get("a")); // touch a, so b is now the oldest
        engine.set("d", "4", 0);
        assertNull(engine.get("b"), "least recently used entry was evicted");
        assertEquals("1", engine.get("a"));
        assertEquals(3, engine.size());

        assertFalse(engine.setIfAbsent("a", "x", 0));
        assertEquals(2, engine.increment("visits", 2, 0));
        assertEquals(5, engine.increment("visits", 3, 0));
        KvEngine.Stats stats = engine.stats();
        assertEquals(1, stats.expirations());
        assertTrue(stats.evictions() >= 1);
    }

    @Test
    void clientTalksToServerOverTcp() throws Exception {
        try (KvNodeServer server = new KvNodeServer("n1", 0, 1000)) {
            server.start();
            try (KvNodeClient client = new KvNodeClient("127.0.0.1", server.port())) {
                assertNull(client.get("missing"));
                client.set("patient:1", "{\"name\":\"Asha ₹\"}", 0);
                assertEquals("{\"name\":\"Asha ₹\"}", client.get("patient:1"));
                assertTrue(client.setIfAbsent("lock", "1", 0));
                assertFalse(client.setIfAbsent("lock", "2", 0));
                assertEquals(7, client.increment("count", 7, 0));
                assertTrue(client.delete("patient:1"));
                assertNull(client.get("patient:1"));
                client.set("big", "x".repeat(200_000), 0);
                assertEquals(200_000, client.get("big").length());
                assertEquals(3, client.stats().keys());
            }
        }
        try (KvNodeClient client = new KvNodeClient("127.0.0.1", 1)) {
            assertThrows(KvNodeClient.UnavailableException.class, () -> client.get("k"));
        }
    }

    @Test
    void clusterKeepsServingWhenANodeDiesAndTakesItBackEmpty() throws Exception {
        List<KvNodeServer> servers = new ArrayList<>();
        try (KvCluster cluster = new KvCluster(new HashRing(100), 1, Duration.ofMillis(50))) {
            for (int i = 1; i <= 3; i++) {
                KvNodeServer server = new KvNodeServer("n" + i, 0, 100_000);
                server.start();
                servers.add(server);
                cluster.addNode("n" + i, "127.0.0.1", server.port());
            }
            for (int i = 0; i < 300; i++) {
                cluster.set("k" + i, "v" + i, null);
            }
            for (KvNodeServer server : servers) {
                assertTrue(server.engine().size() > 40, "every node holds a share of the keys");
            }

            KvNodeServer victim = servers.get(1);
            int lost = victim.engine().size();
            int port = victim.port();
            victim.close();

            int hits = 0;
            for (int i = 0; i < 300; i++) {
                hits += ("v" + i).equals(cluster.get("k" + i)) ? 1 : 0; // must not throw
            }
            assertEquals(300 - lost, hits, "only the dead node's keys are missing");
            assertEquals(2, cluster.router().nodes().size());
            assertEquals(1, cluster.failovers());

            cluster.set("k0", "rewritten", null); // lands on a surviving node
            assertEquals("rewritten", cluster.get("k0"));

            KvNodeServer replacement = new KvNodeServer("n2", port, 100_000);
            replacement.start();
            servers.set(1, replacement);
            for (int i = 0; i < 100 && cluster.router().nodes().size() < 3; i++) {
                Thread.sleep(50);
            }
            assertEquals(3, cluster.router().nodes().size(), "the node rejoined the ring");
            assertEquals(0, replacement.engine().size());
        } finally {
            servers.forEach(KvNodeServer::close);
        }
    }

    @Test
    void replicasCoverForADeadNode() throws Exception {
        List<KvNodeServer> servers = new ArrayList<>();
        try (KvCluster cluster = new KvCluster(new HashRing(100), 2, Duration.ofMillis(50))) {
            for (int i = 1; i <= 3; i++) {
                KvNodeServer server = new KvNodeServer("n" + i, 0, 100_000);
                server.start();
                servers.add(server);
                cluster.addNode("n" + i, "127.0.0.1", server.port());
            }
            for (int i = 0; i < 200; i++) {
                cluster.set("k" + i, "v" + i, null);
            }
            servers.get(0).close();
            for (int i = 0; i < 200; i++) {
                assertEquals("v" + i, cluster.get("k" + i));
            }
            cluster.delete("k1");
            assertNull(cluster.get("k1"));
        } finally {
            servers.forEach(KvNodeServer::close);
        }
    }

    // ---- web crawler -----------------------------------------------------------------------

    @Test
    void urlsAreNormalisedToOneSpelling() {
        String base = "http://Example.test:80/drugs/list?page=2";
        assertEquals("http://example.test/drug/abc", UrlNormalizer.normalize(base, "/drug/abc?utm_source=x&ref=y#top"));
        assertEquals("http://example.test/drugs/other", UrlNormalizer.normalize(base, "other/"));
        assertEquals("http://example.test/a?a=1&b=2", UrlNormalizer.normalize(base, "/a?b=2&a=1"));
        assertEquals("http://example.test/", UrlNormalizer.normalize(base, "/"));
        assertEquals("http://other.test:8080/x", UrlNormalizer.normalize(base, "http://other.test:8080/x"));
        assertNull(UrlNormalizer.normalize(base, "mailto:a@b.test"));
        assertNull(UrlNormalizer.normalize(base, "#section"));
        assertNull(UrlNormalizer.normalize(base, "ftp://files.test/x"));
    }

    @Test
    void robotsRulesUseTheMostSpecificGroupAndLongestMatch() {
        String robots = """
                # comment
                User-agent: *
                Disallow: /private/
                Allow: /private/press/
                Crawl-delay: 2

                User-agent: OtherBot
                Disallow: /
                """;
        RobotsRules rules = RobotsRules.parse(robots, "HmsCrawler/1.0");
        assertTrue(rules.isAllowed("/drugs"));
        assertFalse(rules.isAllowed("/private/staff"));
        assertTrue(rules.isAllowed("/private/press/release"));
        assertEquals(2000, rules.crawlDelayMs());
        assertFalse(RobotsRules.parse(robots, "otherbot").isAllowed("/drugs"));
        assertTrue(RobotsRules.ALLOW_ALL.isAllowed("/anything"));
    }

    @Test
    void parserExtractsLinksTitleAndMarkedFields() {
        HtmlParser.Parsed parsed = HtmlParser.parse("""
                <html><head><title> A &amp; B </title></head><body>
                <article data-type="drug"><h1 data-field="name">Zyvacillin</h1>
                <span data-field="category">Pain &amp; Fever</span></article>
                <a href="/one">1</a> <A class="x" HREF='two?a=1&amp;b=2'>2</A></body></html>""");
        assertEquals("A & B", parsed.title());
        assertEquals("drug", parsed.type());
        assertEquals("Zyvacillin", parsed.fields().get("name"));
        assertEquals("Pain & Fever", parsed.fields().get("category"));
        assertEquals(List.of("/one", "two?a=1&b=2"), parsed.links());
    }

    @Test
    void crawlerCoversBothSitesPolitelyAndWithoutDuplicates() throws Exception {
        try (MockSites sites = new MockSites(0, 0, 0, 120)) {
            sites.start();
            Set<String> drugNames = ConcurrentHashMap.newKeySet();
            Set<String> planIds = ConcurrentHashMap.newKeySet();
            CrawlStats stats = new Crawler(CrawlConfig.of(sites.seeds()).workers(8), page -> {
                if ("drug".equals(page.type())) {
                    assertTrue(drugNames.add(page.fields().get("name")), "each drug handled once");
                    assertNotNull(page.fields().get("side-effects"));
                } else if ("insurance-plan".equals(page.type())) {
                    planIds.add(page.fields().get("plan-id"));
                }
            }).run();

            assertEquals(sites.drugCount(), drugNames.size());
            assertEquals(sites.planCount(), planIds.size());
            assertEquals(0, sites.disallowedHits(), "robots.txt was obeyed");
            assertTrue(stats.robotsBlocked() >= 3);
            assertEquals(6, stats.retries(), "drug pages 0, 20, .. 100 each fail once");
            assertEquals(15, stats.duplicateContent(), "printable versions repeat a drug page");
            assertEquals(3, stats.failed(), "three broken links");
            assertTrue(stats.duplicateUrls() > 100);
            assertEquals(2, stats.perHost().size());
            // Every request the sites saw was one the crawler accounts for (pages, retries, 2 robots.txt).
            assertEquals(stats.pagesFetched() + stats.retries() + 2, sites.requestCount());
        }
    }

    @Test
    void crawlerHonoursPageBudgetAndPerHostDelay() throws Exception {
        try (MockSites sites = new MockSites(0, 0, 0, 60)) {
            sites.start();
            CrawlConfig config = CrawlConfig.of(List.of(sites.insureUrl())).workers(4).maxPages(10).perHostDelayMs(20);
            long start = System.nanoTime();
            CrawlStats stats = new Crawler(config, page -> { }).run();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(10, stats.pagesFetched());
            assertTrue(elapsedMs >= 9 * 20, "10 requests spaced 20 ms apart took " + elapsedMs + " ms");
        }
    }
}

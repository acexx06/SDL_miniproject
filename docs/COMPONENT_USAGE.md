# Where each system-design component is used

This document answers one question for each of the seven components: **where exactly in the hospital system is it used?** For every component it lists the code that implements it, every place the application calls it (file and line), the hospital functions that depend on it, how to see it working, its settings, and
where it is tested and measured.

The *why* behind each choice is in [REPORT.md](REPORT.md), section 2. The measured results are in
[RESULTS.md](RESULTS.md).

Line numbers are for the code as it stands when this was written. If the code has moved, search for the method name given beside each link.

## At a glance

| #   | Component                                     | Implemented in (`hms-components`) | Plugged into the application by                           | You meet it when you...                                     |
| --- | --------------------------------------------- | --------------------------------- | --------------------------------------------------------- | ----------------------------------------------------------- |
| 1   | [Rate limiter](#1-rate-limiter)               | `components/ratelimit/`           | `web/RateLimitFilter`                                     | send any request; log in too fast and get HTTP 429          |
| 2   | [Unique ID generator](#2-unique-id-generator) | `components/idgen/`               | `service/IdService`                                       | register, book, get a bill: every `PAT…`, `APT…`, `BIL…` id |
| 3   | [Autocomplete](#3-autocomplete)               | `components/autocomplete/`        | `service/DoctorService`, `service/PharmacyService`        | type in "Find a doctor" or "Medicine search"                |
| 4   | [URL shortener](#4-url-shortener)             | `components/shortener/`           | `service/ShortLinkService`                                | press "Share link" on a bill and open `/s/…`                |
| 5   | [Web crawler](#5-web-crawler)                 | `components/crawler/`             | `service/CrawlService`                                    | choose an insurance plan; read drug information             |
| 6   | [Consistent hashing](#6-consistent-hashing)   | `components/hashing/`             | `service/KvNodeManager` (ring given to the cache cluster) | read anything that is cached; kill a cache server           |
| 7   | [Key-value store](#7-key-value-store)         | `components/kv/`                  | `service/KvNodeManager`, `service/CacheService`           | make any logged-in request (the session comes from it)      |

Paths in this table are under `hms-components/src/main/java/com/hms/` and `hms-app/src/main/java/com/hms/`.

## The order a request meets them

```
Browser
  │  HTTP request
  ▼
[1] Rate limiter ............ web/RateLimitFilter          (every request, before anything else)
  ▼
Authentication .............. web/AuthInterceptor
  │     session lookup ──► [7] Key-value store, node chosen by [6] Consistent hashing
  ▼
Controller ─► Service
  │     new record needs an id ──► [2] Unique ID generator
  │     search or suggestion ────► [3] Autocomplete
  │     read data ───────────────► [7] Key-value store via [6]; MongoDB on a miss
  │     write data ──────────────► MongoDB, then evict keys from [7]
  │     share a bill ────────────► [4] URL shortener
  ▼
Response

In the background:  [5] Web crawler ─► MongoDB (drug_info, insurance_plans) ─► refreshes [3] and billing
```

---

## 1. Rate limiter

**What it is.** A token bucket per client: a client may make a short burst of requests, then is held to a
steady rate. A request beyond that is refused with HTTP 429.

**Implementation**

| File | Role |
|---|---|
| [`TokenBucketRateLimiter.java`](../hms-components/src/main/java/com/hms/components/ratelimit/TokenBucketRateLimiter.java#L45) | the algorithm used by the application (`tryAcquire`) |
| [`RateLimiter.java`](../hms-components/src/main/java/com/hms/components/ratelimit/RateLimiter.java) | the interface and the `Decision` result |
| [`FixedWindowRateLimiter.java`](../hms-components/src/main/java/com/hms/components/ratelimit/FixedWindowRateLimiter.java), [`SlidingWindowLogRateLimiter.java`](../hms-components/src/main/java/com/hms/components/ratelimit/SlidingWindowLogRateLimiter.java) | baselines for comparison only; the application does not use them |

**Where the application uses it.** In exactly one place, which every request passes through:
[`RateLimitFilter.java`](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java).

| Line | What happens |
|---|---|
| [32](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L32) | `@Order(HIGHEST_PRECEDENCE)`: the filter runs before authentication and before any controller |
| [36–48](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L36) | the seven request classes and their limits (table below) |
| [75](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L75) | one `TokenBucketRateLimiter` is created per class |
| [80](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L80) | `classify(method, path)` decides which class a request belongs to |
| [102](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L102) | `clientKey` identifies the client: its address, or `X-Forwarded-For` when `hms.trust-proxy=true` |
| [124](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L124) | **the call**: `limiters.get(routeClass).tryAcquire(clientKey(request))` |
| [127–128](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L127) | a refused request gets status 429 and a `Retry-After` header |
| [149](../hms-app/src/main/java/com/hms/web/RateLimitFilter.java#L149) | once a minute, buckets of clients that have gone quiet are discarded |

**Which hospital functions it protects**

| Request class | Requests it covers              | Limit per client         | Hospital function                                                                                                |
| ------------- | ------------------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `AUTH`        | `/api/auth/*`                   | 1 per second, burst 10   | patient registration and login (slows password guessing)                                                         |
| `SEARCH`      | `GET …/suggest`, `GET …/search` | 20 per second, burst 40  | doctor search, medicine search                                                                                   |
| `READ`        | every other `GET /api/…`        | 20 per second, burst 40  | profile, records, appointments, bills, slots                                                                     |
| `WRITE`       | `POST` / `PUT` under `/api/…`   | 5 per second, burst 15   | booking, cancelling, prescriptions, payment                                                                      |
| `REDIRECT`    | `GET /s/{code}`                 | 10 per second, burst 20  | short links (stops codes being found by trying many)                                                             |
| `EMERGENCY`   | `POST /api/emergency`           | 2 per second, burst 20   | emergency requests: a separate bucket, so a client that has used up its other allowances can still call for help |
| `ADMIN`       | `/api/admin/*`                  | 50 per second, burst 100 | operations console                                                                                               |

Static files (the web page itself) are not rate limited.

**See it working**
- *System* page (administrator), "Rate limiter test" card, "Send 30 rapid logins": about 10 reach the password check, about 20 are refused. The button is wired at [`index.html` line 1566](../hms-app/src/main/resources/static/index.html#L1566).
- *System* page, "Requests and rate limiting" table and the "Rate limiter" tile: live counts per class from `GET /api/admin/metrics` ([`AdminController` line 116](../hms-app/src/main/java/com/hms/api/AdminController.java#L116)).
- Step 16 of `run.bat demo`.
- It can be switched off and on while running, to compare: `POST /api/admin/config` ([line 131](../hms-app/src/main/java/com/hms/api/AdminController.java#L131)), or the *Switches* card on the System page ([`index.html` line 1547](../hms-app/src/main/resources/static/index.html#L1547)).

**Settings:** `hms.rate-limit.enabled`, `hms.trust-proxy`. The limits themselves are the constants in the `RouteClass` enum.

**Tested in** [`CoreComponentsTest`](../hms-components/src/test/java/com/hms/components/CoreComponentsTest.java#L91) lines 91 and 114.
**Measured in** [`ComponentBench.rateLimiter`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L201) and [`EndToEndBench.abuseExperiment`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L421); results in RESULTS.md sections 5.2 and 3.3.

---

## 2. Unique ID generator

**What it is.** A Snowflake generator: 64-bit ids made of a timestamp, a server ("worker") number and a
sequence, written in base 62 with a prefix, for example `APT0HKblm8obgH`. Each application server can create
ids on its own, they never collide, and they sort by creation time.

**Implementation**

| File | Role |
|---|---|
| [`SnowflakeIdGenerator.java`](../hms-components/src/main/java/com/hms/components/idgen/SnowflakeIdGenerator.java#L49) | `nextId()` at line 49; `nextId(prefix)` at line 73 |
| [`Base62.java`](../hms-components/src/main/java/com/hms/components/idgen/Base62.java) | text encoding (also used by the URL shortener for its codes) |

**Where the application uses it.** Through one wrapper, [`IdService.java`](../hms-app/src/main/java/com/hms/service/IdService.java#L14) (line 14 creates the generator with this server's worker id). Every id in the system comes from `ids.next("PREFIX")`:

| Prefix | Identifies | Created at |
|---|---|---|
| `PAT` | patient (and their login) | [`AuthService` line 84](../hms-app/src/main/java/com/hms/service/AuthService.java#L84) on registration |
| `DOC` | doctor | [`AdminController` line 165](../hms-app/src/main/java/com/hms/api/AdminController.java#L165) when an administrator adds one; [`Bootstrap` line 123](../hms-app/src/main/java/com/hms/service/Bootstrap.java#L123) for the starting data |
| `APT` | appointment | [`AppointmentService` line 125](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L125) |
| `BIL` | bill | [`AppointmentService` line 124](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L124) (consultation bill) and [`PharmacyService` line 218](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L218) (pharmacy bill) |
| `REC` | medical record | [`PatientService` line 80](../hms-app/src/main/java/com/hms/service/PatientService.java#L80) |
| `RX` | prescription | [`PharmacyService` line 144](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L144) |
| `EMR` | emergency request | [`EmergencyService` line 77](../hms-app/src/main/java/com/hms/service/EmergencyService.java#L77) |
| `MED` | medicine in stock | [`Bootstrap` line 130](../hms-app/src/main/java/com/hms/service/Bootstrap.java#L130) |
| `STF` | staff account | [`Bootstrap` lines 146–147](../hms-app/src/main/java/com/hms/service/Bootstrap.java#L146) |

The bulk test-data loader also uses it ([`AdminService` lines 126–144](../hms-app/src/main/java/com/hms/service/AdminService.java#L126)).

Two design points that depend on it:
- The bill id is generated *before* the bill exists, so the appointment can be saved already pointing at its bill (AppointmentService lines 124–125).
- Lists are sorted "newest first" by sorting on the id itself (`Sort.by(DESC, "_id")` in the list queries), which works only because the ids are time-ordered.

**Where it is deliberately not used:** session tokens and short-link codes (these must be unguessable, so they are random), ambulance ids (`AMB-01`…), and crawled data (insurance plans keep the plan code published by the insurer; drug information is keyed by the drug name).

**See it working:** register a patient, or book an appointment: the confirmation shows the new appointment and bill ids. The *System* page's "Unique ID generator" tile counts the ids issued. Steps 1 and 15 of `run.bat demo`.

**Settings:** `hms.worker-id` (0–1023; must differ on each application server).

**Tested in** [`CoreComponentsTest`](../hms-components/src/test/java/com/hms/components/CoreComponentsTest.java#L36) lines 36, 61 and 71.
**Measured in** [`ComponentBench.idGenerator`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L121); RESULTS.md section 5.1.

---

## 3. Autocomplete

**What it is.** A trie (prefix tree) in memory in which every node remembers its best few entries, so
suggestions take about a microsecond however large the directory is. It matches any word of a name, ranks by
popularity, and tolerates one typo.

**Implementation**

| File | Role |
|---|---|
| [`Autocomplete.java`](../hms-components/src/main/java/com/hms/components/autocomplete/Autocomplete.java#L245) | `add` (line 159), `bump` (184), `suggest` (245), typo search `fuzzy` (209), top-k cache `offer` (117) |
| [`LinearScanAutocomplete.java`](../hms-components/src/main/java/com/hms/components/autocomplete/LinearScanAutocomplete.java) | baseline for comparison only |

**Where the application uses it.** In two services, each holding its own index.

*Doctor and specialization search* — [`DoctorService.java`](../hms-app/src/main/java/com/hms/service/DoctorService.java)

| Line | What happens |
|---|---|
| [53](../hms-app/src/main/java/com/hms/service/DoctorService.java#L53) | the index is created |
| [65–69](../hms-app/src/main/java/com/hms/service/DoctorService.java#L65) | each doctor is added under their name, and each specialization is added as an entry of its own, so "card" suggests *Cardiology* |
| [75](../hms-app/src/main/java/com/hms/service/DoctorService.java#L75) | `reload()` builds the index from MongoDB at start-up |
| [99](../hms-app/src/main/java/com/hms/service/DoctorService.java#L99) | `suggest()`: the suggestions shown while typing |
| [116](../hms-app/src/main/java/com/hms/service/DoctorService.java#L116) | `search()`: the result list is also built from the index, with no database query |
| [162](../hms-app/src/main/java/com/hms/service/DoctorService.java#L162) | `recordBooking()` calls `bump`: every booking moves that doctor up the suggestions |

Reached from `GET /api/doctors/suggest` ([`DoctorController` line 38](../hms-app/src/main/java/com/hms/api/DoctorController.java#L38)) and `GET /api/doctors/search` ([line 44](../hms-app/src/main/java/com/hms/api/DoctorController.java#L44)). In the web portal the *Find a doctor* page wires its search box at [`index.html` line 999](../hms-app/src/main/resources/static/index.html#L999); the shared, keyboard-navigable suggestion box is [`suggestBox`, line 541](../hms-app/src/main/resources/static/index.html#L541).

*Medicine search* — [`PharmacyService.java`](../hms-app/src/main/java/com/hms/service/PharmacyService.java)

| Line | What happens |
|---|---|
| [60](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L60) | the index is created |
| [73–81](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L73) | `reload()` adds every medicine in stock, then every crawled drug the pharmacy does not stock (marked "reference only") |
| [93](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L93) | `suggest()` |

Reached from `GET /api/medicines/suggest` ([`PharmacyController` line 55](../hms-app/src/main/java/com/hms/api/PharmacyController.java#L55)). Used by the *Medicines* page ([`index.html` line 1186](../hms-app/src/main/resources/static/index.html#L1186)) and by the doctor's "Write a prescription" form ([line 1335](../hms-app/src/main/resources/static/index.html#L1335)).

**Which hospital functions it serves:** doctor search, appointment booking (finding the doctor to book), medicine search, writing prescriptions.

**The baseline kept for comparison.** `GET /api/doctors/search?mode=db` runs the same search as a MongoDB pattern query (`DoctorService.searchDatabase`), and `GET /api/medicines/search` does the same for medicines. The application's own pages never call these.

**See it working:** type `car`, `rao` or the typo `cardoilogy` into "Find a doctor"; the line under the box shows how long each suggestion request took. Steps 3, 4 and 8 of `run.bat demo`.

**Settings:** none; the size of the index follows the data (`hms.seed.doctors`, `hms.seed.medicines`).

**Tested in** [`CoreComponentsTest`](../hms-components/src/test/java/com/hms/components/CoreComponentsTest.java#L199) lines 199 and 224.
**Measured in** [`ComponentBench.autocomplete`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L439) and [`EndToEndBench.searchBaseline`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L392); RESULTS.md sections 5.6 and 3.2.

---

## 4. URL shortener

**What it is.** Turns a long internal link into a short code such as `/s/J3Uwt9tM` and redirects back.
Codes are random (not counted up) so that nobody can find other patients' links by guessing, they expire, and
only links to pages on this site are accepted.

**Implementation**

| File | Role |
|---|---|
| [`UrlShortener.java`](../hms-components/src/main/java/com/hms/components/shortener/UrlShortener.java#L68) | `shorten` (line 68) generates a code and retries on a collision; `resolve` (84); `isSafeTarget` (51) rejects links to other sites; `Store` is the interface for where codes are kept |

**Where the application uses it**

| File and line | What happens |
|---|---|
| [`ShortLinkService` line 24](../hms-app/src/main/java/com/hms/service/ShortLinkService.java#L24) | the service implements `UrlShortener.Store`: codes are saved in MongoDB (`short_urls`), whose unique `_id` is what detects a collision |
| [`ShortLinkService` line 31](../hms-app/src/main/java/com/hms/service/ShortLinkService.java#L31) | the `UrlShortener` is created with 8-character codes |
| [`ShortLinkService` line 58](../hms-app/src/main/java/com/hms/service/ShortLinkService.java#L58) | looking a code up goes through the key-value cache (key `short:{code}`) |
| [`BillingController` line 70](../hms-app/src/main/java/com/hms/api/BillingController.java#L70) | **the main use**: `POST /api/bills/{id}/share` shortens the link to a bill, valid for 7 days |
| [`ShortLinkController` line 42](../hms-app/src/main/java/com/hms/api/ShortLinkController.java#L42) | `POST /api/shorten`: shorten any page of the portal |
| [`ShortLinkController` line 56](../hms-app/src/main/java/com/hms/api/ShortLinkController.java#L56) | `GET /s/{code}`: the redirect (HTTP 302) |
| [`Bootstrap` line 98](../hms-app/src/main/java/com/hms/service/Bootstrap.java#L98) | the MongoDB TTL index that deletes expired links |

**Which hospital function it serves:** billing — sharing a bill, for example by text message. The page the link opens still requires the owner's login.

**See it working:** *Bills & insurance* page, "Share link" on a bill ([`index.html` line 1171](../hms-app/src/main/resources/static/index.html#L1171)); opening the link returns you to that bill, highlighted. Step 12 of `run.bat demo`. The *System* page's "URL shortener" tile shows links created and collisions.

**Settings:** code length is `ShortLinkService.CODE_LENGTH` (8).

**Tested in** [`CoreComponentsTest`](../hms-components/src/test/java/com/hms/components/CoreComponentsTest.java#L238) lines 238 and 249.
**Measured in** [`ComponentBench.urlShortener`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L595) and [`EndToEndBench.redirectLatency`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L369); RESULTS.md sections 5.7 and 3.4.

---

## 5. Web crawler

**What it is.** A breadth-first crawler that collects information the hospital needs but does not own, from
other organisations' websites. It obeys `robots.txt`, limits how fast it hits each site, retries failures, and
skips URLs and content it has already seen.

**Implementation**

| File | Role |
|---|---|
| [`Crawler.java`](../hms-components/src/main/java/com/hms/components/crawler/Crawler.java#L96) | `run` (line 96), `enqueue` with the "URL seen" check (128), `process` (194), politeness `waitForTurn` (250) |
| [`RobotsRules.java`](../hms-components/src/main/java/com/hms/components/crawler/RobotsRules.java#L24) | reads `robots.txt` |
| [`UrlNormalizer.java`](../hms-components/src/main/java/com/hms/components/crawler/UrlNormalizer.java#L21) | reduces different spellings of a URL to one |
| [`HtmlParser.java`](../hms-components/src/main/java/com/hms/components/crawler/HtmlParser.java#L33) | extracts links and the marked-up fields of a page |
| [`CrawlConfig.java`](../hms-components/src/main/java/com/hms/components/crawler/CrawlConfig.java), [`CrawlStats.java`](../hms-components/src/main/java/com/hms/components/crawler/CrawlStats.java) | settings and results of one crawl |
| [`sim/MockSites.java`](../hms-components/src/main/java/com/hms/sim/MockSites.java) | the two simulated external websites it crawls (not part of the crawler itself) |

**What it crawls.** Two simulated sites, started by the application on ports 9101 and 9102: *MedInfo* (drug
reference pages) and *InsureCo* (insurance providers and plans). No real website is contacted.

**Where the application uses it** — [`CrawlService.java`](../hms-app/src/main/java/com/hms/service/CrawlService.java)

| Line | What happens |
|---|---|
| [60](../hms-app/src/main/java/com/hms/service/CrawlService.java#L60) | the simulated sites are started |
| [73](../hms-app/src/main/java/com/hms/service/CrawlService.java#L73) | `start()` runs a crawl in the background |
| [100–101](../hms-app/src/main/java/com/hms/service/CrawlService.java#L100) | **the call**: `new Crawler(config, page -> …).run()` |
| [103](../hms-app/src/main/java/com/hms/service/CrawlService.java#L103) | a drug page is saved to MongoDB as `DrugInfo` (collection `drug_info`) |
| [107](../hms-app/src/main/java/com/hms/service/CrawlService.java#L107) | an insurance plan page is saved as `InsurancePlan` (collection `insurance_plans`) |
| [115–116](../hms-app/src/main/java/com/hms/service/CrawlService.java#L115) | billing and the medicine index are refreshed, so the new data is used straight away |

It is started automatically the first time the application runs ([`Bootstrap` line 88](../hms-app/src/main/java/com/hms/service/Bootstrap.java#L88)) and on demand by `POST /api/admin/crawl` ([`AdminController` line 144](../hms-app/src/main/java/com/hms/api/AdminController.java#L144)).

**Where the crawled data is then used — this is the crawler's effect on the hospital functions**

| Hospital function | Code | What the crawled data does |
|---|---|---|
| Billing | [`BillingService` line 78](../hms-app/src/main/java/com/hms/service/BillingService.java#L78) | the insurer's share of every new bill is taken from the patient's crawled plan |
| Billing | [`BillingController` line 75](../hms-app/src/main/java/com/hms/api/BillingController.java#L75) (`GET /api/insurance/plans`) | the list of plans a patient can choose from |
| Pharmacy | [`PharmacyService` line 121](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L121) | a medicine's page shows the crawled uses and side effects next to the pharmacy's stock and price |
| Medicine search | [`PharmacyService` line 81](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L81) | drugs the pharmacy does not stock still appear in suggestions, marked "reference only" |

**See it working:** *System* page, "Web crawler" card and "Run crawl now" ([`index.html` line 1565](../hms-app/src/main/resources/static/index.html#L1565)); *Bills & insurance* page, "Insurance plan" (the plans listed there were crawled); *Medicines* page, pick a medicine and read "Reference information". Step 10 of `run.bat demo` books an appointment with a crawled plan applied.

**Settings:** `hms.mock-sites.enabled`, `hms.mock-sites.base-port`, `hms.mock-sites.latency-ms`, `hms.mock-sites.drugs`.

**Tested in** [`KvAndCrawlerTest`](../hms-components/src/test/java/com/hms/components/KvAndCrawlerTest.java#L157) lines 157, 170, 191, 205 and 234.
**Measured in** [`ComponentBench.crawler`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L838); RESULTS.md section 5.8.

---

## 6. Consistent hashing

**What it is.** A hash ring with 150 virtual points per server. It decides which cache server holds each key,
in such a way that when a server is lost or added only that server's share of the keys moves.

**Implementation**

| File | Role |
|---|---|
| [`HashRing.java`](../hms-components/src/main/java/com/hms/components/hashing/HashRing.java#L93) | `route` (line 93), `routeN` for the replicas (102), `rebuild` (59), `ownership` (120) |
| [`NodeRouter.java`](../hms-components/src/main/java/com/hms/components/hashing/NodeRouter.java) | the interface, and the hash function |
| [`ModuloRouter.java`](../hms-components/src/main/java/com/hms/components/hashing/ModuloRouter.java) | `hash mod N`, the baseline; used only in tests and benchmarks |

**Where the application uses it.** It has one job: routing for the key-value cache cluster.

| File and line | What happens |
|---|---|
| [`KvNodeManager` line 41](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L41) | the ring is created with `hms.kv.virtual-nodes` points per server |
| [`KvNodeManager` line 42](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L42) | the ring is handed to the cache cluster as its router |
| [`KvCluster` line 113](../hms-components/src/main/java/com/hms/components/kv/KvCluster.java#L113) | **every cache read, write and delete** asks the ring `routeN(key, replicas)` for the servers that hold the key |
| [`KvCluster` line 86](../hms-components/src/main/java/com/hms/components/kv/KvCluster.java#L86) | a server that fails a request is removed from the ring at once; its keys now fall to the next server |
| [`KvCluster` lines 99–101](../hms-components/src/main/java/com/hms/components/kv/KvCluster.java#L99) | a server that answers again is emptied and put back on the ring |
| [`KvNodeManager` line 84](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L84) | adding a server at run time puts it on the ring |
| [`KvNodeManager` line 120](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L120) | `ownership()` supplies the "Share of ring" column of the *System* tab |

**Which hospital functions depend on it:** indirectly, everything that is cached (see the table in section 7): sessions, patient profiles, records, appointments, slots, bills, prescriptions, short links.

**See it working:** *System* page, "Key-value cache cluster": a ring chart and a table show each server's share of the ring and its key count. Press "Stop" on a server ([`index.html` line 1528](../hms-app/src/main/resources/static/index.html#L1528)): the application keeps working, the other servers' shares grow, and the "Cache hit ratio" chart dips and recovers. "Add a server" ([line 1564](../hms-app/src/main/resources/static/index.html#L1564)) shows the shares even out again. The API behind these is `/api/admin/cluster…` ([`AdminController` lines 91–108](../hms-app/src/main/java/com/hms/api/AdminController.java#L91)).

**Settings:** `hms.kv.virtual-nodes` (150), `hms.kv.replicas` (2).

**Not to be confused with database sharding.** This ring spreads the *cache*. When the database runs as a
MongoDB sharded cluster (`run.bat sharded`), MongoDB spreads the *data* itself by its own hashed shard keys;
that is MongoDB's feature, not this component. [SHARDING.md](SHARDING.md) section 6 compares the two.

**Tested in** [`CoreComponentsTest`](../hms-components/src/test/java/com/hms/components/CoreComponentsTest.java#L146) lines 146, 156 and 184.
**Measured in** [`ComponentBench.consistentHashing`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L325), [`ComponentBench.clusterTopology`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L773) and [`EndToEndBench.runTimelines`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L463); RESULTS.md sections 5.3, 5.5 and 4.

---

## 7. Key-value store

**What it is.** A small distributed in-memory cache: several servers, each a hash table with expiry times and
a least-recently-used size limit, reached over TCP. MongoDB remains the source of truth; the key-value store
holds copies of what is read often.

**Implementation**

| File | Role |
|---|---|
| [`KvEngine.java`](../hms-components/src/main/java/com/hms/components/kv/KvEngine.java#L72) | one server's storage: `get` (line 72), `set` (83), eviction (47) |
| [`KvNodeServer.java`](../hms-components/src/main/java/com/hms/components/kv/KvNodeServer.java#L109) | the TCP server in front of an engine; can also run as its own process |
| [`KvNodeClient.java`](../hms-components/src/main/java/com/hms/components/kv/KvNodeClient.java#L132) | pooled client for one server |
| [`KvCluster.java`](../hms-components/src/main/java/com/hms/components/kv/KvCluster.java#L122) | client for the whole cluster: `get` (122), `set` (144), `delete` (156), failure handling (82, 93) |

**Where the application uses it**

| File and line | What happens |
|---|---|
| [`KvNodeManager` line 57](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L57) | the cache servers are started (three by default, ports 7101–7103) |
| [`KvNodeManager` lines 42 and 50](../hms-app/src/main/java/com/hms/service/KvNodeManager.java#L42) | the cluster client is created and each server registered |
| [`CacheService` line 50](../hms-app/src/main/java/com/hms/service/CacheService.java#L50) | **every cached read**: `cluster.get(key)` |
| [`CacheService` line 58](../hms-app/src/main/java/com/hms/service/CacheService.java#L58) | on a miss the value is loaded from MongoDB and stored with `cluster.set(key, json, ttl)` |
| [`CacheService` line 66](../hms-app/src/main/java/com/hms/service/CacheService.java#L66) | after a write to MongoDB the affected keys are removed with `cluster.delete(key)` |

Services never talk to the cluster directly; they call `CacheService.get`, `getList` and `evict`.

**Everything that is cached**

| Key | Holds | Hospital function | Read at | Kept for | Removed when |
|---|---|---|---|---|---|
| `sess:{tokenHash}` | the logged-in session | authentication (checked on every request) | [`AuthService` 114](../hms-app/src/main/java/com/hms/service/AuthService.java#L114) | 10 min | logout ([121](../hms-app/src/main/java/com/hms/service/AuthService.java#L121)) |
| `patient:{id}` | patient profile | registration data, profile, billing | [`PatientService` 39](../hms-app/src/main/java/com/hms/service/PatientService.java#L39) | 10 min | insurance plan changed ([`BillingService` 69](../hms-app/src/main/java/com/hms/service/BillingService.java#L69)) |
| `records:{patientId}` | list of medical records | patient record access | [`PatientService` 70](../hms-app/src/main/java/com/hms/service/PatientService.java#L70) | 5 min | a record is added ([82](../hms-app/src/main/java/com/hms/service/PatientService.java#L82)) |
| `appts:{patientId}` | the patient's appointments | appointment management | [`AppointmentService` 142](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L142) | 2 min | booking ([136](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L136)), cancelling ([177](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L177)) |
| `slots:{doctorId}:{date}` | times already booked that day | appointment booking | [`AppointmentService` 88](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L88) | 1 min | booking, a lost race for a slot ([131](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L131)), cancelling |
| `doctor:{id}` | doctor profile and schedule | doctor management, booking | [`DoctorService` 152](../hms-app/src/main/java/com/hms/service/DoctorService.java#L152) | 10 min | expiry only |
| `bills:{patientId}` | the patient's bills | billing | [`BillingService` 88](../hms-app/src/main/java/com/hms/service/BillingService.java#L88) | 2 min | bill created ([83](../hms-app/src/main/java/com/hms/service/BillingService.java#L83)), paid ([120](../hms-app/src/main/java/com/hms/service/BillingService.java#L120)), voided ([128](../hms-app/src/main/java/com/hms/service/BillingService.java#L128)) |
| `rx:{patientId}` | the patient's prescriptions | pharmacy | [`PharmacyService` 151](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L151) | 2 min | prescribed ([146](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L146)), dispensed ([223](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L223)) |
| `druginfo:{name}` | crawled drug information | pharmacy | [`PharmacyService` 105](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L105) | 30 min | expiry only |
| `short:{code}` | where a short link points | URL shortener | [`ShortLinkService` 58](../hms-app/src/main/java/com/hms/service/ShortLinkService.java#L58) | 10 min | expiry only |

**Where it is deliberately not used:** medicine stock, the booking decision itself, bill payment and
ambulance assignment always go straight to MongoDB, because they must be decided on the current value, not on
a copy. Emergency requests are not cached either.

**See it working:** *System* page: keys, hits and misses per server, the "Key-value store" tile and the live "Cache hit ratio" chart. Turn the cache off in the *Switches* card and everything still works, more slowly, from MongoDB. Step 15 of `run.bat demo`.

**Settings:** `hms.kv.embedded`, `hms.kv.nodes` (3), `hms.kv.base-port` (7101), `hms.kv.replicas` (2), `hms.kv.max-entries-per-node` (200,000), `hms.cache.enabled`.

**Tested in** [`KvAndCrawlerTest`](../hms-components/src/test/java/com/hms/components/KvAndCrawlerTest.java#L36) lines 36, 62, 85 and 131.
**Measured in** [`ComponentBench.keyValueStore`](../hms-bench/src/main/java/com/hms/bench/ComponentBench.java#L627), [`EndToEndBench.cacheBaseline`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L354), [`EndToEndBench.dataGrowth`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L303) and [`EndToEndBench.runTimelines`](../hms-bench/src/main/java/com/hms/bench/EndToEndBench.java#L463); RESULTS.md sections 5.4, 3.1, 2 and 4.

---

## The same map, read from the hospital functions

| Hospital function | Components involved | Entry point in the code |
|---|---|---|
| Patient registration | rate limiter (`AUTH`), ID generator (`PAT`) | [`AuthService.registerPatient`](../hms-app/src/main/java/com/hms/service/AuthService.java#L84) |
| Authentication | rate limiter (`AUTH`), key-value store + consistent hashing (`sess:`) | [`AuthService.login`](../hms-app/src/main/java/com/hms/service/AuthService.java), [`AuthService.authenticate`](../hms-app/src/main/java/com/hms/service/AuthService.java#L114), [`AuthInterceptor` line 39](../hms-app/src/main/java/com/hms/web/AuthInterceptor.java#L39) |
| Doctor search | rate limiter (`SEARCH`), autocomplete | [`DoctorService.suggest` / `search`](../hms-app/src/main/java/com/hms/service/DoctorService.java#L99) |
| Doctor management | ID generator (`DOC`), autocomplete (a new doctor is indexed at once), key-value store (`doctor:`) | [`AdminController.addDoctor`](../hms-app/src/main/java/com/hms/api/AdminController.java#L159), [`DoctorService.add`](../hms-app/src/main/java/com/hms/service/DoctorService.java) |
| Appointment booking | rate limiter (`WRITE`), ID generator (`APT`, `BIL`), key-value store + consistent hashing (`slots:`, `appts:`, `doctor:`), autocomplete (ranking) | [`AppointmentService.book`](../hms-app/src/main/java/com/hms/service/AppointmentService.java#L124) |
| Patient record access | rate limiter (`READ`), key-value store + consistent hashing (`records:`, `patient:`), ID generator (`REC`) | [`PatientService.records` / `addRecord`](../hms-app/src/main/java/com/hms/service/PatientService.java#L70) |
| Prescriptions and pharmacy | autocomplete, web crawler (drug information), ID generator (`RX`, `BIL`), key-value store (`rx:`, `druginfo:`) | [`PharmacyService`](../hms-app/src/main/java/com/hms/service/PharmacyService.java#L93) |
| Billing | ID generator (`BIL`), web crawler (insurance plans), URL shortener, key-value store (`bills:`) | [`BillingService.create`](../hms-app/src/main/java/com/hms/service/BillingService.java#L78), [`BillingController.share`](../hms-app/src/main/java/com/hms/api/BillingController.java#L70) |
| Emergency requests | rate limiter (its own `EMERGENCY` bucket), ID generator (`EMR`) | [`EmergencyService.raise`](../hms-app/src/main/java/com/hms/service/EmergencyService.java#L77) |

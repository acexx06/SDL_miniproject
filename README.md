# Scalable Hospital Management System

A working prototype of a hospital management system built with **Java 17, Spring Boot 4 and MongoDB**,
designed around seven system-design components that are implemented from scratch in this repository:

| # | Component | Where it is used |
|---|-----------|------------------|
| 1 | **Rate limiter** (token bucket) | First filter on every request; separate allowances for login, search, writes, short links and emergencies |
| 2 | **Unique ID generator** (Snowflake) | Every patient, doctor, appointment, record, prescription, bill and emergency id |
| 3 | **Autocomplete** (trie with cached top-k) | Doctor / specialization search and medicine search, with typo tolerance |
| 4 | **URL shortener** | Short links for sharing a bill |
| 5 | **Web crawler** | Collects drug reference pages and insurance plans from two simulated external websites |
| 6 | **Consistent hashing** | Decides which cache server holds each key, and limits the damage when a server is lost or added |
| 7 | **Key-value store** | Distributed in-memory cache (TTL + LRU) in front of MongoDB for sessions, profiles, records, slots, bills |

All data is synthetic. No real patient or medical data is used anywhere.

- **[docs/SUMMARY.md](docs/SUMMARY.md)** — start here: the whole project and its results on a few pages
- **[docs/TEST_REPORT.md](docs/TEST_REPORT.md)** — base system against the final scaled system under the same load (generated)
- **[docs/REPORT.md](docs/REPORT.md)** — requirement analysis, component mapping with justification, architecture, evaluation and discussion
- **[docs/COMPONENT_USAGE.md](docs/COMPONENT_USAGE.md)** — exactly where each of the seven components is used: file, line, endpoint and screen
- **[docs/SHARDING.md](docs/SHARDING.md)** — the system on a MongoDB sharded cluster: shard keys, data spread, a shard added under load, a shard failure
- **[docs/RESULTS.md](docs/RESULTS.md)** — every measured number as tables and graphs (generated)

## Requirements

- JDK 17 or newer
- Maven 3.9+
- MongoDB running on `localhost:27017` (no authentication; any recent version)
- Only for the sharded set-up: the MongoDB server programs `mongod` and `mongos`, version 7.1 or newer
  (they come with the normal MongoDB server installation)

## Build and run

On Windows, double-click **`run.bat`** or run it from a terminal (in PowerShell type `.\run.bat`):

| Command | What it does |
|---------|--------------|
| `run.bat` | builds the project if needed, checks that MongoDB is up, starts the application and opens the browser |
| `run.bat start --hms.kv.nodes=5` | same, passing extra settings to the application |
| `run.bat build` | compiles everything and runs the unit tests |
| `run.bat test` | runs the unit tests only |
| `run.bat demo` | scripted walk-through of every workflow (the application must be running in another window) |
| `run.bat bench all --quick` | performance evaluation; anything after `bench` goes to the benchmark tool |
| `run.bat sharded` | starts a local MongoDB **sharded cluster** (router, config server, two shards), then the application on it |
| `run.bat cluster status` | manages that cluster: `status`, `add-shard shard3 27203`, `kill shard2`, `revive shard2 27202`, `stop`, `reset` ([docs/SHARDING.md](docs/SHARDING.md)) |

Set `HMS_PORT` first to use another port (`set HMS_PORT=8089`), and `HMS_NO_BROWSER=1` to not open the browser.

Or by hand, on any operating system:

```
mvn package
java -jar hms-app/target/hms-app.jar
```

Then open **http://localhost:8088**. (The default is 8088 rather than 8080 because 8080 is often
already taken by other software; change it with `--server.port=...`.)

On first start with an empty database the application creates synthetic data (200 doctors, 400 medicines,
12 ambulances), starts a three-node cache cluster on ports 7101-7103, starts the two simulated external
websites on ports 9101-9102 and crawls them. That takes a few seconds.

To use another MongoDB server or database: `--spring.mongodb.uri=mongodb://host:27017/dbname`.
All settings are in [application.properties](hms-app/src/main/resources/application.properties).

### Demo accounts

| Role | Email | Password | What to try |
|------|-------|----------|-------------|
| Patient | `patient@hms.test` | `Patient@12345` | search a doctor, book, view bills and records, pick an insurance plan |
| Doctor | `doctor@hms.test` | `Doctor@12345` | see the schedule, add a record, write a prescription |
| Pharmacist | `pharmacist@hms.test` | `Pharma@12345` | dispense a prescription |
| Administrator | `admin@hms.test` | `Admin@12345` | the System page: live component figures and charts, stop / add a cache server, crawler, rate-limit test; add a doctor |

The sign-in page has one-click buttons for all four. You can also register a new patient there. To see the
doctor's side of a booking, book the doctor whose name is shown when you sign in with the doctor account.
Without an account only the ambulance request is available; everything else needs a sign-in. The navigation
shows each role only its own pages. The portal is white and orange, has an optional dark mode, and works on a phone.

## Scripted demonstration

With the application running:

```
java -jar hms-bench/target/hms-bench.jar demo
```

This walks through registration, authentication, doctor search, booking (including a rejected double
booking), record access control, prescription and dispensing, billing with insurance, payment, a short
link, an emergency request, the cluster view and the rate limiter, and checks each result. Wait about
15 seconds before running it a second time: the last step deliberately uses up the login allowance.

## Performance evaluation

```
java -jar hms-bench/target/hms-bench.jar all
```

runs everything (about 20 minutes) and regenerates `docs/RESULTS.md`, `docs/TEST_REPORT.md` and the graphs in `docs/img/`.
The parts can also be run one at a time:

| Command | What it measures | Output |
|---------|------------------|--------|
| `components` | each of the seven components on its own, against a baseline | `results/components.json` |
| `load` | the mixed workload at four load levels; data growth; cache, search and rate-limiter baselines | `results/load.json` |
| `timeline` | a cache server killed, restarted and another added while under load | `results/timeline.json` |
| `compare` | the same workload on the base system (no cache, database searches, no rate limiter) and the final scaled system, at three load levels | `results/compare.json` |
| `report` | draws the graphs and writes `docs/RESULTS.md` and `docs/TEST_REPORT.md` from the JSON files | `docs/` |

Add `--quick` for a short trial run. `load` and `timeline` start their own copy of the application on
port 8095 with a scratch database named `hms_bench`, so they do not disturb a running instance or its
data. Run them from the project root so the application jar is found.

## Project layout

```
hms-components/   the seven components and their unit tests; plain Java, no framework
    idgen/ ratelimit/ hashing/ kv/ autocomplete/ shortener/ crawler/
    sim/            synthetic data generators and the two simulated external websites
hms-app/          the Spring Boot + MongoDB application
    domain/         MongoDB documents
    service/        business logic; each service says which component it uses and why
    api/            REST controllers
    web/            rate-limit filter, authentication, error handling
    resources/static/index.html   the web portal
hms-bench/        load generator, component benchmarks, scenarios, graphs, demo
scripts/          end-to-end checks (business rules, web portal in a browser); the sharded-cluster script
docs/             summary, report, component map, generated results, graphs
results/          raw measurements (JSON)
```

Unit tests: `mvn test` (22 tests covering every component, including key-value fail-over over real
sockets and a full crawl of the simulated sites). Two more end-to-end checks (33 business rules, and a
49-check browser test of every role) are in [scripts/](scripts/README.md).

## API overview

| Area | Endpoints |
|------|-----------|
| Authentication | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/me` |
| Doctors | `GET /api/doctors/suggest?q=`, `GET /api/doctors/search?q=&specialization=`, `GET /api/doctors/{id}`, `GET /api/doctors/{id}/slots?date=` |
| Appointments | `POST /api/appointments`, `GET /api/appointments`, `POST /api/appointments/{id}/cancel`, `GET /api/doctor/appointments?date=` |
| Records | `GET /api/patients/{id}`, `GET /api/patients/{id}/records`, `POST /api/patients/{id}/records` |
| Pharmacy | `GET /api/medicines/suggest?q=`, `GET /api/medicines/{id}`, `POST /api/prescriptions`, `GET /api/prescriptions`, `POST /api/prescriptions/{id}/dispense` |
| Billing | `GET /api/bills`, `POST /api/bills/{id}/pay`, `POST /api/bills/{id}/share`, `GET /api/insurance/plans`, `PUT /api/me/insurance` |
| Emergency | `POST /api/emergency`, `GET /api/emergency/{id}`, `GET /api/emergency`, `POST /api/emergency/{id}/resolve` |
| Short links | `POST /api/shorten`, `GET /s/{code}` |
| Administration | `GET /api/admin/cluster`, `POST /api/admin/cluster/nodes`, `POST /api/admin/cluster/nodes/{id}/kill`, `.../restart`, `GET /api/admin/metrics`, `POST /api/admin/config`, `POST /api/admin/crawl`, `POST /api/admin/doctors` |

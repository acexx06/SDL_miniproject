# Verification scripts

Two end-to-end checks that run against a running copy of the application. They need Node.js (18+ for the
first, 22+ for the second) but nothing else: no `npm install`.

Both scripts create data (bookings, payments, prescriptions, emergencies), so run them against a test database,
for example:

```
java -jar hms-app/target/hms-app.jar --spring.mongodb.uri=mongodb://localhost:27017/hms_test
```

| Script | What it checks | Run |
|---|---|---|
| `business-rules-check.mjs` | 33 rules through the API: booking rules, cancellation frees the slot and voids the bill, twelve simultaneous bookings of one slot (exactly one may succeed), access control, adding a doctor, the emergency priority queue | `node scripts/business-rules-check.mjs` |
| `ui-check.mjs` | 49 checks through the web portal in a headless browser: as a visitor (only sign-in and the ambulance request are reachable), patient, doctor, pharmacist and administrator, in light, dark and phone layouts; saves a screenshot of every page and reports any JavaScript error | `node scripts/ui-check.mjs` |

Both take the application's address as the first argument (default `http://localhost:8088`). `ui-check.mjs`
also takes a folder for the screenshots (default `ui-check-output`) and the browser to use (default: Microsoft
Edge in its usual Windows location; Chrome works too).

The scripted workflow demonstration `run.bat demo` (16 steps) and the unit tests `run.bat test` (22 tests)
complete the set.

## Sharded cluster

`sharded-cluster.ps1` starts and manages a local MongoDB sharded cluster (router on port 27100, a config
server, two shards) for running the application on a sharded database. It is called by `run.bat sharded` and
`run.bat cluster ...`; see [docs/SHARDING.md](../docs/SHARDING.md) for the commands and the test results. Both
checks above pass against an application connected to it.

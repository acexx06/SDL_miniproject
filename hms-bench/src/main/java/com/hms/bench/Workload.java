package com.hms.bench;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The simulated hospital traffic used for every end-to-end measurement.
 *
 * Each virtual user is one thread acting as one logged-in patient with its own client address.
 * It repeatedly picks an operation according to {@link Op}'s weights, performs it, and then
 * pauses for a random "think time" before the next one, as a person using the portal would.
 * The number of virtual users is what sets the load level.
 */
public final class Workload {

    /** Operations and their share of the traffic, in percent. Reads dominate, as in a real portal. */
    public enum Op {
        SUGGEST_DOCTOR(21), SEARCH_DOCTOR(8), VIEW_SLOTS(8), BOOK_APPOINTMENT(6), MY_APPOINTMENTS(10),
        MY_RECORDS(12), MY_PROFILE(8), SUGGEST_MEDICINE(8), MEDICINE_DETAIL(4), MY_BILLS(6), PAY_BILL(3),
        SHARE_BILL(2), EMERGENCY(2), LOGIN(1), REGISTER(1),
        /** Not drawn directly: the second half of SHARE_BILL, following the short link just created. */
        FOLLOW_SHORT_LINK(0);

        final int weight;

        Op(int weight) {
            this.weight = weight;
        }
    }

    /** A patient login prepared before the run. */
    public static final class Account {
        final String email;
        final String password;
        final String clientIp;
        volatile String token;
        volatile String patientId;
        final Deque<String> unpaidBills = new ArrayDeque<>();
        String anyBill;

        Account(String email, String password, int index) {
            this.email = email;
            this.password = password;
            this.clientIp = "10." + (index >> 16 & 255) + "." + (index >> 8 & 255) + "." + (index & 255);
        }
    }

    /** Per-second counters, for experiments that need to see behaviour change during a run. */
    public static final class Timeline {
        final int seconds;
        final LongAdder[] requests;
        final LongAdder[] errors;
        final LatencyRecorder[] latency;
        volatile long startNanos;

        public Timeline(int seconds) {
            this.seconds = seconds;
            requests = new LongAdder[seconds];
            errors = new LongAdder[seconds];
            latency = new LatencyRecorder[seconds];
            for (int i = 0; i < seconds; i++) {
                requests[i] = new LongAdder();
                errors[i] = new LongAdder();
                latency[i] = new LatencyRecorder();
            }
        }

        void record(long finishedNanos, long elapsedNanos, boolean error) {
            int second = (int) ((finishedNanos - startNanos) / 1_000_000_000L);
            if (startNanos != 0 && second >= 0 && second < seconds) {
                requests[second].increment();
                latency[second].add(elapsedNanos);
                if (error) {
                    errors[second].increment();
                }
            }
        }
    }

    private static final String[] TIMES = {"09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30",
            "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30"};
    private static final String[] ZONES = {"Central", "North Gate", "South Park", "East Market", "West End",
            "Lakeside", "Old Town", "Tech Park"};
    private static final Op[] WHEEL = buildWheel();

    private final Http http;
    private final List<Account> accounts;
    private final List<String> doctorIds;
    private final List<String> medicineIds;
    private final List<String> doctorQueries;
    private final List<String> medicineQueries;
    private final List<String> bookingDates = new ArrayList<>();
    private final AtomicInteger registrations = new AtomicInteger();
    private final String runTag = Long.toString(System.currentTimeMillis(), 36);

    public Workload(Http http, List<Account> accounts, List<String> doctorIds, List<String> medicineIds,
                    List<String> doctorQueries, List<String> medicineQueries) {
        this.http = http;
        this.accounts = accounts;
        this.doctorIds = doctorIds;
        this.medicineIds = medicineIds;
        this.doctorQueries = doctorQueries;
        this.medicineQueries = medicineQueries;
        // Every seeded doctor works Monday to Friday, so those days are always bookable.
        for (LocalDate day = LocalDate.now().plusDays(1); bookingDates.size() < 60; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                bookingDates.add(day.toString());
            }
        }
    }

    private static Op[] buildWheel() {
        List<Op> wheel = new ArrayList<>();
        for (Op op : Op.values()) {
            for (int i = 0; i < op.weight; i++) {
                wheel.add(op);
            }
        }
        return wheel.toArray(new Op[0]);
    }

    private static <T> T pick(List<T> list, Random rng) {
        return list.get(rng.nextInt(list.size()));
    }

    public List<Account> accounts() {
        return accounts;
    }

    // ---- one run -------------------------------------------------------------------------------

    /** Mutable tallies for one run; everything a virtual user reports goes through {@link #record}. */
    private static final class Tally {
        final Map<Op, LatencyRecorder> perOp = new EnumMap<>(Op.class);
        final LatencyRecorder all = new LatencyRecorder();
        final LongAdder ok = new LongAdder();
        final LongAdder conflicts = new LongAdder();
        final LongAdder rateLimited = new LongAdder();
        final LongAdder errors = new LongAdder();
        final Map<String, LongAdder> errorKinds = new ConcurrentHashMap<>();
        final Timeline timeline;
        volatile long measureFrom;
        volatile long measureUntil;

        Tally(Timeline timeline) {
            this.timeline = timeline;
            for (Op op : Op.values()) {
                perOp.put(op, new LatencyRecorder());
            }
        }

        void record(Op op, long startNanos, Http.Response response) {
            long now = System.nanoTime();
            long elapsed = now - startNanos;
            int status = response.status();
            // 409 is a correct answer ("that slot is taken"), not a failure of the system.
            boolean error = !(status >= 200 && status < 400) && status != 409 && status != 429;
            if (timeline != null) {
                timeline.record(now, elapsed, error);
            }
            if (now < measureFrom || now >= measureUntil) {
                return;
            }
            if (status == 429) {
                rateLimited.increment();
                return; // rejected requests are counted, but their near-zero latency would flatter the averages
            }
            if (error) {
                errors.increment();
                errorKinds.computeIfAbsent(op + " -> " + status, k -> new LongAdder()).increment();
                return;
            }
            (status == 409 ? conflicts : ok).increment();
            perOp.get(op).add(elapsed);
            all.add(elapsed);
        }
    }

    /**
     * Runs {@code users} virtual users for {@code warmupSeconds + measureSeconds}; only requests
     * finishing inside the measurement window are counted.
     *
     * @param thinkMinMs,thinkMaxMs pause between a user's requests, drawn uniformly from this range
     * @param timeline              optional per-second recorder covering the whole run including warm-up
     */
    public Map<String, Object> run(int users, double warmupSeconds, double measureSeconds, long thinkMinMs,
                                   long thinkMaxMs, Timeline timeline) throws InterruptedException {
        if (users > accounts.size()) {
            throw new IllegalArgumentException("only " + accounts.size() + " accounts prepared for " + users + " users");
        }
        Tally tally = new Tally(timeline);
        long start = System.nanoTime();
        tally.measureFrom = start + (long) (warmupSeconds * 1e9);
        tally.measureUntil = tally.measureFrom + (long) (measureSeconds * 1e9);
        if (timeline != null) {
            timeline.startNanos = start;
        }
        CountDownLatch done = new CountDownLatch(users);
        for (int i = 0; i < users; i++) {
            Account account = accounts.get(i);
            Thread thread = new Thread(() -> {
                try {
                    Random rng = ThreadLocalRandom.current();
                    Thread.sleep(rng.nextLong(thinkMaxMs + 1)); // spread the users out instead of starting in step
                    while (System.nanoTime() < tally.measureUntil) {
                        perform(WHEEL[rng.nextInt(WHEEL.length)], account, rng, tally);
                        Thread.sleep(thinkMinMs + rng.nextLong(thinkMaxMs - thinkMinMs + 1));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "vu-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        done.await();

        long ok = tally.ok.sum();
        long conflicts = tally.conflicts.sum();
        long limited = tally.rateLimited.sum();
        long errors = tally.errors.sum();
        long total = ok + conflicts + limited + errors;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("measureSeconds", measureSeconds);
        result.put("thinkTimeMs", thinkMinMs + "-" + thinkMaxMs);
        result.put("requests", total);
        result.put("throughputRps", LatencyRecorder.round(total / measureSeconds));
        result.put("successRate", total == 0 ? 0 : LatencyRecorder.round((double) (ok + conflicts) / total));
        result.put("rateLimitedRate", total == 0 ? 0 : LatencyRecorder.round((double) limited / total));
        result.put("errorRate", total == 0 ? 0 : LatencyRecorder.round((double) errors / total));
        result.put("slotConflicts", conflicts);
        result.put("latencyMs", tally.all.summaryMs());
        Map<String, Object> perOp = new LinkedHashMap<>();
        for (Op op : Op.values()) {
            if (tally.perOp.get(op).count() > 0) {
                perOp.put(op.name(), tally.perOp.get(op).summaryMs());
            }
        }
        result.put("perOperation", perOp);
        if (!tally.errorKinds.isEmpty()) {
            Map<String, Object> kinds = new LinkedHashMap<>();
            tally.errorKinds.forEach((kind, count) -> kinds.put(kind, count.sum()));
            result.put("errors", kinds);
        }
        return result;
    }

    // ---- the operations --------------------------------------------------------------------

    private Http.Response call(Op op, Tally tally, String method, String path, Account account, Object body) {
        long start = System.nanoTime();
        Http.Response response = http.send(method, path, account.token, account.clientIp, body);
        tally.record(op, start, response);
        return response;
    }

    private void perform(Op op, Account a, Random rng, Tally tally) {
        switch (op) {
            case SUGGEST_DOCTOR -> call(op, tally, "GET", "/api/doctors/suggest?q=" + Http.enc(pick(doctorQueries, rng)), a, null);
            case SEARCH_DOCTOR -> call(op, tally, "GET", "/api/doctors/search?q=" + Http.enc(pick(doctorQueries, rng)), a, null);
            case VIEW_SLOTS -> call(op, tally, "GET", "/api/doctors/" + pick(doctorIds, rng) + "/slots?date=" + pick(bookingDates, rng), a, null);
            case BOOK_APPOINTMENT -> {
                Http.Response r = call(op, tally, "POST", "/api/appointments", a, Map.of("doctorId", pick(doctorIds, rng),
                        "date", pick(bookingDates, rng), "time", TIMES[rng.nextInt(TIMES.length)], "reason", "Load test visit"));
                if (r.status() == 201) {
                    String billId = Json.at(r.json(), "billId");
                    synchronized (a) {
                        a.unpaidBills.add(billId);
                        a.anyBill = billId;
                    }
                }
            }
            case MY_APPOINTMENTS -> call(op, tally, "GET", "/api/appointments", a, null);
            case MY_RECORDS -> call(op, tally, "GET", "/api/patients/" + a.patientId + "/records", a, null);
            case MY_PROFILE -> call(op, tally, "GET", "/api/me", a, null);
            case SUGGEST_MEDICINE -> call(op, tally, "GET", "/api/medicines/suggest?q=" + Http.enc(pick(medicineQueries, rng)), a, null);
            case MEDICINE_DETAIL -> call(op, tally, "GET", "/api/medicines/" + pick(medicineIds, rng), a, null);
            case MY_BILLS -> call(op, tally, "GET", "/api/bills", a, null);
            case PAY_BILL -> {
                String billId;
                synchronized (a) {
                    billId = a.unpaidBills.poll();
                }
                if (billId == null) {
                    call(Op.MY_BILLS, tally, "GET", "/api/bills", a, null);
                } else {
                    call(op, tally, "POST", "/api/bills/" + billId + "/pay", a, Map.of("method", "UPI"));
                }
            }
            case SHARE_BILL -> {
                String billId;
                synchronized (a) {
                    billId = a.anyBill;
                }
                if (billId == null) {
                    call(Op.MY_BILLS, tally, "GET", "/api/bills", a, null);
                    return;
                }
                Http.Response shared = call(op, tally, "POST", "/api/bills/" + billId + "/share", a, null);
                if (shared.ok()) {
                    call(Op.FOLLOW_SHORT_LINK, tally, "GET", "/s/" + Json.at(shared.json(), "code"), a, null);
                }
            }
            case EMERGENCY -> call(op, tally, "POST", "/api/emergency", a, Map.of("callerName", "Load Test Caller",
                    "phone", "9000000000", "zone", ZONES[rng.nextInt(ZONES.length)], "severity", 1 + rng.nextInt(5),
                    "description", "Synthetic emergency"));
            case LOGIN -> {
                Http.Response r = call(op, tally, "POST", "/api/auth/login", a, Map.of("email", a.email, "password", a.password));
                if (r.ok()) {
                    a.token = Json.at(r.json(), "token");
                }
            }
            case REGISTER -> call(op, tally, "POST", "/api/auth/register", a, Map.of(
                    "name", "Load Test Patient", "email", "lt-" + runTag + "-" + registrations.incrementAndGet() + "@synthetic.test",
                    "password", "Password@123", "phone", "9000000000", "dateOfBirth", "1990-01-01", "gender", "Other",
                    "bloodGroup", "O+"));
            case FOLLOW_SHORT_LINK -> { }
        }
    }
}

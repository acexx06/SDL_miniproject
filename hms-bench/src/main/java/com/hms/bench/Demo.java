package com.hms.bench;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Walks through every major workflow against a running application and prints what happened,
 * failing loudly if any step does not behave as expected. It is both the scripted demonstration
 * of the prototype and its end-to-end smoke test.
 */
public final class Demo {

    private final Http http;
    private int step;

    public Demo(String baseUrl) {
        this.http = new Http(baseUrl);
    }

    private void say(String title, String detail) {
        System.out.printf("%2d. %-34s %s%n", ++step, title, detail);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("DEMO FAILED: " + message);
        }
    }

    private String login(String email, String password) {
        return Json.at(http.post("/api/auth/login", null, Map.of("email", email, "password", password)).require().json(), "token");
    }

    private static String rupees(Object paise) {
        return String.format("Rs %.2f", ((Number) paise).doubleValue() / 100);
    }

    /** The first bookable {date, time} for a doctor on a weekday on or after {@code from}. */
    private String[] firstFreeSlot(String doctorId, LocalDate from) {
        for (LocalDate date = from; date.isBefore(from.plusDays(60)); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            List<Map<String, Object>> slots = Json.at(http.get("/api/doctors/" + doctorId + "/slots?date=" + date, null).require().json());
            for (Map<String, Object> slot : slots) {
                if (Boolean.TRUE.equals(slot.get("available"))) {
                    return new String[]{date.toString(), (String) slot.get("time")};
                }
            }
        }
        throw new IllegalStateException("DEMO FAILED: no free slot found in the next 60 days");
    }

    public void run() {
        System.out.println("HMS workflow demonstration against " + http.baseUrl());
        check(http.get("/api/health", null).ok(), "application is not reachable; start it first");

        // --- patient registration and authentication ---
        String email = "demo-" + Long.toString(System.currentTimeMillis(), 36) + "@synthetic.test";
        Object registered = http.post("/api/auth/register", null, Map.of("name", "Riya Demo", "email", email,
                "password", "Password@123", "phone", "9000000001", "dateOfBirth", "1994-04-12", "gender", "Female",
                "bloodGroup", "B+")).require().json();
        String patientId = Json.at(registered, "user", "id");
        say("Patient registration", "new patient " + patientId + " (Snowflake ID) for " + email);
        check(http.post("/api/auth/register", null, Map.of("name", "Riya Demo", "email", email, "password", "Password@123"))
                .status() == 409, "duplicate email must be rejected");
        check(http.post("/api/auth/login", null, Map.of("email", email, "password", "wrong-password")).status() == 401,
                "wrong password must be rejected");
        String patient = login(email, "Password@123");
        say("Authentication", "duplicate email -> 409, wrong password -> 401, correct login -> session token");

        // --- doctor search ---
        List<Map<String, Object>> suggestions = Json.at(http.get("/api/doctors/suggest?q=card", null).require().json());
        check(!suggestions.isEmpty() && "Cardiology".equals(suggestions.get(0).get("text")), "'card' should suggest Cardiology");
        List<Map<String, Object>> typo = Json.at(http.get("/api/doctors/suggest?q=cardoilogy", null).require().json());
        check(!typo.isEmpty() && "Cardiology".equals(typo.get(0).get("text")), "typo should still find Cardiology");
        say("Doctor autocomplete", "'card' -> " + suggestions.get(0).get("text") + " (" + suggestions.get(0).get("detail")
                + "); typo 'cardoilogy' -> " + typo.get(0).get("text"));

        String doctor = login("doctor@hms.test", "Doctor@12345");
        Object doctorMe = http.get("/api/me", doctor).require().json();
        String doctorId = Json.at(doctorMe, "user", "id");
        String doctorName = Json.at(doctorMe, "user", "name");
        String surname = doctorName.substring(doctorName.lastIndexOf(' ') + 1);
        List<Map<String, Object>> found = Json.at(http.get("/api/doctors/search?q=" + Http.enc(surname), null).require().json());
        check(found.stream().anyMatch(d -> doctorId.equals(d.get("id"))), "search by surname should find the demo doctor");
        say("Doctor search", "'" + surname + "' -> " + found.size() + " doctor(s), including " + doctorName);

        // --- appointment booking ---
        String[] slot = firstFreeSlot(doctorId, LocalDate.now().plusDays(1));
        LocalDate date = LocalDate.parse(slot[0]);
        String time = slot[1];
        Map<String, Object> booking = Map.of("doctorId", doctorId, "date", date.toString(), "time", time, "reason", "Demo consultation");
        Object appointment = http.post("/api/appointments", patient, booking).require().json();
        String billId = Json.at(appointment, "billId");
        say("Appointment booking", Json.at(appointment, "id") + " with " + doctorName + " on " + date + " at " + time);
        check(http.post("/api/appointments", patient, booking).status() == 409, "the same slot must not be bookable twice");
        say("Double-booking prevented", "second request for the same slot -> 409 (unique index in MongoDB)");

        // --- patient records ---
        List<Map<String, Object>> schedule = Json.at(http.get("/api/doctor/appointments?date=" + date, doctor).require().json());
        check(schedule.stream().anyMatch(a -> patientId.equals(a.get("patientId"))), "doctor's schedule should list the booking");
        http.post("/api/patients/" + patientId + "/records", doctor, Map.of("type", "DIAGNOSIS", "title", "Seasonal allergy",
                "details", "Synthetic demo entry.")).require();
        List<Map<String, Object>> records = Json.at(http.get("/api/patients/" + patientId + "/records", patient).require().json());
        check(records.size() == 1, "patient should see the new record");
        String other = login("patient@hms.test", "Patient@12345");
        check(http.get("/api/patients/" + patientId + "/records", other).status() == 403, "another patient must not read these records");
        say("Patient record access", "doctor adds a record; the patient reads it; another patient -> 403");

        // --- prescription and pharmacy ---
        List<Map<String, Object>> medicines = Json.at(http.get("/api/medicines/suggest?q=zyv", null).require().json());
        Map<String, Object> medicine = medicines.stream().filter(m -> Boolean.TRUE.equals(m.get("stocked"))).findFirst().orElseThrow();
        Object detail = http.get("/api/medicines/" + medicine.get("id"), null).require().json();
        int stockBefore = ((Number) Json.at(detail, "medicine", "stock")).intValue();
        Object prescription = http.post("/api/prescriptions", doctor, Map.of("patientId", patientId, "notes", "After food",
                "items", List.of(Map.of("medicineId", medicine.get("id"), "quantity", 6, "dosage", "1 tablet twice a day")))).require().json();
        String rxId = Json.at(prescription, "id");
        say("Medicine autocomplete + prescription", "'zyv' -> " + medicine.get("name") + "; prescription " + rxId
                + (Json.at(detail, "info") != null ? " (crawled drug information attached)" : ""));
        String pharmacist = login("pharmacist@hms.test", "Pharma@12345");
        Object dispensed = http.post("/api/prescriptions/" + rxId + "/dispense", pharmacist, null).require().json();
        int stockAfter = ((Number) Json.at(http.get("/api/medicines/" + medicine.get("id"), null).require().json(), "medicine", "stock")).intValue();
        check(stockAfter == stockBefore - 6, "stock should drop by the dispensed quantity");
        check(http.post("/api/prescriptions/" + rxId + "/dispense", pharmacist, null).status() == 409, "a prescription is dispensed once");
        say("Pharmacy dispensing", "stock " + stockBefore + " -> " + stockAfter + ", pharmacy bill "
                + rupees(Json.at(dispensed, "bill", "payablePaise")) + "; second dispense -> 409");

        // --- billing, insurance, short link ---
        List<Map<String, Object>> plans = Json.at(http.get("/api/insurance/plans", null).require().json());
        check(!plans.isEmpty(), "insurance plans should have been crawled");
        Map<String, Object> plan = plans.get(plans.size() - 1);
        http.send("PUT", "/api/me/insurance", patient, null, Map.of("planId", plan.get("id"))).require();
        String[] followUp = firstFreeSlot(doctorId, date.plusDays(1));
        Object insured = http.post("/api/appointments", patient, Map.of("doctorId", doctorId, "date", followUp[0],
                "time", followUp[1], "reason", "Follow-up")).require().json();
        Object insuredBill = http.get("/api/bills/" + Json.at(insured, "billId"), patient).require().json();
        check(Json.num(insuredBill, "coveragePercent") == ((Number) plan.get("consultation")).doubleValue(), "insurance cover should apply");
        say("Billing with insurance", plan.get("provider") + " " + plan.get("name") + " covers " + plan.get("consultation")
                + "%: " + rupees(Json.at(insuredBill, "subtotalPaise")) + " -> payable " + rupees(Json.at(insuredBill, "payablePaise")));

        Object paid = http.post("/api/bills/" + billId + "/pay", patient, Map.of("method", "UPI")).require().json();
        Object paidAgain = http.post("/api/bills/" + billId + "/pay", patient, Map.of("method", "CARD")).require().json();
        check("PAID".equals(Json.at(paid, "status")) && "UPI".equals(Json.at(paidAgain, "paymentMethod")), "payment must be applied once");
        List<Map<String, Object>> bills = Json.at(http.get("/api/bills", patient).require().json());
        say("Bill payment", bills.size() + " bills; " + billId + " paid by UPI; a repeated payment request changes nothing");

        Object link = http.post("/api/bills/" + billId + "/share", patient, null).require().json();
        Http.Response redirect = http.get("/s/" + Json.at(link, "code"), null);
        check(redirect.status() == 302 && ("/#/bills/" + billId).equals(redirect.location()), "short link should redirect to the bill");
        check(http.get("/s/AAAAAAAA", null).status() == 404, "unknown short code -> 404");
        say("URL shortener", Json.at(link, "shortUrl") + " -> 302 " + redirect.location());

        // --- emergency ---
        Object emergency = http.post("/api/emergency", null, Map.of("callerName", "Demo Caller", "phone", "9000000002",
                "zone", "Lakeside", "severity", 1, "description", "Synthetic demo emergency")).require().json();
        String emergencyId = Json.at(emergency, "id");
        say("Emergency request (no login)", emergencyId + " -> " + Json.at(emergency, "status")
                + ("DISPATCHED".equals(Json.at(emergency, "status")) ? ", " + Json.at(emergency, "ambulanceCallSign") + ", ETA "
                + Json.at(emergency, "etaMinutes") + " min" : ", queue position " + Json.at(emergency, "queuePosition")));
        Object resolved = http.post("/api/emergency/" + emergencyId + "/resolve", doctor, null).require().json();
        check("RESOLVED".equals(Json.at(resolved, "status")), "doctor should be able to resolve the emergency");

        // --- operations view ---
        String admin = login("admin@hms.test", "Admin@12345");
        Object cluster = http.get("/api/admin/cluster", admin).require().json();
        List<Map<String, Object>> nodes = Json.at(cluster, "nodes");
        StringBuilder shares = new StringBuilder();
        for (Map<String, Object> node : nodes) {
            shares.append(node.get("id")).append(' ').append(Math.round(((Number) node.get("ringShare")).doubleValue() * 100))
                    .append("% (").append(node.get("keys")).append(" keys)  ");
        }
        say("Consistent-hash cache cluster", shares.toString().trim());
        Object metrics = http.get("/api/admin/metrics", admin).require().json();
        say("Cache and ID generator", "cache hit ratio " + Math.round(Json.num(metrics, "cache", "hitRatio") * 100) + "%, "
                + (long) Json.num(metrics, "idGenerator", "generated") + " IDs issued by worker "
                + (long) Json.num(metrics, "idGenerator", "workerId"));
        Object crawl = Json.at(http.get("/api/admin/crawl", admin).require().json(), "lastRun");
        if (crawl != null && Json.at(crawl, "pagesFetched") != null) {
            say("Web crawler (last run)", (long) Json.num(crawl, "pagesFetched") + " pages, " + (long) Json.num(crawl, "drugPagesStored")
                    + " drug pages, " + (long) Json.num(crawl, "insurancePlansStored") + " insurance plans, "
                    + Math.round(Json.num(crawl, "pagesPerSecond")) + " pages/s");
        }

        // --- rate limiter (last, because it uses up this client's login allowance) ---
        int rejected = 0;
        int reached = 0;
        for (int i = 0; i < 25; i++) {
            int status = http.post("/api/auth/login", null, Map.of("email", "nobody@hms.test", "password", "guess-" + i)).status();
            rejected += status == 429 ? 1 : 0;
            reached += status == 401 ? 1 : 0;
        }
        check(rejected > 0, "rapid login attempts should be rate limited");
        Http.Response stillWorks = http.post("/api/emergency", null, Map.of("callerName", "Demo Caller", "phone", "9000000002",
                "zone", "Central", "severity", 3, "description", "Sent while the login allowance is exhausted"));
        check(stillWorks.status() == 201, "an emergency request must still be accepted");
        http.post("/api/emergency/" + Json.at(stillWorks.json(), "id") + "/resolve", doctor, null);
        say("Rate limiter", "25 rapid login guesses: " + reached + " reached the password check, " + rejected
                + " rejected with 429; an emergency request sent right after was still accepted");

        System.out.println("All " + step + " workflow steps passed.");
    }
}

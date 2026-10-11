package com.hms.service;

import com.hms.domain.Bill;
import com.hms.domain.InsurancePlan;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Bills, insurance cover and payment.
 *
 * A bill's insurance share is fixed when the bill is created, from the plan the patient holds at
 * that moment. Plans come from the web crawler; until it has run there are none and bills are
 * simply payable in full.
 */
@Service
public class BillingService {

    private static final Set<String> PAYMENT_METHODS = Set.of("CARD", "UPI", "CASH", "NETBANKING");
    private static final Duration BILLS_TTL = Duration.ofMinutes(2);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final PatientService patients;
    private final Map<String, InsurancePlan> plans = new ConcurrentHashMap<>();

    public BillingService(MongoTemplate mongo, CacheService cache, PatientService patients) {
        this.mongo = mongo;
        this.cache = cache;
        this.patients = patients;
    }

    /** Plans change only when the crawler runs, so they are simply held in memory. */
    public void reloadPlans() {
        List<InsurancePlan> loaded = mongo.findAll(InsurancePlan.class);
        plans.clear();
        loaded.forEach(plan -> plans.put(plan.id(), plan));
    }

    public List<InsurancePlan> plans() {
        return plans.values().stream()
                .sorted(Comparator.comparing(InsurancePlan::provider).thenComparing(InsurancePlan::consultation))
                .toList();
    }

    public Patient setInsurance(String patientId, String planId) {
        if (planId != null && !planId.isBlank() && !plans.containsKey(planId)) {
            throw ApiException.notFound("Insurance plan");
        }
        String value = planId == null || planId.isBlank() ? null : planId;
        mongo.updateFirst(Query.query(where("_id").is(patientId)), new Update().set("insurancePlanId", value), Patient.class);
        cache.evict("patient:" + patientId);
        return patients.require(patientId);
    }

    /** Creates a bill under an id chosen by the caller, so the caller can reference it before it exists. */
    public Bill create(String billId, String patientId, Bill.Category category, String reference, List<Bill.Line> items) {
        long subtotal = items.stream().mapToLong(Bill.Line::amountPaise).sum();
        Patient patient = patients.require(patientId);
        InsurancePlan plan = patient.insurancePlanId() == null ? null : plans.get(patient.insurancePlanId());
        int percent = plan == null ? 0 : plan.coverageFor(category);
        long covered = subtotal * percent / 100;
        Bill bill = mongo.insert(new Bill(billId, patientId, category, reference, items, subtotal,
                plan == null ? null : plan.id(), percent, covered, subtotal - covered, Bill.Status.UNPAID, null,
                Instant.now(), null));
        cache.evict("bills:" + patientId);
        return bill;
    }

    public List<Bill> listForPatient(String patientId) {
        return cache.getList("bills:" + patientId, Bill.class, BILLS_TTL,
                () -> mongo.find(Query.query(where("patientId").is(patientId))
                        .with(Sort.by(Sort.Direction.DESC, "_id")).limit(100), Bill.class));
    }

    public Bill require(Session session, String billId) {
        Bill bill = mongo.findById(billId, Bill.class);
        if (bill == null) {
            throw ApiException.notFound("Bill");
        }
        if (session.role() == Role.PATIENT && !bill.patientId().equals(session.userId())) {
            throw ApiException.forbidden();
        }
        return bill;
    }

    /**
     * Marks a bill paid. The update only matches while the bill is still UNPAID, so two
     * simultaneous payment requests cannot both succeed; the second one just gets the paid bill.
     */
    public Bill pay(Session session, String billId, String method) {
        if (!PAYMENT_METHODS.contains(method)) {
            throw ApiException.badRequest("method must be one of " + PAYMENT_METHODS);
        }
        Bill bill = require(session, billId);
        if (bill.status() == Bill.Status.CANCELLED) {
            throw ApiException.conflict("This bill was cancelled");
        }
        Bill paid = mongo.findAndModify(
                Query.query(where("_id").is(billId).and("status").is(Bill.Status.UNPAID)),
                new Update().set("status", Bill.Status.PAID).set("paymentMethod", method).set("paidAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), Bill.class);
        cache.evict("bills:" + bill.patientId());
        return paid != null ? paid : mongo.findById(billId, Bill.class);
    }

    /** Voids the bill of a cancelled appointment, unless it has already been paid. */
    public void cancelIfUnpaid(String billId, String patientId) {
        mongo.updateFirst(Query.query(where("_id").is(billId).and("status").is(Bill.Status.UNPAID)),
                new Update().set("status", Bill.Status.CANCELLED), Bill.class);
        cache.evict("bills:" + patientId);
    }
}

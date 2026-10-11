package com.hms.service;

import com.hms.components.autocomplete.Autocomplete;
import com.hms.domain.Bill;
import com.hms.domain.DrugInfo;
import com.hms.domain.Medicine;
import com.hms.domain.Patient;
import com.hms.domain.Prescription;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.sim.SyntheticData;
import com.hms.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Medicine search, prescriptions and dispensing.
 *
 * Medicine names are suggested from an in-memory autocomplete index. It holds the pharmacy's own
 * stock and, once the crawler has run, drugs known only from the external reference site, which
 * are offered as "not stocked" so staff can still look them up.
 */
@Service
public class PharmacyService {

    /** @param stocked false for a drug known only from crawled reference data */
    public record MedicineRef(String id, String name, String category, String form, String strength, boolean stocked) {
    }

    public record MedicineDetail(Medicine medicine, DrugInfo info) {
    }

    public record ItemRequest(String medicineId, int quantity, String dosage) {
    }

    public record DispenseResult(Prescription prescription, Bill bill) {
    }

    private static final String INFO_PREFIX = "info:";

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final IdService ids;
    private final BillingService billing;
    private final PatientService patients;
    private volatile Autocomplete<MedicineRef> index = new Autocomplete<>(10, true);

    public PharmacyService(MongoTemplate mongo, CacheService cache, IdService ids, BillingService billing,
                           PatientService patients) {
        this.mongo = mongo;
        this.cache = cache;
        this.ids = ids;
        this.billing = billing;
        this.patients = patients;
    }

    /** Rebuilds the search index from the pharmacy stock plus crawled drug information. */
    public synchronized void reload() {
        Autocomplete<MedicineRef> fresh = new Autocomplete<>(10, true);
        Map<String, Boolean> stockedSlugs = new HashMap<>();
        for (Medicine m : mongo.findAll(Medicine.class)) {
            fresh.add(m.id(), m.name(), 10, new MedicineRef(m.id(), m.name(), m.category(), m.form(), m.strength(), true));
            stockedSlugs.put(SyntheticData.slug(m.name()), true);
        }
        for (DrugInfo info : mongo.findAll(DrugInfo.class)) {
            if (!stockedSlugs.containsKey(info.id())) {
                fresh.add(INFO_PREFIX + info.id(), info.name(), 1,
                        new MedicineRef(INFO_PREFIX + info.id(), info.name(), info.category(), info.form(), info.strength(), false));
            }
        }
        index = fresh;
    }

    public int indexedCount() {
        return index.size();
    }

    public List<MedicineRef> suggest(String q, int limit) {
        return index.suggest(q, limit).stream().map(Autocomplete.Suggestion::payload).toList();
    }

    /** Baseline for the evaluation: the same lookup as a database query. */
    public List<MedicineRef> searchDatabase(String q, int limit) {
        Query query = Query.query(where("name").regex("(^|\\s)" + Pattern.quote(q.trim()), "i"))
                .with(Sort.by("name")).limit(limit);
        return mongo.find(query, Medicine.class).stream()
                .map(m -> new MedicineRef(m.id(), m.name(), m.category(), m.form(), m.strength(), true)).toList();
    }

    private DrugInfo drugInfo(String slug) {
        return cache.get("druginfo:" + slug, DrugInfo.class, Duration.ofMinutes(30), () -> mongo.findById(slug, DrugInfo.class));
    }

    /** Stock and price from the pharmacy, joined with the crawled reference information when there is any. */
    public MedicineDetail detail(String id) {
        if (id.startsWith(INFO_PREFIX)) {
            DrugInfo info = drugInfo(id.substring(INFO_PREFIX.length()));
            if (info == null) {
                throw ApiException.notFound("Medicine");
            }
            return new MedicineDetail(null, info);
        }
        Medicine medicine = mongo.findById(id, Medicine.class);
        if (medicine == null) {
            throw ApiException.notFound("Medicine");
        }
        return new MedicineDetail(medicine, drugInfo(SyntheticData.slug(medicine.name())));
    }

    public Medicine restock(String id, int quantity) {
        Medicine medicine = mongo.findAndModify(Query.query(where("_id").is(id)), new Update().inc("stock", quantity),
                FindAndModifyOptions.options().returnNew(true), Medicine.class);
        if (medicine == null) {
            throw ApiException.notFound("Medicine");
        }
        return medicine;
    }

    public Prescription prescribe(Session doctor, String patientId, List<ItemRequest> requested, String notes) {
        Patient patient = patients.require(patientId);
        List<Prescription.Item> items = new ArrayList<>();
        for (ItemRequest request : requested) {
            Medicine medicine = mongo.findById(request.medicineId(), Medicine.class);
            if (medicine == null) {
                throw ApiException.badRequest("Unknown medicine " + request.medicineId());
            }
            items.add(new Prescription.Item(medicine.id(), medicine.name() + " " + medicine.strength(),
                    request.quantity(), request.dosage()));
        }
        Prescription prescription = mongo.insert(new Prescription(ids.next("RX"), patientId, patient.name(),
                doctor.userId(), doctor.name(), items, notes, Prescription.Status.ISSUED, null, Instant.now()));
        cache.evict("rx:" + patientId);
        return prescription;
    }

    public List<Prescription> listForPatient(String patientId) {
        return cache.getList("rx:" + patientId, Prescription.class, Duration.ofMinutes(2),
                () -> mongo.find(Query.query(where("patientId").is(patientId))
                        .with(Sort.by(Sort.Direction.DESC, "_id")).limit(100), Prescription.class));
    }

    /** Prescriptions waiting at the pharmacy counter, oldest first. */
    public List<Prescription> pending() {
        return mongo.find(Query.query(where("status").is(Prescription.Status.ISSUED))
                .with(Sort.by("_id")).limit(50), Prescription.class);
    }

    public Prescription require(Session session, String id) {
        Prescription prescription = mongo.findById(id, Prescription.class);
        if (prescription == null) {
            throw ApiException.notFound("Prescription");
        }
        if (session.role() == Role.PATIENT && !prescription.patientId().equals(session.userId())) {
            throw ApiException.forbidden();
        }
        return prescription;
    }

    private void setStatus(String id, Prescription.Status status) {
        mongo.updateFirst(Query.query(where("_id").is(id)), new Update().set("status", status), Prescription.class);
    }

    /**
     * Hands out a prescription: takes the items out of stock and raises the pharmacy bill.
     *
     * Each step is a conditional update, so concurrent requests cannot interfere. Moving the
     * prescription from ISSUED to DISPENSING succeeds for one caller only, and a stock decrement
     * applies only if enough units remain. If any item is short, the decrements already made are
     * put back and the prescription returns to ISSUED.
     */
    public DispenseResult dispense(String prescriptionId) {
        Prescription prescription = mongo.findAndModify(
                Query.query(where("_id").is(prescriptionId).and("status").is(Prescription.Status.ISSUED)),
                new Update().set("status", Prescription.Status.DISPENSING), Prescription.class);
        if (prescription == null) {
            if (mongo.findById(prescriptionId, Prescription.class) == null) {
                throw ApiException.notFound("Prescription");
            }
            throw ApiException.conflict("This prescription has already been dispensed");
        }

        List<Prescription.Item> taken = new ArrayList<>();
        List<Bill.Line> lines = new ArrayList<>();
        try {
            for (Prescription.Item item : prescription.items()) {
                Medicine medicine = mongo.findAndModify(
                        Query.query(where("_id").is(item.medicineId()).and("stock").gte(item.quantity())),
                        new Update().inc("stock", -item.quantity()), Medicine.class);
                if (medicine == null) {
                    throw ApiException.conflict("Not enough stock of " + item.name());
                }
                taken.add(item);
                lines.add(new Bill.Line(item.name() + " x " + item.quantity(), medicine.pricePaise() * item.quantity()));
            }
        } catch (RuntimeException e) {
            for (Prescription.Item item : taken) {
                mongo.updateFirst(Query.query(where("_id").is(item.medicineId())),
                        new Update().inc("stock", item.quantity()), Medicine.class);
            }
            setStatus(prescriptionId, Prescription.Status.ISSUED);
            throw e;
        }

        String billId = ids.next("BIL");
        Bill bill = billing.create(billId, prescription.patientId(), Bill.Category.PHARMACY, prescriptionId, lines);
        Prescription dispensed = mongo.findAndModify(Query.query(where("_id").is(prescriptionId)),
                new Update().set("status", Prescription.Status.DISPENSED).set("billId", billId),
                FindAndModifyOptions.options().returnNew(true), Prescription.class);
        cache.evict("rx:" + prescription.patientId());
        return new DispenseResult(dispensed, bill);
    }
}

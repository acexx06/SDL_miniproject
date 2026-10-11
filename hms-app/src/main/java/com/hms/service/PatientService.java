package com.hms.service;

import com.hms.domain.MedicalRecord;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Patient profiles and their medical records, both read through the cache. */
@Service
public class PatientService {

    private static final Set<String> RECORD_TYPES = Set.of("DIAGNOSIS", "LAB_RESULT", "VITALS", "NOTE");
    private static final Duration PROFILE_TTL = Duration.ofMinutes(10);
    private static final Duration RECORDS_TTL = Duration.ofMinutes(5);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final IdService ids;

    public PatientService(MongoTemplate mongo, CacheService cache, IdService ids) {
        this.mongo = mongo;
        this.cache = cache;
        this.ids = ids;
    }

    public Patient require(String id) {
        Patient patient = cache.get("patient:" + id, Patient.class, PROFILE_TTL, () -> mongo.findById(id, Patient.class));
        if (patient == null) {
            throw ApiException.notFound("Patient");
        }
        return patient;
    }

    public Patient findByEmail(String email) {
        Patient patient = mongo.findOne(Query.query(where("email").is(AuthService.normalizeEmail(email))), Patient.class);
        if (patient == null) {
            throw ApiException.notFound("Patient");
        }
        return patient;
    }

    /** Patients may see only their own data; staff may see any patient's profile. */
    public void checkProfileAccess(Session session, String patientId) {
        if (session.role() == Role.PATIENT && !session.userId().equals(patientId)) {
            throw ApiException.forbidden();
        }
    }

    /** Clinical records are narrower still: the patient, doctors and administrators, but not the pharmacy. */
    public void checkRecordAccess(Session session, String patientId) {
        checkProfileAccess(session, patientId);
        if (session.role() == Role.PHARMACIST) {
            throw ApiException.forbidden();
        }
    }

    public List<MedicalRecord> records(String patientId) {
        return cache.getList("records:" + patientId, MedicalRecord.class, RECORDS_TTL,
                () -> mongo.find(Query.query(where("patientId").is(patientId))
                        .with(Sort.by(Sort.Direction.DESC, "_id")).limit(200), MedicalRecord.class));
    }

    public MedicalRecord addRecord(Session author, String patientId, String type, String title, String details) {
        if (!RECORD_TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of " + RECORD_TYPES);
        }
        require(patientId);
        MedicalRecord record = mongo.insert(new MedicalRecord(ids.next("REC"), patientId, author.userId(),
                author.name(), type, title, details, Instant.now()));
        cache.evict("records:" + patientId);
        return record;
    }
}

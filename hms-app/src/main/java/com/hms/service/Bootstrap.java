package com.hms.service;

import com.hms.config.HmsProperties;
import com.hms.domain.Ambulance;
import com.hms.domain.Appointment;
import com.hms.domain.Bill;
import com.hms.domain.Doctor;
import com.hms.domain.EmergencyRequest;
import com.hms.domain.MedicalRecord;
import com.hms.domain.Medicine;
import com.hms.domain.Patient;
import com.hms.domain.Prescription;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.domain.ShortUrl;
import com.hms.domain.UserAccount;
import com.hms.sim.SyntheticData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Start-up work, in order: create the MongoDB indexes, load synthetic starting data if the
 * database is empty, build the in-memory search indexes, and start a first crawl if there is no
 * crawled data yet.
 */
@Component
public class Bootstrap implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@hms.test";
    public static final String ADMIN_PASSWORD = "Admin@12345";
    public static final String DOCTOR_EMAIL = "doctor@hms.test";
    public static final String DOCTOR_PASSWORD = "Doctor@12345";
    public static final String PHARMACIST_EMAIL = "pharmacist@hms.test";
    public static final String PHARMACIST_PASSWORD = "Pharma@12345";
    public static final String PATIENT_EMAIL = "patient@hms.test";
    public static final String PATIENT_PASSWORD = "Patient@12345";

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final MongoTemplate mongo;
    private final HmsProperties properties;
    private final IdService ids;
    private final AuthService auth;
    private final DoctorService doctors;
    private final PharmacyService pharmacy;
    private final BillingService billing;
    private final CrawlService crawl;

    public Bootstrap(MongoTemplate mongo, HmsProperties properties, IdService ids, AuthService auth,
                     DoctorService doctors, PharmacyService pharmacy, BillingService billing, CrawlService crawl) {
        this.mongo = mongo;
        this.properties = properties;
        this.ids = ids;
        this.auth = auth;
        this.doctors = doctors;
        this.pharmacy = pharmacy;
        this.billing = billing;
        this.crawl = crawl;
    }

    @Override
    public void run(ApplicationArguments args) {
        createIndexes();
        if (properties.seed().enabled() && mongo.count(new Query(), Doctor.class) == 0) {
            seed();
        }
        doctors.reload();
        pharmacy.reload();
        billing.reloadPlans();
        log.info("Search indexes ready: {} doctor entries, {} medicine entries", doctors.indexedCount(), pharmacy.indexedCount());
        if (properties.mockSites().enabled() && billing.plans().isEmpty()) {
            crawl.start(8, 0);
        }
    }

    private void createIndexes() {
        Sort.Direction asc = Sort.Direction.ASC;
        mongo.indexOps(UserAccount.class).createIndex(new Index().on("email", asc).unique());
        mongo.indexOps(Patient.class).createIndex(new Index().on("email", asc));
        // MongoDB deletes a session or short link by itself once its expiry time has passed.
        mongo.indexOps(Session.class).createIndex(new Index().on("expiresAt", asc).expire(Duration.ZERO));
        mongo.indexOps(ShortUrl.class).createIndex(new Index().on("expiresAt", asc).expire(Duration.ZERO));
        // The rule that makes double booking impossible: one BOOKED appointment per doctor, date and time.
        mongo.indexOps(Appointment.class).createIndex(new Index().on("doctorId", asc).on("date", asc).on("time", asc)
                .unique().partial(PartialIndexFilter.of(where("status").is("BOOKED"))));
        mongo.indexOps(Appointment.class).createIndex(new Index().on("patientId", asc).on("_id", Sort.Direction.DESC));
        mongo.indexOps(MedicalRecord.class).createIndex(new Index().on("patientId", asc).on("_id", Sort.Direction.DESC));
        mongo.indexOps(Prescription.class).createIndex(new Index().on("patientId", asc).on("_id", Sort.Direction.DESC));
        mongo.indexOps(Prescription.class).createIndex(new Index().on("status", asc));
        mongo.indexOps(Bill.class).createIndex(new Index().on("patientId", asc).on("_id", Sort.Direction.DESC));
        mongo.indexOps(EmergencyRequest.class).createIndex(new Index().on("status", asc).on("severity", asc));
        mongo.indexOps(Ambulance.class).createIndex(new Index().on("status", asc));
    }

    private void seed() {
        log.info("Empty database: loading synthetic data");
        Random rng = new Random(2025);

        List<Doctor> doctorDocs = new ArrayList<>();
        int room = 101;
        for (SyntheticData.DoctorSeed seed : SyntheticData.doctors(properties.seed().doctors())) {
            List<Integer> days = switch (room % 3) {
                case 0 -> List.of(1, 2, 3, 4, 5);
                case 1 -> List.of(1, 2, 3, 4, 5, 6);
                default -> List.of(1, 2, 3, 4, 5, 6, 7);
            };
            doctorDocs.add(new Doctor(ids.next("DOC"), seed.name(), seed.specialization(), seed.qualification(),
                    seed.experienceYears(), seed.feePaise(), seed.rating(), 0, days, "09:00", "17:00", 30, "R-" + room++));
        }
        mongo.insert(doctorDocs, Doctor.class);

        List<Medicine> medicines = new ArrayList<>();
        for (SyntheticData.Drug drug : SyntheticData.drugs(properties.seed().medicines())) {
            medicines.add(new Medicine(ids.next("MED"), drug.name(), drug.category(), drug.form(), drug.strength(),
                    drug.pricePaise(), 50 + rng.nextInt(450)));
        }
        mongo.insert(medicines, Medicine.class);

        List<Ambulance> ambulances = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            SyntheticData.Zone zone = SyntheticData.ZONES.get(i % SyntheticData.ZONES.size());
            ambulances.add(new Ambulance(String.format("AMB-%02d", i + 1), String.format("Ambulance %02d", i + 1),
                    zone.name(), zone.x() + rng.nextInt(9) - 4, zone.y() + rng.nextInt(9) - 4, Ambulance.Status.FREE, null));
        }
        mongo.insert(ambulances, Ambulance.class);

        // Demonstration logins. The demo doctor account belongs to the first seeded doctor.
        Doctor demoDoctor = doctorDocs.get(0);
        auth.createAccount(demoDoctor.id(), DOCTOR_EMAIL, DOCTOR_PASSWORD, demoDoctor.name(), Role.DOCTOR);
        auth.createAccount(ids.next("STF"), ADMIN_EMAIL, ADMIN_PASSWORD, "System Administrator", Role.ADMIN);
        auth.createAccount(ids.next("STF"), PHARMACIST_EMAIL, PHARMACIST_PASSWORD, "Pharmacy Desk", Role.PHARMACIST);
        String patientId = ids.next("PAT");
        auth.createAccount(patientId, PATIENT_EMAIL, PATIENT_PASSWORD, "Demo Patient", Role.PATIENT);
        mongo.insert(new Patient(patientId, "Demo Patient", PATIENT_EMAIL, "9000000000", "1995-06-15", "Other", "O+",
                null, Instant.now()));

        log.info("Seeded {} doctors, {} medicines, {} ambulances and 4 demo accounts", doctorDocs.size(),
                medicines.size(), ambulances.size());
    }
}

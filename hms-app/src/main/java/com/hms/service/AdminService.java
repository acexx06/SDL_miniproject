package com.hms.service;

import com.hms.components.idgen.Base62;
import com.hms.components.idgen.SnowflakeIdGenerator;
import com.hms.domain.Appointment;
import com.hms.domain.Bill;
import com.hms.domain.Doctor;
import com.hms.domain.MedicalRecord;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.UserAccount;
import com.hms.sim.SyntheticData;
import com.hms.web.ApiException;
import com.sun.management.OperatingSystemMXBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Operational helpers for administrators and for the evaluation: resource figures and bulk test data. */
@Service
public class AdminService {

    public static final String BULK_PASSWORD = "Password@123";
    private static final int BATCH = 1000;
    /** How many of the new patients are returned to the caller as ready-made test logins. */
    private static final int MAX_SAMPLE = 2000;
    private static final String[] RECORD_TYPES = {"DIAGNOSIS", "LAB_RESULT", "VITALS", "NOTE"};

    private final MongoTemplate mongo;
    private final IdService ids;
    private final AuthService auth;
    private final DoctorService doctors;

    public AdminService(MongoTemplate mongo, IdService ids, AuthService auth, DoctorService doctors) {
        this.mongo = mongo;
        this.ids = ids;
        this.auth = auth;
        this.doctors = doctors;
    }

    /** Inserts {@code count} synthetic doctors (without logins) and rebuilds the search index. */
    public Map<String, Object> seedDoctors(int count) {
        if (count < 1 || count > 100_000) {
            throw ApiException.badRequest("count must be between 1 and 100000");
        }
        Random rng = new Random();
        List<Doctor> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new Doctor(ids.next("DOC"), "Dr. " + SyntheticData.personName(rng),
                    SyntheticData.SPECIALIZATIONS.get(rng.nextInt(SyntheticData.SPECIALIZATIONS.size())), "MBBS, MD",
                    2 + rng.nextInt(34), (6 + rng.nextInt(25)) * 5000L, Math.round((3.5 + rng.nextDouble() * 1.5) * 10) / 10.0,
                    0, List.of(1, 2, 3, 4, 5), "09:00", "17:00", 30, "R-" + (1000 + i)));
            if (batch.size() == BATCH || i == count - 1) {
                mongo.insert(batch, Doctor.class);
                batch = new ArrayList<>();
            }
        }
        doctors.reload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", count);
        result.put("totalDoctors", mongo.estimatedCount(Doctor.class));
        return result;
    }

    /** CPU, memory and thread figures for this JVM, for the resource-usage part of the evaluation. */
    public Map<String, Object> resources() {
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processCpuLoad", os.getProcessCpuLoad());
        result.put("processCpuTimeMs", os.getProcessCpuTime() / 1_000_000);
        result.put("availableProcessors", runtime.availableProcessors());
        result.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        result.put("heapMaxMb", runtime.maxMemory() / (1024.0 * 1024.0));
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        result.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        return result;
    }

    public Map<String, Object> collectionSizes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patients", mongo.estimatedCount(Patient.class));
        result.put("doctors", mongo.estimatedCount(Doctor.class));
        result.put("appointments", mongo.estimatedCount(Appointment.class));
        result.put("records", mongo.estimatedCount(MedicalRecord.class));
        result.put("bills", mongo.estimatedCount(Bill.class));
        return result;
    }

    /**
     * Inserts {@code count} synthetic patients, each with a login, two medical records and one
     * past appointment, so the system can be measured at different data volumes. All of them share
     * {@link #BULK_PASSWORD}; the hash is computed once, which keeps this fast.
     */
    public Map<String, Object> seedPatients(int count) {
        if (count < 1 || count > 500_000) {
            throw ApiException.badRequest("count must be between 1 and 500000");
        }
        List<Doctor> doctors = mongo.find(new Query().limit(200), Doctor.class);
        if (doctors.isEmpty()) {
            throw ApiException.conflict("Seed doctors first");
        }
        String passwordHash = auth.hashPassword(BULK_PASSWORD);
        Random rng = new Random();
        List<String> sampleIds = new ArrayList<>();
        List<String> sampleEmails = new ArrayList<>();
        int sampleEvery = Math.max(1, count / MAX_SAMPLE);

        for (int done = 0; done < count; done += BATCH) {
            int size = Math.min(BATCH, count - done);
            List<UserAccount> accounts = new ArrayList<>(size);
            List<Patient> patients = new ArrayList<>(size);
            List<MedicalRecord> records = new ArrayList<>(size * 2);
            List<Appointment> appointments = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                long raw = ids.nextRaw();
                String id = "PAT" + Base62.encode(raw, SnowflakeIdGenerator.TEXT_WIDTH);
                String name = SyntheticData.personName(rng);
                // Emails are compared case-insensitively but base-62 ids are case-sensitive, so the email
                // is built from the number in base 36 rather than by lower-casing the id.
                String email = "p" + Long.toString(raw, 36) + "@synthetic.test";
                Instant now = Instant.now();
                accounts.add(new UserAccount(id, email, passwordHash, name, Role.PATIENT, now));
                patients.add(new Patient(id, name, email, "9" + (100_000_000 + rng.nextInt(900_000_000)),
                        LocalDate.of(1950 + rng.nextInt(60), 1 + rng.nextInt(12), 1 + rng.nextInt(28)).toString(),
                        rng.nextBoolean() ? "Female" : "Male", "O+", null, now));
                Doctor doctor = doctors.get(rng.nextInt(doctors.size()));
                for (int r = 0; r < 2; r++) {
                    String type = RECORD_TYPES[rng.nextInt(RECORD_TYPES.length)];
                    records.add(new MedicalRecord(ids.next("REC"), id, doctor.id(), doctor.name(), type,
                            "Synthetic " + type.toLowerCase(Locale.ROOT).replace('_', ' '),
                            "Generated entry for load testing. Not a real medical record.", now));
                }
                appointments.add(new Appointment(ids.next("APT"), id, name, doctor.id(), doctor.name(),
                        doctor.specialization(), LocalDate.now().minusDays(1 + rng.nextInt(365)).toString(),
                        String.format("%02d:%02d", 9 + rng.nextInt(8), rng.nextBoolean() ? 0 : 30),
                        Appointment.Status.COMPLETED, "Synthetic visit", null, now));
                if ((done + i) % sampleEvery == 0 && sampleIds.size() < MAX_SAMPLE) {
                    sampleIds.add(id);
                    sampleEmails.add(email);
                }
            }
            mongo.insert(accounts, UserAccount.class);
            mongo.insert(patients, Patient.class);
            mongo.insert(records, MedicalRecord.class);
            mongo.insert(appointments, Appointment.class);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", count);
        result.put("totalPatients", mongo.estimatedCount(Patient.class));
        result.put("password", BULK_PASSWORD);
        result.put("sampleIds", sampleIds);
        result.put("sampleEmails", sampleEmails);
        return result;
    }
}

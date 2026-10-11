package com.hms.service;

import com.hms.domain.Appointment;
import com.hms.domain.Bill;
import com.hms.domain.Doctor;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Appointment slots and booking.
 *
 * Double booking is prevented by the database rather than by checking first: a unique index over
 * (doctor, date, time) restricted to BOOKED appointments means that of two simultaneous requests
 * for the same slot exactly one insert succeeds, with no lock and no window in between.
 * Cancelling changes the status, which takes the appointment out of the index and frees the slot.
 */
@Service
public class AppointmentService {

    public record Slot(String time, boolean available) {
    }

    private static final int MAX_DAYS_AHEAD = 90;
    private static final Duration SLOTS_TTL = Duration.ofSeconds(60);
    private static final Duration LIST_TTL = Duration.ofMinutes(2);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final IdService ids;
    private final DoctorService doctors;
    private final BillingService billing;

    public AppointmentService(MongoTemplate mongo, CacheService cache, IdService ids, DoctorService doctors,
                              BillingService billing) {
        this.mongo = mongo;
        this.cache = cache;
        this.ids = ids;
        this.doctors = doctors;
        this.billing = billing;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("date must look like 2026-01-31");
        }
    }

    private static List<String> slotTimes(Doctor doctor, LocalDate date) {
        List<String> times = new ArrayList<>();
        if (!doctor.workingDays().contains(date.getDayOfWeek().getValue())) {
            return times;
        }
        LocalTime end = LocalTime.parse(doctor.endTime());
        for (LocalTime t = LocalTime.parse(doctor.startTime()); t.isBefore(end); t = t.plusMinutes(doctor.slotMinutes())) {
            times.add(t.toString());
        }
        return times;
    }

    private static String slotsKey(String doctorId, String date) {
        return "slots:" + doctorId + ":" + date;
    }

    private Set<String> bookedTimes(String doctorId, String date) {
        List<String> booked = cache.getList(slotsKey(doctorId, date), String.class, SLOTS_TTL,
                () -> mongo.findDistinct(Query.query(where("doctorId").is(doctorId).and("date").is(date)
                        .and("status").is(Appointment.Status.BOOKED)), "time", Appointment.class, String.class));
        return new HashSet<>(booked);
    }

    /** Every slot the doctor offers on that day, flagged with whether it can still be booked. */
    public List<Slot> slots(String doctorId, String dateText) {
        Doctor doctor = doctors.require(doctorId);
        LocalDate date = parseDate(dateText);
        Set<String> booked = bookedTimes(doctorId, dateText);
        boolean today = date.equals(LocalDate.now());
        boolean bookableDay = !date.isBefore(LocalDate.now()) && !date.isAfter(LocalDate.now().plusDays(MAX_DAYS_AHEAD));
        String now = LocalTime.now().toString();
        List<Slot> slots = new ArrayList<>();
        for (String time : slotTimes(doctor, date)) {
            boolean past = today && time.compareTo(now) <= 0;
            slots.add(new Slot(time, bookableDay && !past && !booked.contains(time)));
        }
        return slots;
    }

    public Appointment book(Session patient, String doctorId, String dateText, String time, String reason) {
        Doctor doctor = doctors.require(doctorId);
        LocalDate date = parseDate(dateText);
        LocalDate today = LocalDate.now();
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw ApiException.badRequest("Appointments can be booked from today up to " + MAX_DAYS_AHEAD + " days ahead");
        }
        if (!slotTimes(doctor, date).contains(time)) {
            throw ApiException.badRequest(doctor.name() + " has no slot at " + time + " on that day");
        }
        if (date.equals(today) && time.compareTo(LocalTime.now().toString()) <= 0) {
            throw ApiException.badRequest("That time has already passed");
        }

        String billId = ids.next("BIL");
        Appointment appointment = new Appointment(ids.next("APT"), patient.userId(), patient.name(), doctor.id(),
                doctor.name(), doctor.specialization(), dateText, time, Appointment.Status.BOOKED, reason, billId,
                Instant.now());
        try {
            mongo.insert(appointment);
        } catch (DuplicateKeyException e) {
            cache.evict(slotsKey(doctorId, dateText));
            throw ApiException.conflict("That slot has just been taken; please choose another");
        }
        billing.create(billId, patient.userId(), Bill.Category.CONSULTATION, appointment.id(),
                List.of(new Bill.Line("Consultation with " + doctor.name(), doctor.feePaise())));
        cache.evict(slotsKey(doctorId, dateText), "appts:" + patient.userId());
        doctors.recordBooking(doctorId);
        return appointment;
    }

    public List<Appointment> listForPatient(String patientId) {
        return cache.getList("appts:" + patientId, Appointment.class, LIST_TTL,
                () -> mongo.find(Query.query(where("patientId").is(patientId))
                        .with(Sort.by(Sort.Direction.DESC, "_id")).limit(100), Appointment.class));
    }

    /** A doctor's booked appointments for one day, in time order. */
    public List<Appointment> listForDoctor(String doctorId, String dateText) {
        parseDate(dateText);
        return mongo.find(Query.query(where("doctorId").is(doctorId).and("date").is(dateText)
                .and("status").is(Appointment.Status.BOOKED)).with(Sort.by("time")), Appointment.class);
    }

    public Appointment require(Session session, String id) {
        Appointment appointment = mongo.findById(id, Appointment.class);
        if (appointment == null) {
            throw ApiException.notFound("Appointment");
        }
        boolean owner = session.role() == Role.PATIENT && appointment.patientId().equals(session.userId());
        boolean treating = session.role() == Role.DOCTOR && appointment.doctorId().equals(session.userId());
        if (!owner && !treating && session.role() != Role.ADMIN) {
            throw ApiException.forbidden();
        }
        return appointment;
    }

    public Appointment cancel(Session session, String id) {
        Appointment existing = require(session, id);
        Criteria booked = where("_id").is(id).and("status").is(Appointment.Status.BOOKED);
        Appointment cancelled = mongo.findAndModify(Query.query(booked),
                new Update().set("status", Appointment.Status.CANCELLED),
                FindAndModifyOptions.options().returnNew(true), Appointment.class);
        if (cancelled == null) {
            throw ApiException.conflict("This appointment is no longer active");
        }
        billing.cancelIfUnpaid(existing.billId(), existing.patientId());
        cache.evict(slotsKey(existing.doctorId(), existing.date()), "appts:" + existing.patientId());
        return cancelled;
    }
}

package com.hms.api;

import com.hms.domain.Appointment;
import com.hms.domain.Doctor;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.AppointmentService;
import com.hms.service.DoctorService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Finding doctors is open to everyone; no login is needed to browse. */
@RestController
@RequestMapping("/api")
public class DoctorController {

    private final DoctorService doctors;
    private final AppointmentService appointments;

    public DoctorController(DoctorService doctors, AppointmentService appointments) {
        this.doctors = doctors;
        this.appointments = appointments;
    }

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    /** Autocomplete: called on every keystroke. */
    @GetMapping("/doctors/suggest")
    public List<DoctorService.Suggestion> suggest(@RequestParam String q, @RequestParam(defaultValue = "8") int limit) {
        return doctors.suggest(q, clamp(limit, 20));
    }

    /** @param mode "index" (default) uses the autocomplete index; "db" runs the baseline database query */
    @GetMapping("/doctors/search")
    public List<DoctorService.DoctorRef> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String specialization,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "index") String mode) {
        return "db".equals(mode) ? doctors.searchDatabase(q, specialization, clamp(limit, 50))
                : doctors.search(q, specialization, clamp(limit, 50));
    }

    @GetMapping("/doctors/specializations")
    public List<String> specializations() {
        return doctors.specializations();
    }

    @GetMapping("/doctors/{id}")
    public Doctor get(@PathVariable String id) {
        return doctors.require(id);
    }

    @GetMapping("/doctors/{id}/slots")
    public List<AppointmentService.Slot> slots(@PathVariable String id, @RequestParam String date) {
        return appointments.slots(id, date);
    }

    /** The logged-in doctor's own schedule for a day. */
    @GetMapping("/doctor/appointments")
    @RequireAuth(Role.DOCTOR)
    public List<Appointment> schedule(@RequestAttribute(AuthInterceptor.SESSION) Session session, @RequestParam String date) {
        return appointments.listForDoctor(session.userId(), date);
    }
}

package com.hms.api;

import com.hms.domain.Appointment;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.AppointmentService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    public record BookRequest(
            @NotBlank String doctorId,
            @NotBlank String date,
            @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}", message = "must look like 09:30") String time,
            @Size(max = 300) String reason) {
    }

    private final AppointmentService appointments;

    public AppointmentController(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @PostMapping
    @RequireAuth(Role.PATIENT)
    @ResponseStatus(HttpStatus.CREATED)
    public Appointment book(@RequestAttribute(AuthInterceptor.SESSION) Session session, @Valid @RequestBody BookRequest body) {
        return appointments.book(session, body.doctorId(), body.date(), body.time(), body.reason());
    }

    @GetMapping
    @RequireAuth(Role.PATIENT)
    public List<Appointment> mine(@RequestAttribute(AuthInterceptor.SESSION) Session session) {
        return appointments.listForPatient(session.userId());
    }

    @GetMapping("/{id}")
    @RequireAuth
    public Appointment get(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        return appointments.require(session, id);
    }

    @PostMapping("/{id}/cancel")
    @RequireAuth
    public Appointment cancel(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        return appointments.cancel(session, id);
    }
}

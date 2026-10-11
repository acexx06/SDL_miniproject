package com.hms.api;

import com.hms.domain.MedicalRecord;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.PatientService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
@RequireAuth
public class PatientController {

    public record RecordRequest(
            @NotBlank String type,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 4000) String details) {
    }

    private final PatientService patients;

    public PatientController(PatientService patients) {
        this.patients = patients;
    }

    /** Staff find a patient by the email they registered with. */
    @GetMapping("/lookup")
    @RequireAuth({Role.DOCTOR, Role.PHARMACIST, Role.ADMIN})
    public Patient lookup(@RequestParam String email) {
        return patients.findByEmail(email);
    }

    @GetMapping("/{id}")
    public Patient get(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        patients.checkProfileAccess(session, id);
        return patients.require(id);
    }

    @GetMapping("/{id}/records")
    public List<MedicalRecord> records(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        patients.checkRecordAccess(session, id);
        return patients.records(id);
    }

    @PostMapping("/{id}/records")
    @RequireAuth(Role.DOCTOR)
    @ResponseStatus(HttpStatus.CREATED)
    public MedicalRecord addRecord(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id,
                                   @Valid @RequestBody RecordRequest body) {
        return patients.addRecord(session, id, body.type(), body.title(), body.details());
    }
}

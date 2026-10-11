package com.hms.api;

import com.hms.domain.Medicine;
import com.hms.domain.Prescription;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.PharmacyService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/api")
public class PharmacyController {

    public record ItemBody(
            @NotBlank String medicineId,
            @Min(1) @Max(1000) int quantity,
            @Size(max = 120) String dosage) {
    }

    public record PrescribeRequest(
            @NotBlank String patientId,
            @NotEmpty @Size(max = 20) List<@Valid ItemBody> items,
            @Size(max = 1000) String notes) {
    }

    public record RestockRequest(@Min(1) @Max(100000) int quantity) {
    }

    private final PharmacyService pharmacy;

    public PharmacyController(PharmacyService pharmacy) {
        this.pharmacy = pharmacy;
    }

    /** Autocomplete over medicine names. Open to everyone, like doctor search. */
    @GetMapping("/medicines/suggest")
    public List<PharmacyService.MedicineRef> suggest(@RequestParam String q, @RequestParam(defaultValue = "8") int limit) {
        return pharmacy.suggest(q, Math.max(1, Math.min(limit, 10)));
    }

    /** Baseline database query, kept for the evaluation's comparison with {@code /medicines/suggest}. */
    @GetMapping("/medicines/search")
    public List<PharmacyService.MedicineRef> search(@RequestParam String q, @RequestParam(defaultValue = "8") int limit) {
        return pharmacy.searchDatabase(q, Math.max(1, Math.min(limit, 50)));
    }

    @GetMapping("/medicines/{id}")
    public PharmacyService.MedicineDetail medicine(@PathVariable String id) {
        return pharmacy.detail(id);
    }

    @PostMapping("/medicines/{id}/restock")
    @RequireAuth({Role.PHARMACIST, Role.ADMIN})
    public Medicine restock(@PathVariable String id, @Valid @RequestBody RestockRequest body) {
        return pharmacy.restock(id, body.quantity());
    }

    @PostMapping("/prescriptions")
    @RequireAuth(Role.DOCTOR)
    @ResponseStatus(HttpStatus.CREATED)
    public Prescription prescribe(@RequestAttribute(AuthInterceptor.SESSION) Session session,
                                  @Valid @RequestBody PrescribeRequest body) {
        List<PharmacyService.ItemRequest> items = body.items().stream()
                .map(i -> new PharmacyService.ItemRequest(i.medicineId(), i.quantity(), i.dosage())).toList();
        return pharmacy.prescribe(session, body.patientId(), items, body.notes());
    }

    @GetMapping("/prescriptions")
    @RequireAuth(Role.PATIENT)
    public List<Prescription> mine(@RequestAttribute(AuthInterceptor.SESSION) Session session) {
        return pharmacy.listForPatient(session.userId());
    }

    @GetMapping("/prescriptions/pending")
    @RequireAuth({Role.PHARMACIST, Role.ADMIN})
    public List<Prescription> pending() {
        return pharmacy.pending();
    }

    @GetMapping("/prescriptions/{id}")
    @RequireAuth
    public Prescription get(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        return pharmacy.require(session, id);
    }

    @PostMapping("/prescriptions/{id}/dispense")
    @RequireAuth({Role.PHARMACIST, Role.ADMIN})
    public PharmacyService.DispenseResult dispense(@PathVariable String id) {
        return pharmacy.dispense(id);
    }
}

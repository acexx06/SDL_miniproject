package com.hms.api;

import com.hms.domain.Ambulance;
import com.hms.domain.EmergencyRequest;
import com.hms.domain.Role;
import com.hms.service.EmergencyService;
import com.hms.sim.SyntheticData;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Raising an emergency needs no account: anyone must be able to call for help. */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    public record RaiseRequest(
            @NotBlank @Size(max = 80) String callerName,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank String zone,
            @Min(1) @Max(5) int severity,
            @Size(max = 500) String description) {
    }

    private final EmergencyService emergencies;

    public EmergencyController(EmergencyService emergencies) {
        this.emergencies = emergencies;
    }

    @GetMapping("/zones")
    public List<String> zones() {
        return SyntheticData.ZONES.stream().map(SyntheticData.Zone::name).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyService.PublicStatus raise(@Valid @RequestBody RaiseRequest body) {
        EmergencyRequest request = emergencies.raise(body.callerName(), body.phone(), body.zone(), body.severity(),
                body.description());
        return emergencies.status(request.id());
    }

    @GetMapping("/{id}")
    public EmergencyService.PublicStatus status(@PathVariable String id) {
        return emergencies.status(id);
    }

    @GetMapping
    @RequireAuth({Role.DOCTOR, Role.ADMIN})
    public List<EmergencyRequest> active() {
        return emergencies.active();
    }

    @GetMapping("/ambulances")
    @RequireAuth({Role.DOCTOR, Role.ADMIN})
    public List<Ambulance> ambulances() {
        return emergencies.ambulances();
    }

    @PostMapping("/{id}/resolve")
    @RequireAuth({Role.DOCTOR, Role.ADMIN})
    public EmergencyRequest resolve(@PathVariable String id) {
        return emergencies.resolve(id);
    }
}

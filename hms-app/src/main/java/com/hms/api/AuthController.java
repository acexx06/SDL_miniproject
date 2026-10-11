package com.hms.api;

import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.AuthService;
import com.hms.service.PatientService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
            @Size(max = 20) String phone,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "must look like 1990-12-31") String dateOfBirth,
            @Size(max = 20) String gender,
            @Size(max = 5) String bloodGroup) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    private final AuthService auth;
    private final PatientService patients;

    public AuthController(AuthService auth, PatientService patients) {
        this.auth = auth;
        this.patients = patients;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.LoginResult register(@Valid @RequestBody RegisterRequest body) {
        return auth.registerPatient(body.name(), body.email(), body.password(), body.phone(), body.dateOfBirth(),
                body.gender(), body.bloodGroup());
    }

    @PostMapping("/auth/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest body) {
        return auth.login(body.email(), body.password());
    }

    @PostMapping("/auth/logout")
    @RequireAuth
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestAttribute(AuthInterceptor.SESSION) Session session) {
        auth.logout(session);
    }

    @GetMapping("/me")
    @RequireAuth
    public Map<String, Object> me(@RequestAttribute(AuthInterceptor.SESSION) Session session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", new AuthService.UserView(session.userId(), session.name(), session.email(), session.role()));
        if (session.role() == Role.PATIENT) {
            result.put("patient", patients.require(session.userId()));
        }
        return result;
    }
}

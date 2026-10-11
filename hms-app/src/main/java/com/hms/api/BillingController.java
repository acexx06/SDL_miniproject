package com.hms.api;

import com.hms.domain.Bill;
import com.hms.domain.InsurancePlan;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.BillingService;
import com.hms.service.ShortLinkService;
import com.hms.web.AuthInterceptor;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BillingController {

    public record PayRequest(@NotBlank String method) {
    }

    public record InsuranceRequest(String planId) {
    }

    private final BillingService billing;
    private final ShortLinkService shortLinks;

    public BillingController(BillingService billing, ShortLinkService shortLinks) {
        this.billing = billing;
        this.shortLinks = shortLinks;
    }

    @GetMapping("/bills")
    @RequireAuth(Role.PATIENT)
    public List<Bill> mine(@RequestAttribute(AuthInterceptor.SESSION) Session session) {
        return billing.listForPatient(session.userId());
    }

    @GetMapping("/bills/{id}")
    @RequireAuth
    public Bill get(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        return billing.require(session, id);
    }

    @PostMapping("/bills/{id}/pay")
    @RequireAuth({Role.PATIENT, Role.ADMIN})
    public Bill pay(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id,
                    @Valid @RequestBody PayRequest body) {
        return billing.pay(session, id, body.method());
    }

    /** Creates a short link to this bill, valid for a week. Opening it still requires the owner's login. */
    @PostMapping("/bills/{id}/share")
    @RequireAuth
    public Map<String, String> share(@RequestAttribute(AuthInterceptor.SESSION) Session session, @PathVariable String id) {
        Bill bill = billing.require(session, id);
        String code = shortLinks.shorten("/#/bills/" + bill.id(), Duration.ofDays(7));
        String url = ServletUriComponentsBuilder.fromCurrentContextPath().path("/s/").path(code).toUriString();
        return Map.of("code", code, "shortUrl", url);
    }

    @GetMapping("/insurance/plans")
    public List<InsurancePlan> plans() {
        return billing.plans();
    }

    @PutMapping("/me/insurance")
    @RequireAuth(Role.PATIENT)
    public Patient setInsurance(@RequestAttribute(AuthInterceptor.SESSION) Session session, @RequestBody InsuranceRequest body) {
        return billing.setInsurance(session.userId(), body.planId());
    }
}

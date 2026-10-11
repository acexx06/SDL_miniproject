package com.hms.api;

import com.hms.domain.Doctor;
import com.hms.domain.Role;
import com.hms.service.AdminService;
import com.hms.service.AuthService;
import com.hms.service.CacheService;
import com.hms.service.CrawlService;
import com.hms.service.DoctorService;
import com.hms.service.IdService;
import com.hms.service.KvNodeManager;
import com.hms.service.ShortLinkService;
import com.hms.sim.SyntheticData;
import com.hms.web.ApiException;
import com.hms.web.RateLimitFilter;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations console: cluster control, metrics, crawler, doctor management and test data. Administrators only. */
@RestController
@RequestMapping("/api/admin")
@RequireAuth(Role.ADMIN)
public class AdminController {

    public record ConfigRequest(Boolean cacheEnabled, Boolean rateLimitEnabled) {
    }

    public record CrawlRequest(@Min(1) @Max(64) int workers, @Min(0) @Max(5000) long perHostDelayMs) {
    }

    public record DoctorRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank String specialization,
            @Size(max = 60) String qualification,
            @Min(0) @Max(60) int experienceYears,
            @Min(0) @Max(10_000_000) long feePaise,
            @DecimalMin("0") @DecimalMax("5") double rating,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    private final KvNodeManager nodes;
    private final CacheService cache;
    private final RateLimitFilter rateLimit;
    private final CrawlService crawl;
    private final AdminService admin;
    private final IdService ids;
    private final ShortLinkService shortLinks;
    private final DoctorService doctors;
    private final AuthService auth;

    public AdminController(KvNodeManager nodes, CacheService cache, RateLimitFilter rateLimit, CrawlService crawl,
                           AdminService admin, IdService ids, ShortLinkService shortLinks, DoctorService doctors,
                           AuthService auth) {
        this.nodes = nodes;
        this.cache = cache;
        this.rateLimit = rateLimit;
        this.crawl = crawl;
        this.admin = admin;
        this.ids = ids;
        this.shortLinks = shortLinks;
        this.doctors = doctors;
        this.auth = auth;
    }

    // ---- key-value cluster -----------------------------------------------------------------

    @GetMapping("/cluster")
    public Map<String, Object> cluster() {
        return nodes.describe();
    }

    @PostMapping("/cluster/nodes")
    public Map<String, Object> addNode() {
        nodes.addNode();
        return nodes.describe();
    }

    @PostMapping("/cluster/nodes/{id}/kill")
    public Map<String, Object> killNode(@PathVariable String id) {
        nodes.kill(id);
        return nodes.describe();
    }

    @PostMapping("/cluster/nodes/{id}/restart")
    public Map<String, Object> restartNode(@PathVariable String id) {
        nodes.restart(id);
        return nodes.describe();
    }

    // ---- metrics and switches --------------------------------------------------------------

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rateLimiterEnabled", rateLimit.isEnabled());
        result.put("requests", rateLimit.metrics());
        result.put("cache", cache.metrics());
        result.put("idGenerator", Map.of("workerId", ids.workerId(), "generated", ids.generatedCount()));
        result.put("shortLinks", shortLinks.metrics());
        result.put("data", admin.collectionSizes());
        result.put("resources", admin.resources());
        return result;
    }

    /** Turns the cache or the rate limiter off and on while running, to measure the system without them. */
    @PostMapping("/config")
    public Map<String, Object> configure(@RequestBody ConfigRequest body) {
        if (body.cacheEnabled() != null) {
            cache.setEnabled(body.cacheEnabled());
        }
        if (body.rateLimitEnabled() != null) {
            rateLimit.setEnabled(body.rateLimitEnabled());
        }
        return Map.of("cacheEnabled", cache.isEnabled(), "rateLimitEnabled", rateLimit.isEnabled());
    }

    // ---- crawler ---------------------------------------------------------------------------

    @PostMapping("/crawl")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> startCrawl(@Valid @RequestBody(required = false) CrawlRequest body) {
        crawl.start(body == null ? 8 : body.workers(), body == null ? 0 : body.perHostDelayMs());
        return crawl.status();
    }

    @GetMapping("/crawl")
    public Map<String, Object> crawlStatus() {
        return crawl.status();
    }

    // ---- doctor management and test data ---------------------------------------------------

    /** Adds a doctor with a login. The doctor is searchable as soon as this returns. */
    @PostMapping("/doctors")
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor addDoctor(@Valid @RequestBody DoctorRequest body) {
        if (!SyntheticData.SPECIALIZATIONS.contains(body.specialization())) {
            throw ApiException.badRequest("specialization must be one of " + SyntheticData.SPECIALIZATIONS);
        }
        String id = ids.next("DOC");
        String name = body.name().startsWith("Dr.") ? body.name() : "Dr. " + body.name();
        auth.createAccount(id, body.email(), body.password(), name, Role.DOCTOR);
        return doctors.add(new Doctor(id, name, body.specialization(), body.qualification(), body.experienceYears(),
                body.feePaise(), body.rating(), 0, List.of(1, 2, 3, 4, 5), "09:00", "17:00", 30, "R-NEW"));
    }

    @PostMapping("/seed/patients")
    public Map<String, Object> seedPatients(@RequestParam int count) {
        return admin.seedPatients(count);
    }

    @PostMapping("/seed/doctors")
    public Map<String, Object> seedDoctors(@RequestParam int count) {
        return admin.seedDoctors(count);
    }
}

package com.hms.api;

import com.hms.service.ShortLinkService;
import com.hms.web.ApiException;
import com.hms.web.RequireAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.Map;

@RestController
public class ShortLinkController {

    public record ShortenRequest(@NotBlank String target, @Min(1) @Max(24 * 90) int ttlHours) {
    }

    private final ShortLinkService shortLinks;

    public ShortLinkController(ShortLinkService shortLinks) {
        this.shortLinks = shortLinks;
    }

    @PostMapping("/api/shorten")
    @RequireAuth
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> shorten(@Valid @RequestBody ShortenRequest body) {
        String code;
        try {
            code = shortLinks.shorten(body.target(), Duration.ofHours(body.ttlHours()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        String url = ServletUriComponentsBuilder.fromCurrentContextPath().path("/s/").path(code).toUriString();
        return Map.of("code", code, "shortUrl", url);
    }

    /**
     * Follows a short link. 302 rather than 301: a permanent redirect would be remembered by
     * browsers, and the link must stop working when it expires.
     */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> follow(@PathVariable String code) {
        String target = shortLinks.resolve(code).orElseThrow(() -> ApiException.notFound("Short link"));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
    }
}

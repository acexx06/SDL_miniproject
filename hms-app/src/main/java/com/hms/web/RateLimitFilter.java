package com.hms.web;

import com.hms.components.ratelimit.RateLimiter;
import com.hms.components.ratelimit.TokenBucketRateLimiter;
import com.hms.config.HmsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * First thing every request meets: the rate limiter, and the request counters behind
 * {@code /api/admin/metrics}.
 *
 * Requests are sorted into classes, each with its own token bucket per client, so that one kind
 * of traffic cannot use up the allowance of another. In particular an emergency call is never
 * turned away because the same client has been searching or logging in too quickly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Request classes with their sustained rate (requests/second) and burst size, per client. */
    public enum RouteClass {
        /** Login and registration: slow, to blunt password guessing. */
        AUTH(1, 10),
        /** Autocomplete and search: one request per keystroke, so a generous allowance. */
        SEARCH(20, 40),
        READ(20, 40),
        /** Bookings, payments, prescriptions. */
        WRITE(5, 15),
        /** Short-link lookups: limited so that codes cannot be found by trying them in bulk. */
        REDIRECT(10, 20),
        /** Emergency calls: a bucket of their own that ordinary traffic cannot drain. */
        EMERGENCY(2, 20),
        ADMIN(50, 100);

        final double rate;
        final double burst;

        RouteClass(double rate, double burst) {
            this.rate = rate;
            this.burst = burst;
        }
    }

    private static final class Counters {
        final LongAdder requests = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder serverErrors = new LongAdder();
        final LongAdder nanos = new LongAdder();
    }

    private final Map<RouteClass, TokenBucketRateLimiter> limiters = new EnumMap<>(RouteClass.class);
    private final Map<RouteClass, Counters> counters = new EnumMap<>(RouteClass.class);
    private final boolean trustProxy;
    private volatile boolean enabled;

    public RateLimitFilter(HmsProperties properties) {
        this.trustProxy = properties.trustProxy();
        this.enabled = properties.rateLimit().enabled();
        for (RouteClass routeClass : RouteClass.values()) {
            limiters.put(routeClass, new TokenBucketRateLimiter(routeClass.rate, routeClass.burst));
            counters.put(routeClass, new Counters());
        }
    }

    static RouteClass classify(String method, String path) {
        if (path.startsWith("/s/")) {
            return RouteClass.REDIRECT;
        }
        if (!path.startsWith("/api/")) {
            return null; // static pages are not limited
        }
        if (path.startsWith("/api/admin/")) {
            return RouteClass.ADMIN;
        }
        if (path.startsWith("/api/auth/")) {
            return RouteClass.AUTH;
        }
        if (path.equals("/api/emergency") && method.equals("POST")) {
            return RouteClass.EMERGENCY;
        }
        if (!method.equals("GET")) {
            return RouteClass.WRITE;
        }
        return path.endsWith("/suggest") || path.endsWith("/search") ? RouteClass.SEARCH : RouteClass.READ;
    }

    private String clientKey(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            }
        }
        return request.getRemoteAddr();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteClass routeClass = classify(request.getMethod(), request.getRequestURI());
        if (routeClass == null) {
            chain.doFilter(request, response);
            return;
        }
        Counters stats = counters.get(routeClass);
        stats.requests.increment();
        if (enabled) {
            RateLimiter.Decision decision = limiters.get(routeClass).tryAcquire(clientKey(request));
            if (!decision.allowed()) {
                stats.rejected.increment();
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf((long) Math.ceil(decision.retryAfterSeconds())));
                response.setContentType("application/json");
                response.getWriter().write(String.format(Locale.ROOT,
                        "{\"error\":\"Too many requests\",\"retryAfterSeconds\":%.2f}", decision.retryAfterSeconds()));
                return;
            }
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            stats.nanos.add(System.nanoTime() - start);
            if (response.getStatus() >= 500) {
                stats.serverErrors.increment();
            }
        }
    }

    /** Buckets of clients that have gone quiet are dropped so memory tracks active clients only. */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        limiters.values().forEach(TokenBucketRateLimiter::sweep);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RouteClass routeClass : RouteClass.values()) {
            Counters stats = counters.get(routeClass);
            TokenBucketRateLimiter limiter = limiters.get(routeClass);
            long requests = stats.requests.sum();
            long rejected = stats.rejected.sum();
            long served = requests - rejected;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ratePerSecond", routeClass.rate);
            row.put("burst", routeClass.burst);
            row.put("requests", requests);
            row.put("rejected", rejected);
            row.put("rejectionRate", requests == 0 ? 0 : (double) rejected / requests);
            row.put("serverErrors", stats.serverErrors.sum());
            row.put("avgServiceMs", served == 0 ? 0 : stats.nanos.sum() / 1e6 / served);
            row.put("trackedClients", limiter.trackedKeys());
            result.put(routeClass.name(), row);
        }
        return result;
    }
}

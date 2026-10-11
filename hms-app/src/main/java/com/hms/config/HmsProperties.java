package com.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Everything under {@code hms.*} in application.properties. */
@ConfigurationProperties("hms")
public record HmsProperties(
        @DefaultValue("1") int workerId,
        @DefaultValue("false") boolean trustProxy,
        @DefaultValue("10") int bcryptStrength,
        @DefaultValue Kv kv,
        @DefaultValue Cache cache,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Seed seed,
        @DefaultValue MockSites mockSites) {

    public record Kv(
            @DefaultValue("true") boolean embedded,
            @DefaultValue("3") int nodes,
            @DefaultValue("7101") int basePort,
            @DefaultValue("2") int replicas,
            @DefaultValue("150") int virtualNodes,
            @DefaultValue("200000") int maxEntriesPerNode) {
    }

    public record Cache(@DefaultValue("true") boolean enabled) {
    }

    public record RateLimit(@DefaultValue("true") boolean enabled) {
    }

    public record Seed(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200") int doctors,
            @DefaultValue("400") int medicines) {
    }

    public record MockSites(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("9101") int basePort,
            @DefaultValue("20") int latencyMs,
            @DefaultValue("600") int drugs) {
    }
}

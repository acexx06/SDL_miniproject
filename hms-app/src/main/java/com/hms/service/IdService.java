package com.hms.service;

import com.hms.components.idgen.SnowflakeIdGenerator;
import com.hms.config.HmsProperties;
import org.springframework.stereotype.Service;

/** Issues every identifier in the system from the Snowflake generator, with a prefix naming the kind of thing. */
@Service
public class IdService {

    private final SnowflakeIdGenerator generator;

    public IdService(HmsProperties properties) {
        this.generator = new SnowflakeIdGenerator(properties.workerId());
    }

    /** For example {@code next("APT")} gives {@code APT0B3xK9fQw2Za}. */
    public String next(String prefix) {
        return generator.nextId(prefix);
    }

    /** The bare 64-bit id, for callers that need to derive more than one representation from it. */
    public long nextRaw() {
        return generator.nextId();
    }

    public long generatedCount() {
        return generator.generatedCount();
    }

    public long workerId() {
        return generator.workerId();
    }
}

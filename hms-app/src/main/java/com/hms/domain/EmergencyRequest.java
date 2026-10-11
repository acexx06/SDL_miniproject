package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** @param severity 1 (life-threatening) to 5 (minor); lower numbers are dispatched first */
@Document("emergencies")
public record EmergencyRequest(@Id String id, String callerName, String phone, String zone, int severity,
                               String description, Status status, String ambulanceId, String ambulanceCallSign,
                               Integer etaMinutes, Instant createdAt, Instant dispatchedAt, Instant resolvedAt) {

    public enum Status {
        QUEUED, DISPATCHED, RESOLVED
    }
}

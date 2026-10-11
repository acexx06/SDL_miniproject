package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A logged-in session, keyed by the SHA-256 of its bearer token so a database leak exposes no usable tokens. */
@Document("sessions")
public record Session(@Id String tokenHash, String userId, String name, String email, Role role, Instant expiresAt) {
}

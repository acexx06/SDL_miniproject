package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Login credentials. The id is the id of the person it belongs to: a patient's account has the
 * patient id, a doctor's account has the doctor id.
 */
@Document("users")
public record UserAccount(@Id String id, String email, String passwordHash, String name, Role role, Instant createdAt) {
}

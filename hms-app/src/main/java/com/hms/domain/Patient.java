package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("patients")
public record Patient(@Id String id, String name, String email, String phone, String dateOfBirth, String gender,
                      String bloodGroup, String insurancePlanId, Instant createdAt) {
}

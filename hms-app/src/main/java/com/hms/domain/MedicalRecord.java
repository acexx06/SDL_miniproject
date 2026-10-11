package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** @param type one of DIAGNOSIS, LAB_RESULT, VITALS, NOTE */
@Document("records")
public record MedicalRecord(@Id String id, String patientId, String authorId, String authorName, String type,
                            String title, String details, Instant createdAt) {
}

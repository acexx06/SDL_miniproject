package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("prescriptions")
public record Prescription(@Id String id, String patientId, String patientName, String doctorId, String doctorName,
                           List<Item> items, String notes, Status status, String billId, Instant createdAt) {

    public record Item(String medicineId, String name, int quantity, String dosage) {
    }

    /** DISPENSING is held only while the pharmacy is taking the items out of stock. */
    public enum Status {
        ISSUED, DISPENSING, DISPENSED
    }
}

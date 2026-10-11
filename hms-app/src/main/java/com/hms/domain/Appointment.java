package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * @param date ISO date, "yyyy-MM-dd"
 * @param time slot start, "HH:mm"
 */
@Document("appointments")
public record Appointment(@Id String id, String patientId, String patientName, String doctorId, String doctorName,
                          String specialization, String date, String time, Status status, String reason,
                          String billId, Instant createdAt) {

    public enum Status {
        BOOKED, CANCELLED, COMPLETED
    }
}

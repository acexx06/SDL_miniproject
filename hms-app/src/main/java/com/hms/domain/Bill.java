package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * All amounts are whole paise (1/100 rupee) so that no rounding error can creep in.
 *
 * @param reference the appointment or prescription this bill is for
 */
@Document("bills")
public record Bill(@Id String id, String patientId, Category category, String reference, List<Line> items,
                   long subtotalPaise, String insurancePlanId, int coveragePercent, long coveredPaise,
                   long payablePaise, Status status, String paymentMethod, Instant createdAt, Instant paidAt) {

    public record Line(String description, long amountPaise) {
    }

    public enum Category {
        CONSULTATION, PHARMACY, LAB, EMERGENCY
    }

    public enum Status {
        UNPAID, PAID, CANCELLED
    }
}

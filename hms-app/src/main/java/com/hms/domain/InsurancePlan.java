package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** An insurance plan gathered by the web crawler. Coverage values are percentages of the bill the insurer pays. */
@Document("insurance_plans")
public record InsurancePlan(@Id String id, String provider, String name, int consultation, int pharmacy, int lab,
                            int emergency, long annualLimitPaise, String sourceUrl, Instant crawledAt) {

    public int coverageFor(Bill.Category category) {
        return switch (category) {
            case CONSULTATION -> consultation;
            case PHARMACY -> pharmacy;
            case LAB -> lab;
            case EMERGENCY -> emergency;
        };
    }
}

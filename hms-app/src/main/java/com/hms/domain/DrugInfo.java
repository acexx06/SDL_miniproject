package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Reference information about a drug, gathered by the web crawler. The id is the slug of the drug name. */
@Document("drug_info")
public record DrugInfo(@Id String id, String name, String category, String form, String strength, String uses,
                       String sideEffects, String sourceUrl, Instant crawledAt) {
}

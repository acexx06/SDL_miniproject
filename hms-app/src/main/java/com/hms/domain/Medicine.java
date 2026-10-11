package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** An item in the hospital pharmacy's own stock. */
@Document("medicines")
public record Medicine(@Id String id, String name, String category, String form, String strength, long pricePaise,
                       int stock) {
}

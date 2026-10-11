package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A short link; the id is the short code. MongoDB removes the document once {@code expiresAt} passes. */
@Document("short_urls")
public record ShortUrl(@Id String code, String target, Instant createdAt, Instant expiresAt) {
}

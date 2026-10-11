package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** @param x,y position on the simulated 100 x 100 city grid */
@Document("ambulances")
public record Ambulance(@Id String id, String callSign, String zone, int x, int y, Status status, String emergencyId) {

    public enum Status {
        FREE, BUSY
    }
}

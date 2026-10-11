package com.hms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * @param workingDays ISO day-of-week numbers, 1 = Monday .. 7 = Sunday
 * @param startTime   first slot of the day, "HH:mm"
 * @param endTime     end of the last slot, "HH:mm"
 * @param bookings    how many appointments have been booked; drives search ranking
 */
@Document("doctors")
public record Doctor(@Id String id, String name, String specialization, String qualification, int experienceYears,
                     long feePaise, double rating, long bookings, List<Integer> workingDays, String startTime,
                     String endTime, int slotMinutes, String room) {
}

package com.hms.service;

import com.hms.domain.Ambulance;
import com.hms.domain.EmergencyRequest;
import com.hms.sim.SyntheticData;
import com.hms.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Emergency requests and ambulance dispatch.
 *
 * A request is given the nearest free ambulance straight away. If none is free it waits in a
 * queue ordered by severity and then arrival time, and is dispatched as soon as an ambulance is
 * released. An ambulance is claimed with a conditional update (FREE to BUSY), so two requests
 * arriving together can never be given the same vehicle.
 */
@Service
public class EmergencyService {

    /** What an anonymous caller may see when checking on a request: no names or phone numbers. */
    public record PublicStatus(String id, EmergencyRequest.Status status, String zone, int severity,
                               String ambulanceCallSign, Integer etaMinutes, Long queuePosition) {
    }

    private final MongoTemplate mongo;
    private final IdService ids;

    public EmergencyService(MongoTemplate mongo, IdService ids) {
        this.mongo = mongo;
        this.ids = ids;
    }

    private static SyntheticData.Zone zone(String name) {
        return SyntheticData.ZONES.stream().filter(z -> z.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown zone; see /api/emergency/zones"));
    }

    private static double distance(Ambulance ambulance, SyntheticData.Zone zone) {
        return Math.hypot(ambulance.x() - zone.x(), ambulance.y() - zone.y());
    }

    private static int etaMinutes(double distance) {
        return (int) Math.max(3, Math.round(distance / 3));
    }

    /** Tries the free ambulances nearest first; returns the one claimed for this emergency, or null. */
    private Ambulance claimNearest(SyntheticData.Zone zone, String emergencyId) {
        List<Ambulance> free = new ArrayList<>(
                mongo.find(Query.query(where("status").is(Ambulance.Status.FREE)), Ambulance.class));
        free.sort(Comparator.comparingDouble(a -> distance(a, zone)));
        for (Ambulance candidate : free) {
            Ambulance claimed = mongo.findAndModify(
                    Query.query(where("_id").is(candidate.id()).and("status").is(Ambulance.Status.FREE)),
                    new Update().set("status", Ambulance.Status.BUSY).set("emergencyId", emergencyId),
                    FindAndModifyOptions.options().returnNew(true), Ambulance.class);
            if (claimed != null) {
                return claimed;
            }
        }
        return null;
    }

    public EmergencyRequest raise(String callerName, String phone, String zoneName, int severity, String description) {
        SyntheticData.Zone zone = zone(zoneName);
        String id = ids.next("EMR");
        Instant now = Instant.now();
        Ambulance ambulance = claimNearest(zone, id);
        EmergencyRequest request = ambulance == null
                ? new EmergencyRequest(id, callerName, phone, zone.name(), severity, description,
                        EmergencyRequest.Status.QUEUED, null, null, null, now, null, null)
                : new EmergencyRequest(id, callerName, phone, zone.name(), severity, description,
                        EmergencyRequest.Status.DISPATCHED, ambulance.id(), ambulance.callSign(),
                        etaMinutes(distance(ambulance, zone)), now, now, null);
        return mongo.insert(request);
    }

    public PublicStatus status(String id) {
        EmergencyRequest request = mongo.findById(id, EmergencyRequest.class);
        if (request == null) {
            throw ApiException.notFound("Emergency request");
        }
        Long position = null;
        if (request.status() == EmergencyRequest.Status.QUEUED) {
            position = 1 + mongo.count(Query.query(where("status").is(EmergencyRequest.Status.QUEUED)
                    .orOperator(where("severity").lt(request.severity()),
                            where("severity").is(request.severity()).and("_id").lt(id))), EmergencyRequest.class);
        }
        return new PublicStatus(request.id(), request.status(), request.zone(), request.severity(),
                request.ambulanceCallSign(), request.etaMinutes(), position);
    }

    /** Requests that are still open, most urgent first. */
    public List<EmergencyRequest> active() {
        return mongo.find(Query.query(where("status").ne(EmergencyRequest.Status.RESOLVED))
                .with(Sort.by("severity", "_id")).limit(100), EmergencyRequest.class);
    }

    public List<Ambulance> ambulances() {
        return mongo.find(new Query().with(Sort.by("callSign")), Ambulance.class);
    }

    /** Closes a request, frees its ambulance and sends that capacity to whoever is waiting. */
    public EmergencyRequest resolve(String id) {
        EmergencyRequest resolved = mongo.findAndModify(
                Query.query(where("_id").is(id).and("status").ne(EmergencyRequest.Status.RESOLVED)),
                new Update().set("status", EmergencyRequest.Status.RESOLVED).set("resolvedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), EmergencyRequest.class);
        if (resolved == null) {
            throw ApiException.notFound("Open emergency request");
        }
        if (resolved.ambulanceId() != null) {
            mongo.updateFirst(Query.query(where("_id").is(resolved.ambulanceId())),
                    new Update().set("status", Ambulance.Status.FREE).unset("emergencyId"), Ambulance.class);
            dispatchQueued();
        }
        return resolved;
    }

    /** Gives free ambulances to queued requests, most severe and longest-waiting first. */
    private void dispatchQueued() {
        while (true) {
            EmergencyRequest next = mongo.findOne(Query.query(where("status").is(EmergencyRequest.Status.QUEUED))
                    .with(Sort.by("severity", "_id")), EmergencyRequest.class);
            if (next == null) {
                return;
            }
            SyntheticData.Zone zone = zone(next.zone());
            Ambulance ambulance = claimNearest(zone, next.id());
            if (ambulance == null) {
                return;
            }
            EmergencyRequest dispatched = mongo.findAndModify(
                    Query.query(where("_id").is(next.id()).and("status").is(EmergencyRequest.Status.QUEUED)),
                    new Update().set("status", EmergencyRequest.Status.DISPATCHED)
                            .set("ambulanceId", ambulance.id()).set("ambulanceCallSign", ambulance.callSign())
                            .set("etaMinutes", etaMinutes(distance(ambulance, zone))).set("dispatchedAt", Instant.now()),
                    EmergencyRequest.class);
            if (dispatched == null) { // someone else dispatched or resolved it first: hand the ambulance back
                mongo.updateFirst(Query.query(where("_id").is(ambulance.id())),
                        new Update().set("status", Ambulance.Status.FREE).unset("emergencyId"), Ambulance.class);
            }
        }
    }
}

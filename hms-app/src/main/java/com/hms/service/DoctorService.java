package com.hms.service;

import com.hms.components.autocomplete.Autocomplete;
import com.hms.domain.Doctor;
import com.hms.web.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Doctor directory and search.
 *
 * Search and suggestions are answered from an in-memory autocomplete index holding both doctor
 * names and specializations, so typing "car" offers "Cardiology" as well as Dr. Carvalho, and no
 * keystroke costs a database query. The index is built from MongoDB at start-up and updated as
 * doctors are added or booked (bookings raise a doctor's ranking).
 */
@Service
public class DoctorService {

    /** The part of a doctor that search results need; small enough to keep in the index. */
    public record DoctorRef(String id, String name, String specialization, long feePaise, double rating) {
    }

    /** @param type "doctor" or "specialization" */
    public record Suggestion(String type, String id, String text, String detail) {
    }

    private static final String SPEC_PREFIX = "spec:";
    private static final int INDEX_CACHE = 20;
    private static final Comparator<DoctorRef> BY_RATING =
            Comparator.comparingDouble(DoctorRef::rating).reversed().thenComparing(DoctorRef::name);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private volatile Autocomplete<DoctorRef> index = new Autocomplete<>(INDEX_CACHE, true);
    private final Map<String, List<DoctorRef>> bySpecialization = new ConcurrentHashMap<>();

    public DoctorService(MongoTemplate mongo, CacheService cache) {
        this.mongo = mongo;
        this.cache = cache;
    }

    private static DoctorRef ref(Doctor d) {
        return new DoctorRef(d.id(), d.name(), d.specialization(), d.feePaise(), d.rating());
    }

    private void index(Autocomplete<DoctorRef> target, Doctor doctor) {
        DoctorRef ref = ref(doctor);
        target.add(doctor.id(), doctor.name(), doctor.rating() * 10 + doctor.bookings(), ref);
        // Specializations outrank individual doctors so that the category is offered first.
        target.add(SPEC_PREFIX + doctor.specialization(), doctor.specialization(), 1_000_000, null);
        bySpecialization.computeIfAbsent(doctor.specialization(), k -> new CopyOnWriteArrayList<>()).add(ref);
    }

    /** Rebuilds the search index from the database. */
    public synchronized void reload() {
        Autocomplete<DoctorRef> fresh = new Autocomplete<>(INDEX_CACHE, true);
        bySpecialization.clear();
        mongo.findAll(Doctor.class).forEach(doctor -> index(fresh, doctor));
        bySpecialization.values().forEach(list -> list.sort(BY_RATING));
        index = fresh;
    }

    public synchronized Doctor add(Doctor doctor) {
        mongo.insert(doctor);
        index(index, doctor);
        bySpecialization.get(doctor.specialization()).sort(BY_RATING);
        return doctor;
    }

    public int indexedCount() {
        return index.size();
    }

    public List<String> specializations() {
        return new ArrayList<>(new TreeSet<>(bySpecialization.keySet()));
    }

    public List<Suggestion> suggest(String q, int limit) {
        List<Suggestion> result = new ArrayList<>();
        for (Autocomplete.Suggestion<DoctorRef> s : index.suggest(q, limit)) {
            if (s.payload() == null) {
                int count = bySpecialization.getOrDefault(s.text(), List.of()).size();
                result.add(new Suggestion("specialization", s.id(), s.text(), count + " doctors"));
            } else {
                result.add(new Suggestion("doctor", s.id(), s.text(), s.payload().specialization()));
            }
        }
        return result;
    }

    /** Doctors matching a name or specialization prefix, optionally narrowed to one specialization. */
    public List<DoctorRef> search(String q, String specialization, int limit) {
        boolean hasQuery = q != null && !q.isBlank();
        boolean hasSpecialization = specialization != null && !specialization.isBlank();
        Set<DoctorRef> found = new LinkedHashSet<>();
        if (hasQuery) {
            for (Autocomplete.Suggestion<DoctorRef> s : index.suggest(q, INDEX_CACHE)) {
                if (s.payload() == null) {
                    found.addAll(bySpecialization.getOrDefault(s.text(), List.of()));
                } else {
                    found.add(s.payload());
                }
            }
        } else if (hasSpecialization) {
            found.addAll(bySpecialization.getOrDefault(specialization, List.of()));
        } else {
            bySpecialization.values().forEach(found::addAll);
            return found.stream().sorted(BY_RATING).limit(limit).toList();
        }
        return found.stream()
                .filter(ref -> !hasSpecialization || ref.specialization().equalsIgnoreCase(specialization))
                .limit(limit).toList();
    }

    /** Baseline for the evaluation: the same search done the obvious way, as a database query. */
    public List<DoctorRef> searchDatabase(String q, String specialization, int limit) {
        List<Criteria> all = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String quoted = Pattern.quote(q.trim());
            all.add(new Criteria().orOperator(
                    where("name").regex("(^|\\s)" + quoted, "i"),
                    where("specialization").regex("^" + quoted, "i")));
        }
        if (specialization != null && !specialization.isBlank()) {
            all.add(where("specialization").is(specialization));
        }
        Query query = all.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(all));
        query.with(Sort.by(Sort.Direction.DESC, "rating")).limit(limit);
        return mongo.find(query, Doctor.class).stream().map(DoctorService::ref).toList();
    }

    public Doctor require(String id) {
        Doctor doctor = cache.get("doctor:" + id, Doctor.class, Duration.ofMinutes(10),
                () -> mongo.findById(id, Doctor.class));
        if (doctor == null) {
            throw ApiException.notFound("Doctor");
        }
        return doctor;
    }

    /** A booked doctor moves up the suggestions; the count is persisted so a restart keeps the ranking. */
    public void recordBooking(String doctorId) {
        index.bump(doctorId, 1);
        mongo.updateFirst(Query.query(where("_id").is(doctorId)), new Update().inc("bookings", 1), Doctor.class);
    }
}

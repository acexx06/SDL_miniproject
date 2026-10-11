package com.hms.service;

import com.hms.config.HmsProperties;
import com.hms.domain.Patient;
import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.domain.UserAccount;
import com.hms.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Registration, login and session lookup.
 *
 * Passwords are stored as BCrypt hashes. A login yields a random bearer token; only its SHA-256
 * is stored. Sessions live in MongoDB (which also expires them) and are cached in the key-value
 * cluster, because checking the session is the one database read that every request would
 * otherwise repeat.
 */
@Service
public class AuthService {

    public record UserView(String id, String name, String email, Role role) {
    }

    public record LoginResult(String token, UserView user) {
    }

    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private static final Duration SESSION_CACHE_TTL = Duration.ofMinutes(10);

    private final MongoTemplate mongo;
    private final CacheService cache;
    private final IdService ids;
    private final BCryptPasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the email is unknown, so that case takes as long as a wrong password. */
    private final String decoyHash;

    public AuthService(MongoTemplate mongo, CacheService cache, IdService ids, HmsProperties properties) {
        this.mongo = mongo;
        this.cache = cache;
        this.ids = ids;
        this.encoder = new BCryptPasswordEncoder(properties.bcryptStrength());
        this.decoyHash = encoder.encode("decoy-password");
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String hashPassword(String password) {
        return encoder.encode(password);
    }

    /** Creates login credentials for {@code id}. The unique index on email rejects a second account. */
    public UserAccount createAccount(String id, String email, String password, String name, Role role) {
        UserAccount account = new UserAccount(id, normalizeEmail(email), encoder.encode(password), name, role, Instant.now());
        try {
            return mongo.insert(account);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
    }

    public LoginResult registerPatient(String name, String email, String password, String phone, String dateOfBirth,
                                       String gender, String bloodGroup) {
        String id = ids.next("PAT");
        UserAccount account = createAccount(id, email, password, name.trim(), Role.PATIENT);
        mongo.insert(new Patient(id, account.name(), account.email(), phone, dateOfBirth, gender, bloodGroup, null, Instant.now()));
        return openSession(account);
    }

    public LoginResult login(String email, String password) {
        UserAccount account = mongo.findOne(Query.query(where("email").is(normalizeEmail(email))), UserAccount.class);
        boolean matches = encoder.matches(password, account != null ? account.passwordHash() : decoyHash);
        if (account == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect email or password");
        }
        return openSession(account);
    }

    private LoginResult openSession(UserAccount account) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mongo.insert(new Session(sha256(token), account.id(), account.name(), account.email(), account.role(),
                Instant.now().plus(SESSION_LIFETIME)));
        return new LoginResult(token, new UserView(account.id(), account.name(), account.email(), account.role()));
    }

    /** The session for a bearer token, or {@code null} if it is unknown or has expired. */
    public Session authenticate(String token) {
        if (token.isEmpty() || token.length() > 128) {
            return null;
        }
        String hash = sha256(token);
        Session session = cache.get("sess:" + hash, Session.class, SESSION_CACHE_TTL,
                () -> mongo.findById(hash, Session.class));
        return session != null && session.expiresAt().isAfter(Instant.now()) ? session : null;
    }

    public void logout(Session session) {
        mongo.remove(Query.query(where("_id").is(session.tokenHash())), Session.class);
        cache.evict("sess:" + session.tokenHash());
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.hms.components.hashing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Maps a key to the server (or servers) responsible for it. */
public interface NodeRouter {

    void addNode(String node);

    void removeNode(String node);

    List<String> nodes();

    /** The node responsible for {@code key}, or {@code null} when there are no nodes. */
    String route(String key);

    /** Preference list: up to {@code n} distinct nodes for {@code key}, primary first. */
    List<String> routeN(String key, int n);

    ThreadLocal<MessageDigest> MD5 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** 64-bit hash that is identical on every JVM, unlike {@code String.hashCode} it is well mixed. */
    static long hash64(String key) {
        byte[] digest = MD5.get().digest(key.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest).getLong();
    }
}

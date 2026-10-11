package com.hms.components.kv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Pooled blocking client for a single {@link KvNodeServer}. Safe for use from many threads. */
public final class KvNodeClient implements AutoCloseable {

    /** Thrown when the node cannot be reached or drops the connection. */
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface Call<T> {
        T apply(Connection connection) throws IOException;
    }

    private static final class Connection implements Closeable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Connection(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192));
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static final int MAX_IDLE = 64;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();
    private final AtomicInteger idleCount = new AtomicInteger();

    public KvNodeClient(String host, int port) {
        this(host, port, 200, 1000);
    }

    public KvNodeClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    private <T> T execute(Call<T> call) {
        Connection pooled = idle.pollFirst();
        if (pooled != null) {
            idleCount.decrementAndGet();
            try {
                T result = call.apply(pooled);
                release(pooled);
                return result;
            } catch (IOException stale) {
                // The pooled connection may simply predate a server restart; try once on a fresh one.
                pooled.close();
            } catch (RuntimeException e) {
                pooled.close();
                throw e;
            }
        }
        Connection fresh = null;
        try {
            fresh = new Connection(host, port, connectTimeoutMs, readTimeoutMs);
            T result = call.apply(fresh);
            release(fresh);
            return result;
        } catch (IOException e) {
            if (fresh != null) {
                fresh.close();
            }
            throw new UnavailableException("kv node " + host + ":" + port + " unavailable", e);
        } catch (RuntimeException e) {
            if (fresh != null) {
                fresh.close();
            }
            throw e;
        }
    }

    private void release(Connection connection) {
        if (idleCount.get() < MAX_IDLE) {
            idleCount.incrementAndGet();
            idle.offerFirst(connection);
        } else {
            connection.close();
        }
    }

    private static void header(Connection c, byte op, String key) throws IOException {
        c.out.writeByte(op);
        c.out.writeUTF(key);
    }

    private static byte status(Connection c) throws IOException {
        c.out.flush();
        byte status = c.in.readByte();
        if (status == KvNodeServer.ST_ERROR) {
            throw new IllegalStateException("kv node rejected the operation");
        }
        return status;
    }

    public String get(String key) {
        return execute(c -> {
            header(c, KvNodeServer.OP_GET, key);
            if (status(c) != KvNodeServer.ST_OK) {
                return null;
            }
            byte[] bytes = new byte[c.in.readInt()];
            c.in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    private boolean store(byte op, String key, String value, long ttlMs) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return execute(c -> {
            header(c, op, key);
            c.out.writeLong(ttlMs);
            c.out.writeInt(bytes.length);
            c.out.write(bytes);
            return status(c) == KvNodeServer.ST_OK;
        });
    }

    public void set(String key, String value, long ttlMs) {
        store(KvNodeServer.OP_SET, key, value, ttlMs);
    }

    public boolean setIfAbsent(String key, String value, long ttlMs) {
        return store(KvNodeServer.OP_SETNX, key, value, ttlMs);
    }

    public boolean delete(String key) {
        return execute(c -> {
            header(c, KvNodeServer.OP_DEL, key);
            return status(c) == KvNodeServer.ST_OK;
        });
    }

    public long increment(String key, long delta, long ttlMs) {
        return execute(c -> {
            header(c, KvNodeServer.OP_INCR, key);
            c.out.writeLong(delta);
            c.out.writeLong(ttlMs);
            status(c);
            return c.in.readLong();
        });
    }

    public void ping() {
        execute(c -> {
            header(c, KvNodeServer.OP_PING, "");
            return status(c);
        });
    }

    public void flush() {
        execute(c -> {
            header(c, KvNodeServer.OP_FLUSH, "");
            return status(c);
        });
    }

    public KvEngine.Stats stats() {
        return execute(c -> {
            header(c, KvNodeServer.OP_STATS, "");
            status(c);
            return new KvEngine.Stats(c.in.readLong(), c.in.readLong(), c.in.readLong(), c.in.readLong(),
                    c.in.readLong(), c.in.readLong(), c.in.readLong());
        });
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** Drops pooled connections, e.g. once the node is known to be down. */
    public void closeIdle() {
        Connection connection;
        while ((connection = idle.pollFirst()) != null) {
            idleCount.decrementAndGet();
            connection.close();
        }
    }

    @Override
    public void close() {
        closeIdle();
    }
}

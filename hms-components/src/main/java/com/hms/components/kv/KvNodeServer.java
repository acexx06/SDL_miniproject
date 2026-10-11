package com.hms.components.kv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One key-value store server: a {@link KvEngine} behind a TCP socket.
 *
 * <pre>
 * Request frame:   op (1 byte) | key (UTF) | op-specific fields
 *   GET, DEL       -
 *   SET, SETNX     ttlMs (8) | valueLength (4) | value bytes
 *   INCR           delta (8) | ttlMs (8)
 *   PING, STATS, FLUSH carry an empty key
 * Response frame:  status (1 byte: 0 = ok/found, 1 = not found/false, 2 = error) | payload
 * </pre>
 *
 * Can run embedded (the application starts several on different ports) or as its own process:
 * {@code java -cp hms-components.jar com.hms.components.kv.KvNodeServer n1 7101}
 */
public final class KvNodeServer implements AutoCloseable {

    static final byte OP_GET = 1;
    static final byte OP_SET = 2;
    static final byte OP_DEL = 3;
    static final byte OP_SETNX = 4;
    static final byte OP_INCR = 5;
    static final byte OP_PING = 6;
    static final byte OP_STATS = 7;
    static final byte OP_FLUSH = 8;

    static final byte ST_OK = 0;
    static final byte ST_MISS = 1;
    static final byte ST_ERROR = 2;

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    private final String nodeId;
    private final int requestedPort;
    private final KvEngine engine;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "kv-node-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public KvNodeServer(String nodeId, int port, int maxEntries) {
        this.nodeId = nodeId;
        this.requestedPort = port;
        this.engine = new KvEngine(maxEntries);
    }

    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 512);
        serverSocket = socket;
        running = true;
        workers.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                connections.add(socket);
                workers.execute(() -> serve(socket));
            } catch (IOException e) {
                // Either close() shut the socket (the loop condition ends us) or one accept failed.
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 8192));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192))) {
            while (running) {
                handle(in, out);
                out.flush();
            }
        } catch (EOFException ignored) {
            // client closed the connection
        } catch (IOException ignored) {
            // connection dropped, or the server is stopping
        } finally {
            connections.remove(socket);
        }
    }

    private void handle(DataInputStream in, DataOutputStream out) throws IOException {
        byte op = in.readByte();
        String key = in.readUTF();
        switch (op) {
            case OP_GET -> {
                String value = engine.get(key);
                if (value == null) {
                    out.writeByte(ST_MISS);
                } else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    out.writeByte(ST_OK);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            case OP_SET, OP_SETNX -> {
                long ttlMs = in.readLong();
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                String value = new String(bytes, StandardCharsets.UTF_8);
                if (op == OP_SET) {
                    engine.set(key, value, ttlMs);
                    out.writeByte(ST_OK);
                } else {
                    out.writeByte(engine.setIfAbsent(key, value, ttlMs) ? ST_OK : ST_MISS);
                }
            }
            case OP_DEL -> out.writeByte(engine.delete(key) ? ST_OK : ST_MISS);
            case OP_INCR -> {
                long delta = in.readLong();
                long ttlMs = in.readLong();
                try {
                    long value = engine.increment(key, delta, ttlMs);
                    out.writeByte(ST_OK);
                    out.writeLong(value);
                } catch (NumberFormatException e) {
                    out.writeByte(ST_ERROR);
                }
            }
            case OP_PING -> out.writeByte(ST_OK);
            case OP_STATS -> {
                KvEngine.Stats s = engine.stats();
                out.writeByte(ST_OK);
                for (long v : new long[]{s.keys(), s.hits(), s.misses(), s.sets(), s.deletes(), s.evictions(), s.expirations()}) {
                    out.writeLong(v);
                }
            }
            case OP_FLUSH -> {
                engine.clear();
                out.writeByte(ST_OK);
            }
            default -> throw new IOException("unknown op " + op);
        }
    }

    /** Stops accepting and drops every open connection, which is what a crashed server looks like to clients. */
    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        connections.clear();
        workers.shutdownNow();
    }

    public String nodeId() {
        return nodeId;
    }

    public int port() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : requestedPort;
    }

    public boolean isRunning() {
        return running;
    }

    public KvEngine engine() {
        return engine;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: KvNodeServer <nodeId> <port> [maxEntries]");
            System.exit(2);
        }
        int maxEntries = args.length > 2 ? Integer.parseInt(args[2]) : 1_000_000;
        KvNodeServer server = new KvNodeServer(args[0], Integer.parseInt(args[1]), maxEntries);
        server.start();
        System.out.println("kv node " + server.nodeId() + " listening on port " + server.port());
        Thread.currentThread().join();
    }
}

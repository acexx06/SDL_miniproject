package com.hms.bench;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Draws docs/img/architecture.png: the services, data stores, components and how they connect. */
public final class ArchitectureDiagram {

    private static final Color SURFACE = new Color(0xfcfcfb);
    private static final Color INK = new Color(0x0b0b0b);
    private static final Color MUTED = new Color(0x52514e);
    private static final Color LINE = new Color(0x898781);
    private static final Color FRAME = new Color(0xc3c2b7);
    private static final Color PLAIN = new Color(0xf0efec);
    private static final Color COMPONENT = new Color(0xcde2fb);
    private static final Color COMPONENT_EDGE = new Color(0x2a78d6);
    private static final Color STORE = new Color(0xfde3d6);
    private static final Color STORE_EDGE = new Color(0xeb6834);

    private static final Font TITLE = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font HEAD = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font SMALL = new Font("Segoe UI", Font.ITALIC, 12);

    private final Graphics2D g;

    private ArchitectureDiagram(Graphics2D g) {
        this.g = g;
    }

    public static void write(Path file) throws IOException {
        BufferedImage image = new BufferedImage(1500, 1010, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(SURFACE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        new ArchitectureDiagram(g).draw();
        g.dispose();
        Files.createDirectories(file.toAbsolutePath().getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    /** A rounded box with a bold first line and smaller lines under it, all centred. */
    private void box(int x, int y, int w, int h, Color fill, Color edge, String head, String... lines) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, 12, 12);
        g.setColor(fill);
        g.fill(shape);
        g.setColor(edge);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(shape);
        int lineHeight = 17;
        int textHeight = 18 + lines.length * lineHeight;
        int baseline = y + (h - textHeight) / 2 + 15;
        g.setColor(INK);
        g.setFont(HEAD);
        centred(head, x + w / 2, baseline);
        g.setFont(BODY);
        g.setColor(MUTED);
        for (int i = 0; i < lines.length; i++) {
            centred(lines[i], x + w / 2, baseline + 19 + i * lineHeight);
        }
    }

    private void frame(int x, int y, int w, int h, String label) {
        g.setColor(FRAME);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 16, 16));
        g.setFont(HEAD);
        g.setColor(INK);
        g.drawString(label, x + 16, y + 24);
    }

    private void centred(String text, int cx, int baseline) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, cx - metrics.stringWidth(text) / 2, baseline);
    }

    /** A vertical or horizontal arrow from (x1, y1) to (x2, y2) with an optional label beside its midpoint. */
    private void arrow(int x1, int y1, int x2, int y2, String label) {
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.8f));
        g.drawLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        Path2D head = new Path2D.Double();
        head.moveTo(x2, y2);
        head.lineTo(x2 - 10 * Math.cos(angle - 0.45), y2 - 10 * Math.sin(angle - 0.45));
        head.lineTo(x2 - 10 * Math.cos(angle + 0.45), y2 - 10 * Math.sin(angle + 0.45));
        head.closePath();
        g.fill(head);
        if (label != null) {
            g.setFont(SMALL);
            g.setColor(MUTED);
            g.drawString(label, (x1 + x2) / 2 + 10, (y1 + y2) / 2 + 4);
        }
    }

    private void draw() {
        g.setFont(TITLE);
        g.setColor(INK);
        g.drawString("Scalable Hospital Management System: architecture", 40, 44);

        // Legend
        box(945, 20, 215, 34, COMPONENT, COMPONENT_EDGE, "System-design component");
        box(1175, 20, 110, 34, STORE, STORE_EDGE, "Data store");
        box(1300, 20, 160, 34, PLAIN, FRAME, "Service / other part");

        // Clients
        box(160, 80, 360, 62, PLAIN, FRAME, "Web portal (browser)", "patients, doctors, pharmacy, administrators");
        box(560, 80, 300, 62, PLAIN, FRAME, "Emergency callers", "no account needed");
        box(900, 80, 440, 62, PLAIN, FRAME, "hms-bench load generator", "virtual users for the performance evaluation");
        arrow(340, 142, 340, 196, "HTTP / JSON");
        arrow(710, 142, 710, 196, null);
        arrow(1120, 142, 1120, 196, null);

        // Application
        frame(40, 196, 1420, 500, "Spring Boot application server  (stateless: several can run side by side, each with its own worker id)");
        box(70, 236, 660, 62, COMPONENT, COMPONENT_EDGE, "1. Rate limiter  (token bucket)",
                "first filter on every request; a bucket per client and per kind of request; excess gets HTTP 429");
        box(760, 236, 670, 62, PLAIN, FRAME, "Authentication interceptor",
                "bearer token -> session (read through the cache) -> role check");
        arrow(730, 267, 760, 267, null);

        int y = 330;
        int w = 164;
        String[][] services = {
                {"Auth & patients", "registration, login,", "profiles"},
                {"Doctors", "directory, search,", "suggestions"},
                {"Appointments", "slots, booking,", "cancellation"},
                {"Medical records", "access-controlled", "history"},
                {"Pharmacy", "medicine search,", "prescriptions, stock"},
                {"Billing", "bills, insurance", "cover, payment"},
                {"Emergency", "ambulance dispatch,", "priority queue"},
                {"Short links", "share a bill by", "short URL"}};
        for (int i = 0; i < services.length; i++) {
            box(70 + i * (w + 9), y, w, 84, PLAIN, FRAME, services[i][0], services[i][1], services[i][2]);
        }
        g.setFont(SMALL);
        g.setColor(MUTED);
        g.drawString("services call the components below", 70, y + 108);

        int cy = 470;
        box(70, cy, 250, 92, COMPONENT, COMPONENT_EDGE, "2. Unique ID generator", "Snowflake: 64-bit, time-ordered", "ids for every record, no", "round trip to the database");
        box(335, cy, 265, 92, COMPONENT, COMPONENT_EDGE, "3. Autocomplete", "tries with cached top-k,", "typo-tolerant; doctors,", "specializations, medicines");
        box(615, cy, 250, 92, COMPONENT, COMPONENT_EDGE, "4. URL shortener", "random base-62 codes,", "collision check, expiry,", "302 redirect at /s/{code}");
        box(880, cy, 265, 92, COMPONENT, COMPONENT_EDGE, "5. Web crawler", "breadth-first, robots.txt,", "politeness, de-duplication;", "drug info and insurance plans");
        box(1160, cy, 270, 92, COMPONENT, COMPONENT_EDGE, "6. Consistent hashing ring", "150 virtual nodes per server;", "picks the cache server(s)", "for each key, with failover");

        box(70, 596, 1360, 70, PLAIN, FRAME, "Cache-aside data access",
                "read: key-value cache first, MongoDB on a miss, then fill the cache    |    write: MongoDB, then evict the affected keys");

        // Stores and external sites
        arrow(400, 696, 400, 780, "queries, conditional updates, unique indexes");
        arrow(1010, 696, 1010, 780, "TCP, key chosen by the ring");
        arrow(1340, 696, 1340, 780, null);

        box(60, 780, 560, 190, STORE, STORE_EDGE, "MongoDB  (system of record)",
                "users, sessions (TTL index), patients, doctors,",
                "appointments (unique index on doctor + date + time),",
                "records, medicines, prescriptions, bills,",
                "emergencies, ambulances, short_urls (TTL index),",
                "drug_info and insurance_plans (from the crawler)");

        frame(650, 780, 560, 190, "7. Key-value store  (cache cluster)");
        int nx = 672;
        for (String node : new String[]{"n1", "n2", "n3", "n4 ..."}) {
            box(nx, 820, 118, 78, COMPONENT, COMPONENT_EDGE, "Node " + node, "in-memory,", "TTL + LRU");
            nx += 130;
        }
        g.setFont(BODY);
        g.setColor(MUTED);
        g.drawString("sessions, profiles, record lists, slots, bills, short links;", 672, 925);
        g.drawString("each key on 2 nodes; a lost node only costs cache misses", 672, 944);

        frame(1240, 780, 220, 190, "External sources");
        box(1258, 820, 184, 60, PLAIN, FRAME, "MedInfo site", "drug reference pages");
        box(1258, 892, 184, 60, PLAIN, FRAME, "InsureCo site", "insurance plans");
        g.setFont(SMALL);
        g.setColor(MUTED);
        g.drawString("crawled over HTTP", 1350, 742);
        g.drawString("(simulated sites)", 1350, 758);
    }
}

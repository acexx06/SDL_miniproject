package com.hms.components.autocomplete;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prefix autocomplete backed by a trie with cached top-k results.
 *
 * Every trie node keeps the {@code cacheSize} highest-weighted entries below it, so a lookup
 * costs O(length of prefix) however many entries are indexed. Each entry is indexed under every
 * word-suffix of its text, so "rao" finds "Dr. Asha Rao". When a prefix of four or more letters
 * has too few exact matches, a bounded search allowing one typo after the first letter (a wrong,
 * missing, extra or swapped character) fills the remaining places.
 *
 * @param <T> application data carried with each entry
 */
public final class Autocomplete<T> {

    public record Suggestion<T>(String id, String text, double weight, T payload) {
    }

    private static final class Entry {
        final String id;
        final String text;
        final String[] keys;
        double weight;
        Object payload;

        Entry(String id, String text, String[] keys, double weight, Object payload) {
            this.id = id;
            this.text = text;
            this.keys = keys;
            this.weight = weight;
            this.payload = payload;
        }
    }

    /** Children are parallel arrays: tries are sparse, and this is far smaller than a map per node. */
    private static final class Node {
        char[] labels = NO_LABELS;
        Node[] children = NO_NODES;
        Entry[] top = NO_ENTRIES;

        Node child(char c) {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == c) {
                    return children[i];
                }
            }
            return null;
        }
    }

    private static final char[] NO_LABELS = new char[0];
    private static final Node[] NO_NODES = new Node[0];
    private static final Entry[] NO_ENTRIES = new Entry[0];
    /** Shorter prefixes are too ambiguous to correct: one edit in three letters matches almost anything. */
    private static final int MIN_TYPO_LENGTH = 4;
    private static final Comparator<Entry> RANK =
            Comparator.comparingDouble((Entry e) -> -e.weight).thenComparing(e -> e.text);

    private final int cacheSize;
    private final boolean indexWordSuffixes;
    private final Node root = new Node();
    private final Map<String, Entry> entries = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private int nodeCount = 1;

    public Autocomplete() {
        this(10, true);
    }

    public Autocomplete(int cacheSize, boolean indexWordSuffixes) {
        this.cacheSize = cacheSize;
        this.indexWordSuffixes = indexWordSuffixes;
    }

    /** Lower-cases and collapses anything that is not a letter or digit into single spaces. */
    public static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(c);
                pendingSpace = false;
            } else {
                pendingSpace = true;
            }
        }
        return sb.toString();
    }

    private String[] keysFor(String normalized) {
        if (!indexWordSuffixes) {
            return new String[]{normalized};
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(normalized);
        for (int i = normalized.indexOf(' '); i >= 0; i = normalized.indexOf(' ', i + 1)) {
            keys.add(normalized.substring(i + 1));
        }
        return keys.toArray(new String[0]);
    }

    private void offer(Node node, Entry entry) {
        Entry[] top = node.top;
        boolean present = false;
        for (Entry e : top) {
            if (e == entry) {
                present = true;
                break;
            }
        }
        if (!present) {
            if (top.length < cacheSize) {
                top = Arrays.copyOf(top, top.length + 1);
            } else if (RANK.compare(entry, top[top.length - 1]) >= 0) {
                return;
            }
            top[top.length - 1] = entry;
        }
        Arrays.sort(top, RANK);
        node.top = top;
    }

    private void index(Entry entry) {
        for (String key : entry.keys) {
            Node node = root;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                Node next = node.child(c);
                if (next == null) {
                    next = new Node();
                    node.labels = Arrays.copyOf(node.labels, node.labels.length + 1);
                    node.children = Arrays.copyOf(node.children, node.children.length + 1);
                    node.labels[node.labels.length - 1] = c;
                    node.children[node.children.length - 1] = next;
                    nodeCount++;
                }
                node = next;
                offer(node, entry);
            }
        }
    }

    /** Adds an entry. Re-adding an existing id refreshes its payload and can raise its weight. */
    public void add(String id, String text, double weight, T payload) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            Entry existing = entries.get(id);
            if (existing != null) {
                existing.payload = payload;
                if (weight > existing.weight) {
                    existing.weight = weight;
                    index(existing);
                }
                return;
            }
            Entry entry = new Entry(id, text, keysFor(normalized), weight, payload);
            entries.put(id, entry);
            index(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Raises an entry's weight, for example each time a doctor is booked. */
    public void bump(String id, double delta) {
        if (delta <= 0) {
            return;
        }
        lock.writeLock().lock();
        try {
            Entry entry = entries.get(id);
            if (entry != null) {
                entry.weight += delta;
                index(entry);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Node walk(String prefix) {
        Node node = root;
        for (int i = 0; i < prefix.length() && node != null; i++) {
            node = node.child(prefix.charAt(i));
        }
        return node;
    }

    /** Collects the trie nodes reachable from {@code q} with exactly one edit. */
    private void fuzzy(Node node, String q, int i, boolean edited, Map<Node, Boolean> found) {
        if (i == q.length()) {
            if (edited) {
                found.put(node, Boolean.TRUE);
            }
            return;
        }
        char c = q.charAt(i);
        Node exact = node.child(c);
        if (exact != null) {
            fuzzy(exact, q, i + 1, edited, found);
        }
        if (edited || i == 0) { // one edit at most, and never on the first character
            return;
        }
        fuzzy(node, q, i + 1, true, found); // an extra character was typed
        for (int k = 0; k < node.labels.length; k++) {
            if (node.labels[k] != c) {
                fuzzy(node.children[k], q, i + 1, true, found); // a wrong character was typed
                fuzzy(node.children[k], q, i, true, found); // a character was left out
            }
        }
        if (i + 1 < q.length() && q.charAt(i + 1) != c) { // two characters were swapped
            Node first = node.child(q.charAt(i + 1));
            Node second = first == null ? null : first.child(c);
            if (second != null) {
                fuzzy(second, q, i + 2, true, found);
            }
        }
    }

    public List<Suggestion<T>> suggest(String prefix, int limit) {
        return suggest(prefix, limit, true);
    }

    @SuppressWarnings("unchecked")
    public List<Suggestion<T>> suggest(String prefix, int limit, boolean allowTypo) {
        String q = normalize(prefix);
        if (q.isEmpty()) {
            return List.of();
        }
        limit = Math.min(limit, cacheSize);
        lock.readLock().lock();
        try {
            List<Entry> picked = new ArrayList<>(limit);
            Node node = walk(q);
            if (node != null) {
                for (int i = 0; i < node.top.length && picked.size() < limit; i++) {
                    picked.add(node.top[i]);
                }
            }
            if (allowTypo && picked.size() < limit && q.length() >= MIN_TYPO_LENGTH) {
                Map<Node, Boolean> nodes = new IdentityHashMap<>();
                fuzzy(root, q, 0, false, nodes);
                Set<Entry> extra = new LinkedHashSet<>();
                for (Node n : nodes.keySet()) {
                    extra.addAll(Arrays.asList(n.top));
                }
                picked.forEach(extra::remove);
                extra.stream().sorted(RANK).limit(limit - picked.size()).forEach(picked::add);
            }
            List<Suggestion<T>> result = new ArrayList<>(picked.size());
            for (Entry e : picked) {
                result.add(new Suggestion<>(e.id, e.text, e.weight, (T) e.payload));
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int nodeCount() {
        return nodeCount;
    }
}

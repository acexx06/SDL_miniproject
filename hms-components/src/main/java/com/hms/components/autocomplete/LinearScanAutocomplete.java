package com.hms.components.autocomplete;

import java.util.ArrayList;
import java.util.List;

/** Baseline: compare the prefix against every entry on every keystroke and return matches A to Z. */
public final class LinearScanAutocomplete {

    private record Item(String normalized, String id, String text) {
    }

    private final List<Item> items = new ArrayList<>();

    public void add(String id, String text) {
        items.add(new Item(Autocomplete.normalize(text), id, text));
    }

    public List<String> suggest(String prefix, int limit) {
        String q = Autocomplete.normalize(prefix);
        if (q.isEmpty()) {
            return List.of();
        }
        String inner = " " + q;
        List<Item> matches = new ArrayList<>();
        for (Item item : items) {
            if (item.normalized.startsWith(q) || item.normalized.contains(inner)) {
                matches.add(item);
            }
        }
        matches.sort((a, b) -> a.text.compareTo(b.text));
        List<String> ids = new ArrayList<>(limit);
        for (int i = 0; i < Math.min(limit, matches.size()); i++) {
            ids.add(matches.get(i).id);
        }
        return ids;
    }

    public int size() {
        return items.size();
    }
}

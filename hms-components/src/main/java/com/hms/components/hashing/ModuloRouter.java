package com.hms.components.hashing;

import java.util.ArrayList;
import java.util.List;

/** Baseline: {@code hash(key) % N}. Perfectly balanced, but nearly every key moves when N changes. */
public final class ModuloRouter implements NodeRouter {

    private volatile List<String> nodes = List.of();

    @Override
    public synchronized void addNode(String node) {
        if (!nodes.contains(node)) {
            List<String> next = new ArrayList<>(nodes);
            next.add(node);
            nodes = List.copyOf(next);
        }
    }

    @Override
    public synchronized void removeNode(String node) {
        List<String> next = new ArrayList<>(nodes);
        next.remove(node);
        nodes = List.copyOf(next);
    }

    @Override
    public List<String> nodes() {
        return nodes;
    }

    @Override
    public String route(String key) {
        List<String> current = nodes;
        if (current.isEmpty()) {
            return null;
        }
        return current.get((int) Long.remainderUnsigned(NodeRouter.hash64(key), current.size()));
    }

    @Override
    public List<String> routeN(String key, int n) {
        List<String> current = nodes;
        List<String> result = new ArrayList<>();
        if (current.isEmpty()) {
            return result;
        }
        int start = (int) Long.remainderUnsigned(NodeRouter.hash64(key), current.size());
        for (int i = 0; i < Math.min(n, current.size()); i++) {
            result.add(current.get((start + i) % current.size()));
        }
        return result;
    }
}

package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class OptimisticDataStore implements DataStore {

    private final Map<String, AtomicReference<Node>> store;

    public OptimisticDataStore(Map<String, AtomicReference<Node>> store) {
        this.store = store;
    }

    public Node get(String key) {
        var ref = store.get(key);
        if (ref == null) throw new MissingKeyException(key);

        var node = ref.get();
        if (node == null) throw new MissingKeyException(key);

        return node;
    }

    public Node put(String key, JsonNode value, Long ifVersion) {
        while (true) {
            var ref = store.get(key);

            if (ref == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var next = Node.init(value);

                var existing = store.putIfAbsent(key, new AtomicReference<>(next));
                if (existing != null) continue;

                return next;
            }

            var current = ref.get();

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) {
                throw new InvalidVersionException(oldVersion, ifVersion);
            }

            var next = Node.next(value, oldVersion);
            if (ref.compareAndSet(current, next)) return next;
        }
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    public void delete(String key) {
        store.remove(key);
    }

    public Node patch(String key, JsonNode delta, Long ifVersion) {
        while (true) {
            var ref = store.get(key);

            if (ref == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var next = Node.init(delta);
                var existing = store.putIfAbsent(key, new AtomicReference<>(next));
                if (existing != null) continue;

                return next;
            }

            var current = ref.get();

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            var currentValue = current.value();
            JsonNode newValue;
            if (currentValue.isObject() && delta.isObject()) {
                ObjectNode merged = currentValue.deepCopy().asObject();
                merged.setAll(delta.asObject());
                newValue = merged;
            } else {
                newValue = delta;
            }

            var next = Node.next(newValue, oldVersion);
            if (ref.compareAndSet(current, next)) return next;
        }
    }
}

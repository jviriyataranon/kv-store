package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
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
        var ref = store.computeIfAbsent(key, k -> new AtomicReference<>(null));

        while (true) {
            var current = ref.get();
            if (current == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var next = Node.init(value);
                if (ref.compareAndSet(current, next)) return next;

                continue;
            }

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            var next = Node.next(value, oldVersion);
            if (ref.compareAndSet(current, next)) return next;
        }
    }

    public Node patch(String key, JsonNode delta, Long ifVersion) {
        var ref = store.computeIfAbsent(key, k -> new AtomicReference<>(null));

        while (true) {
            var current = ref.get();
            if (current == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var next = Node.init(delta);
                if (ref.compareAndSet(current, next)) return next;

                continue;
            }

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            var currentValue = current.value();
            if (currentValue.isObject() && delta.isObject()) {
                ObjectNode merged = currentValue.deepCopy().asObject();
                merged.setAll(delta.asObject());

                var next = Node.next(merged, oldVersion);
                if (ref.compareAndSet(current, next)) return next;

                continue;
            }

            var next = Node.next(delta, oldVersion);
            if (ref.compareAndSet(current, next)) return next;
        }
    }
}

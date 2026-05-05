package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.Node;
import jirat.viriyataranon.coda.kv.model.State;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class OptimisticDataStore implements DataStore {

    private final Map<String, AtomicReference<State>> store;

    public OptimisticDataStore(Map<String, AtomicReference<State>> store) {
        this.store = store;
    }

    @Override
    public Node get(String key) {
        var ref = store.get(key);
        if (ref == null) throw new MissingKeyException(key);

        var state = ref.get();
        if (state.isDeleted()) throw new MissingKeyException(key);

        return state.node();
    }

    @Override
    public Node put(String key, JsonNode value, Long ifVersion) {
        while (true) {
            var ref = store.get(key);

            if (ref == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var nextNode = Node.init(value);

                var existing = store.putIfAbsent(key, new AtomicReference<>(State.alive(nextNode)));
                if (existing != null) continue;

                return nextNode;
            }

            var state = ref.get();

            if (state.isDeleted()) {
                store.remove(key, ref);
                continue;
            }

            var oldVersion = state.node().version();
            if (ifVersion != null && ifVersion != oldVersion) {
                throw new InvalidVersionException(oldVersion, ifVersion);
            }

            var nextNode = Node.next(value, oldVersion);
            if (ref.compareAndSet(state, State.alive(nextNode))) {
                return nextNode;
            }
        }
    }

    @Override
    public Node patch(String key, JsonNode delta, Long ifVersion) {
        while (true) {
            var ref = store.get(key);

            if (ref == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                var node = Node.init(delta);

                var existing = store.putIfAbsent(key, new AtomicReference<>(State.alive(node)));
                if (existing != null) continue;

                return node;
            }

            var state = ref.get();

            if (state.isDeleted()) {
                store.remove(key, ref);
                continue;
            }

            var oldVersion = state.node().version();
            if (ifVersion != null && ifVersion != oldVersion) {
                throw new InvalidVersionException(oldVersion, ifVersion);
            }

            JsonNode newValue;

            var currentValue = state.node().value();
            if (currentValue.isObject() && delta.isObject()) {
                ObjectNode merged = currentValue.deepCopy().asObject();
                merged.setAll(delta.asObject());
                newValue = merged;
            } else {
                newValue = delta;
            }

            var nextNode = Node.next(newValue, oldVersion);

            if (ref.compareAndSet(state, State.alive(nextNode))) {
                return nextNode;
            }
        }
    }

    @Override
    public void delete(String key) {
        while (true) {
            var ref = store.get(key);
            if (ref == null) return;

            var state = ref.get();
            if (state.isDeleted()) return;

            if (ref.compareAndSet(state, State.removed())) {
                store.remove(key, ref);
                return;
            }
        }
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }
}

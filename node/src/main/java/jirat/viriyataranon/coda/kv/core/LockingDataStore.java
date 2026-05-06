package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class LockingDataStore implements DataStore {

    private final Map<String, Node> store;

    public LockingDataStore(Map<String, Node> store) {
        this.store = store;
    }

    public Node get(String key) {
        var node = store.get(key);
        if (node == null) throw new MissingKeyException(key);

        return node;
    }

    public Node put(String key, JsonNode value, Long ifVersion) {
        return store.compute(key, (ignored, current) -> {
            if (current == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                return Node.init(value);
            }

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            return Node.next(value, oldVersion);
        });
    }

    public Node patch(String key, JsonNode delta, Long ifVersion) {
        return store.compute(key, (ignored, current) -> {
            if (current == null) {
                if (ifVersion != null) throw new InvalidVersionException(ifVersion);

                return Node.init(delta);
            }

            var oldVersion = current.version();
            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            var currentValue = current.value();
            if (currentValue.isObject() && delta.isObject()) {
                ObjectNode merged = currentValue.deepCopy().asObject();
                merged.setAll(delta.asObject());

                return Node.next(merged, oldVersion);
            }

            return Node.next(delta, oldVersion);
        });
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    public void delete(String key) {
        store.remove(key);
    }
}

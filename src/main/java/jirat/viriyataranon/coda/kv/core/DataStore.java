package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DataStore {

    private final Map<String, Node> store;

    @Autowired
    public DataStore() {
        this.store = new ConcurrentHashMap<>();
    }

    public DataStore(Map<String, Node> store) {
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
            var currentValue = current.value();

            if (ifVersion != null && ifVersion != oldVersion) throw new InvalidVersionException(oldVersion, ifVersion);

            if (currentValue.isObject() && delta.isObject()) {
                ObjectNode merged = currentValue.deepCopy().asObject();
                merged.setAll(delta.asObject());

                return Node.next(merged, oldVersion);
            }

            return Node.next(delta, oldVersion);
        });
    }
}

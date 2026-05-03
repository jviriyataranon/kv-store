package jirat.viriyataranon.coda.kv.core;

import jirat.viriyataranon.coda.kv.model.Node;
import tools.jackson.databind.JsonNode;

public interface DataStore {

    Node get(String key);

    Node put(String key, JsonNode value, Long ifVersion);

    Node patch(String key, JsonNode delta, Long ifVersion);
}

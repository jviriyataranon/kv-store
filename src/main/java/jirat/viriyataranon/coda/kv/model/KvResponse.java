package jirat.viriyataranon.coda.kv.model;


import tools.jackson.databind.JsonNode;

public record KvResponse(String key, JsonNode value, long version) {
}

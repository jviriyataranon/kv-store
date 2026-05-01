package jirat.viriyataranon.coda.kv.model;


import tools.jackson.databind.JsonNode;

public record Node(JsonNode value, long version) {

    public static Node init(JsonNode value) {
        return new Node(value, 1);
    }

    public static Node next(JsonNode value,  long version) {
        return new Node(value, version + 1);
    }
}

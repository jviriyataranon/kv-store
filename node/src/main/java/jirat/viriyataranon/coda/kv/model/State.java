package jirat.viriyataranon.coda.kv.model;

public record State(Node node, boolean isDeleted) {

    public static State alive(Node node) {
        return new State(node, false);
    }

    public static State removed() {
        return new State(null, true);
    }
}
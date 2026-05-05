package jirat.viriyataranon.coda.kv.router.model;

public record RegistryResult(Status status, NodeInfo nodeInfo) {

    public static RegistryResult registered(NodeInfo nodeInfo) {
        return new RegistryResult(Status.REGISTERED, nodeInfo);
    }

    public static RegistryResult deregistered(NodeInfo nodeInfo) {
        return new RegistryResult(Status.DEREGISTERED, nodeInfo);
    }

    public static RegistryResult refreshed(NodeInfo nodeInfo) {
        return new RegistryResult(Status.REFRESHED, nodeInfo);
    }

    public enum Status {
        REGISTERED,
        DEREGISTERED,
        REFRESHED,
        EVICTED;
    }
}

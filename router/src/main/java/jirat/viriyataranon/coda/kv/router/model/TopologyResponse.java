package jirat.viriyataranon.coda.kv.router.model;

import java.util.Map;

public record TopologyResponse(long version, byte[] hasherState, Map<Integer, String> bucketToNode) {
}

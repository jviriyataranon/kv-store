package jirat.viriyataranon.coda.kv.model;

import java.util.Map;

public record TopologyResponse(long version, byte[] hasherState, Map<Integer, String> bucketToNode) {
}

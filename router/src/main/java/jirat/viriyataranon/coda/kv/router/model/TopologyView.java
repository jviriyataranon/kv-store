package jirat.viriyataranon.coda.kv.router.model;

import java.util.Map;

public record TopologyView(
        long version,
        Map<String, NodeInfo> nodeIdToInfo,
        Map<Integer, String> bucketToNodeId,
        byte[] state
) {
}

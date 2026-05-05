package jirat.viriyataranon.coda.kv.router.model;

import com.dynatrace.hash4j.consistent.ConsistentBucketSetHasher;
import com.dynatrace.hash4j.consistent.ConsistentHashing;
import com.dynatrace.hash4j.random.PseudoRandomGeneratorProvider;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record Topology(
        long version,
        Map<String, NodeInfo> nodeIdToInfo,
        Map<Integer, String> bucketToNodeId,
        ConsistentBucketSetHasher hasher
) {

    public Topology forkNextVersion() {
        var newHasher = ConsistentHashing.jumpBackAnchorHash(PseudoRandomGeneratorProvider.splitMix64_V1());
        newHasher.setState(this.hasher.getState());

        return new Topology(
                this.version + 1,
                new ConcurrentHashMap<>(this.nodeIdToInfo),
                new ConcurrentHashMap<>(this.bucketToNodeId),
                newHasher
        );
    }

    public TopologyView view() {

        return new TopologyView(
                this.version,
                Collections.unmodifiableMap(this.nodeIdToInfo),
                Collections.unmodifiableMap(this.bucketToNodeId),
                this.hasher.getState()
        );
    }
}

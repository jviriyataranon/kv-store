package jirat.viriyataranon.coda.kv.core;

import com.dynatrace.hash4j.consistent.ConsistentHashing;
import com.dynatrace.hash4j.random.PseudoRandomGeneratorProvider;
import jirat.viriyataranon.coda.kv.config.KvConfig;
import jirat.viriyataranon.coda.kv.model.TopologyResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TopologyCleanupTest {

    private static final String MY_NODE = "my-node";
    private static final String OTHER_NODE = "other-node";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KvConfig configFor(String nodeId) {
        var config = new KvConfig();
        config.setNodeId(nodeId);
        config.setRouterUrl("http://localhost:6999");
        return config;
    }

    private static TopologyResponse singleBucketTopology(String ownerNodeId) {
        var hasher = ConsistentHashing.jumpBackAnchorHash(PseudoRandomGeneratorProvider.splitMix64_V1());
        int bucket = hasher.addBucket();
        return new TopologyResponse(1L, hasher.getState(), Map.of(bucket, ownerNodeId));
    }

    @Test
    void sweep_keysOwnedByOtherNode_deletesAll() throws Exception {
        var store = new LockingDataStore(new ConcurrentHashMap<>());
        store.put("key-a", MAPPER.readTree("1"), null);
        store.put("key-b", MAPPER.readTree("2"), null);

        var cleanup = new TopologyCleanup(configFor(MY_NODE), store);
        cleanup.start();
        cleanup.enqueue(singleBucketTopology(OTHER_NODE));

        await().atMost(Duration.ofSeconds(3)).until(() -> store.keys().isEmpty());
        assertThat(store.keys()).isEmpty();
        cleanup.stop();
    }

    @Test
    void sweep_keysOwnedByThisNode_keepsAll() throws Exception {
        var store = new LockingDataStore(new ConcurrentHashMap<>());
        store.put("key-a", MAPPER.readTree("1"), null);
        store.put("key-b", MAPPER.readTree("2"), null);

        var cleanup = new TopologyCleanup(configFor(MY_NODE), store);
        cleanup.start();
        cleanup.enqueue(singleBucketTopology(MY_NODE));

        await()
                .during(Duration.ofMillis(200))
                .atMost(Duration.ofMillis(500))
                .until(() -> store.keys().containsAll(java.util.Set.of("key-a", "key-b")));
        assertThat(store.keys()).containsExactlyInAnyOrder("key-a", "key-b");
        cleanup.stop();
    }

    @Test
    void sweep_noRouterConfigured_doesNotStart() throws Exception {
        var store = new LockingDataStore(new ConcurrentHashMap<>());
        store.put("key-a", MAPPER.readTree("1"), null);

        var config = new KvConfig();
        config.setNodeId(MY_NODE);

        var cleanup = new TopologyCleanup(config, store);
        cleanup.start();
        cleanup.enqueue(singleBucketTopology(OTHER_NODE));

        await()
                .during(Duration.ofMillis(200))
                .atMost(Duration.ofMillis(500))
                .until(() -> store.keys().containsAll(java.util.Set.of("key-a")));
        assertThat(store.keys()).containsExactly("key-a");
        cleanup.stop();
    }
}

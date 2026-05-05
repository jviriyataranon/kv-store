package jirat.viriyataranon.coda.kv.router.core;

import jirat.viriyataranon.coda.kv.router.config.RouterConfig;
import jirat.viriyataranon.coda.kv.router.exception.UnknownNodeException;
import jirat.viriyataranon.coda.kv.router.model.RegistryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class NodeRegistryTest {

    private NodeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry(new RouterConfig());
        // startEviction() is NOT called — keeps tests deterministic
    }

    // ─── register ─────────────────────────────────────────────────────────────

    @Test
    void register_newNode_returnsRegisteredStatus() {
        var result = registry.register("n1", "http://host:1234");

        assertThat(result.status()).isEqualTo(RegistryResult.Status.REGISTERED);
        assertThat(result.nodeInfo().nodeId()).isEqualTo("n1");
        assertThat(result.nodeInfo().url()).isEqualTo("http://host:1234");
        assertThat(registry.topology().nodeIdToInfo()).containsKey("n1");
        assertThat(registry.nodeLastSeen()).containsKey("n1");
    }

    @Test
    void register_newNode_incrementsTopologyVersion() {
        var before = registry.topology().version();
        registry.register("n1", "http://host:1234");
        assertThat(registry.topology().version()).isEqualTo(before + 1);
    }

    @Test
    void register_sameNode_returnsRefreshedStatus() throws InterruptedException {
        registry.register("n1", "http://host:1234");
        var seenBefore = registry.nodeLastSeen().get("n1");

        Thread.sleep(2);
        var result = registry.register("n1", "http://host:1234");

        assertThat(result.status()).isEqualTo(RegistryResult.Status.REFRESHED);
        assertThat(registry.nodeLastSeen().get("n1")).isAfter(seenBefore);
    }

    @Test
    void register_sameNode_doesNotIncrementVersion() {
        registry.register("n1", "http://host:1234");
        var versionAfterFirst = registry.topology().version();

        registry.register("n1", "http://host:1234");

        assertThat(registry.topology().version()).isEqualTo(versionAfterFirst);
    }

    @Test
    void register_twoDistinctNodes_versionIncrementsTwice() {
        var initial = registry.topology().version();

        registry.register("n1", "http://host1:1111");
        registry.register("n2", "http://host2:2222");

        assertThat(registry.topology().version()).isEqualTo(initial + 2);
    }

    // ─── deregister ───────────────────────────────────────────────────────────

    @Test
    void deregister_knownNode_returnsDeregisteredStatus() {
        registry.register("n1", "http://host:1234");
        var result = registry.deregister("n1");

        assertThat(result.status()).isEqualTo(RegistryResult.Status.DEREGISTERED);
        assertThat(result.nodeInfo().nodeId()).isEqualTo("n1");
    }

    @Test
    void deregister_knownNode_removedFromTopology() {
        registry.register("n1", "http://host:1234");
        registry.deregister("n1");

        assertThat(registry.topology().nodeIdToInfo()).doesNotContainKey("n1");
        assertThat(registry.nodeLastSeen()).doesNotContainKey("n1");
    }

    @Test
    void deregister_knownNode_incrementsVersion() {
        registry.register("n1", "http://host:1234");
        var versionAfterRegister = registry.topology().version();

        registry.deregister("n1");

        assertThat(registry.topology().version()).isEqualTo(versionAfterRegister + 1);
    }

    @Test
    void deregister_unknownNode_throwsUnknownNodeExceptionSameVersion() {
        var initialVersion = registry.topology().version();

        assertThatThrownBy(() -> registry.deregister("ghost"))
                .isInstanceOf(UnknownNodeException.class);
        assertThat(registry.topology().version()).isEqualTo(initialVersion);
    }

    // ─── resolveNode ──────────────────────────────────────────────────────────

    @Test
    void resolveNode_emptyRegistry_throwsUnknownNodeException() {
        assertThatThrownBy(() -> registry.resolveNode("any-key"))
                .isInstanceOf(UnknownNodeException.class);
    }

    @Test
    void resolveNode_singleNode_alwaysReturnsNode() {
        registry.register("n1", "http://host:1234");

        var node = registry.resolveNode("any-key");

        assertThat(node.nodeId()).isEqualTo("n1");
        assertThat(node.url()).isEqualTo("http://host:1234");
    }

    @Test
    void resolveNode_sameKeyReturnsSameNodeConsistently() {
        registry.register("n1", "http://host1:1111");
        registry.register("n2", "http://host2:2222");

        var first = registry.resolveNode("stable-key");
        var second = registry.resolveNode("stable-key");

        assertThat(first.nodeId()).isEqualTo(second.nodeId());
    }

    @Test
    void resolveNode_afterDeregister_remainingNodeServesAllKeys() {
        registry.register("n1", "http://host1:1111");
        registry.register("n2", "http://host2:2222");

        registry.deregister("n1");

        var node = registry.resolveNode("any-key");
        assertThat(node.nodeId()).isEqualTo("n2");
    }

    // ─── evict ────────────────────────────────────────────────────────────────

    @Test
    void evict_healthyNode_retained() throws InterruptedException {
        var config = new RouterConfig();
        config.setEvictionThresholdMs(300);
        config.setEvictionCheckIntervalMs(50);
        var evictingRegistry = new NodeRegistry(config);
        evictingRegistry.startEviction();

        try {
            evictingRegistry.register("stale", "http://stale:9999");
            Thread.sleep(200);
            evictingRegistry.register("healthy", "http://healthy:9999");
            // healthy has ~300ms before it becomes stale — enough margin for await + assert

            await().atMost(Duration.ofSeconds(3))
                    .pollInterval(Duration.ofMillis(10))
                    .untilAsserted(() -> {
                        assertThat(evictingRegistry.topology().nodeIdToInfo()).doesNotContainKey("stale");
                        assertThat(evictingRegistry.topology().nodeIdToInfo()).containsKey("healthy");
                    });
        } finally {
            evictingRegistry.stopEviction();
        }
    }

    @Test
    void evict_staleNode_removedFromTopology() {
        var config = new RouterConfig();
        config.setEvictionThresholdMs(0);
        config.setEvictionCheckIntervalMs(50);
        var evictingRegistry = new NodeRegistry(config);
        evictingRegistry.startEviction();

        try {
            evictingRegistry.register("stale", "http://host:9999");

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> evictingRegistry.topology().nodeIdToInfo().isEmpty());

            assertThat(evictingRegistry.topology().nodeIdToInfo()).isEmpty();
        } finally {
            evictingRegistry.stopEviction();
        }
    }

    @Test
    void evict_staleNode_removedFromNodeLastSeen() {
        // Regression: evict() must clean nodeLastSeen or subsequent eviction cycles NPE
        // on nodeIdToInfo().remove() returning null for an already-evicted node.
        var config = new RouterConfig();
        config.setEvictionThresholdMs(0);
        config.setEvictionCheckIntervalMs(50);
        var evictingRegistry = new NodeRegistry(config);
        evictingRegistry.startEviction();

        try {
            evictingRegistry.register("stale", "http://host:9999");

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> evictingRegistry.nodeLastSeen().isEmpty());

            assertThat(evictingRegistry.nodeLastSeen()).doesNotContainKey("stale");
        } finally {
            evictingRegistry.stopEviction();
        }
    }

    @Test
    void evict_staleNode_incrementsTopologyVersion() {
        var config = new RouterConfig();
        config.setEvictionThresholdMs(0);
        config.setEvictionCheckIntervalMs(50);
        var evictingRegistry = new NodeRegistry(config);
        evictingRegistry.startEviction();

        try {
            evictingRegistry.register("stale", "http://host:9999");
            var versionAfterRegister = evictingRegistry.topology().version();

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> evictingRegistry.topology().version() > versionAfterRegister);

            assertThat(evictingRegistry.topology().version()).isGreaterThan(versionAfterRegister);
        } finally {
            evictingRegistry.stopEviction();
        }
    }

    @Test
    void evict_thenReregister_treatedAsNewNode() {
        var config = new RouterConfig();
        config.setEvictionThresholdMs(0);
        config.setEvictionCheckIntervalMs(50);
        var evictingRegistry = new NodeRegistry(config);
        evictingRegistry.startEviction();

        try {
            evictingRegistry.register("n1", "http://host:9999");

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> evictingRegistry.topology().nodeIdToInfo().isEmpty());

            var result = evictingRegistry.register("n1", "http://host:9999");
            assertThat(result.status()).isEqualTo(RegistryResult.Status.REGISTERED);
            assertThat(evictingRegistry.topology().nodeIdToInfo()).containsKey("n1");
        } finally {
            evictingRegistry.stopEviction();
        }
    }

    // ─── register (url change) ────────────────────────────────────────────────

    @Test
    void register_sameNodeDifferentUrl_urlIsNotUpdated() {
        // URL is fixed at registration time; re-registration only refreshes lastSeen.
        registry.register("n1", "http://original:1234");
        registry.register("n1", "http://changed:5678");

        assertThat(registry.topology().nodeIdToInfo().get("n1").url()).isEqualTo("http://original:1234");
    }

    // ─── resolveNode (distribution) ──────────────────────────────────────────

    @Test
    void resolveNode_multipleNodes_keysDistributedAcrossNodes() {
        registry.register("n1", "http://host1:1111");
        registry.register("n2", "http://host2:2222");
        registry.register("n3", "http://host3:3333");

        var resolvedNodes = new java.util.HashSet<String>();
        for (int i = 0; i < 100; i++) {
            resolvedNodes.add(registry.resolveNode("key-" + i).nodeId());
        }

        assertThat(resolvedNodes).hasSize(3);
    }
}

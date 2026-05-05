package jirat.viriyataranon.coda.kv.router.core;

import com.dynatrace.hash4j.consistent.ConsistentHashing;
import com.dynatrace.hash4j.hashing.Hashing;
import com.dynatrace.hash4j.random.PseudoRandomGeneratorProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jirat.viriyataranon.coda.kv.router.config.RouterConfig;
import jirat.viriyataranon.coda.kv.router.exception.UnknownNodeException;
import jirat.viriyataranon.coda.kv.router.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class NodeRegistry {

    private static final String THREAD_NAME = "node-eviction";

    private final RouterConfig config;
    private final Map<String, Instant> nodeLastSeen;
    private final AtomicReference<Topology> topology;

    private ScheduledExecutorService evictionScheduler;

    @Autowired
    public NodeRegistry(RouterConfig config) {
        this.config = config;
        this.nodeLastSeen = new ConcurrentHashMap<>();

        var hasher = ConsistentHashing.jumpBackAnchorHash(PseudoRandomGeneratorProvider.splitMix64_V1());
        this.topology = new AtomicReference<>(
                new Topology(0, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), hasher)
        );
    }

    @PostConstruct
    public void startEviction() {
        evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, THREAD_NAME));
        evictionScheduler.scheduleAtFixedRate(
                this::evict,
                config.getEvictionCheckIntervalMs(),
                config.getEvictionCheckIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void stopEviction() {
        evictionScheduler.shutdownNow();
    }

    public synchronized RegistryResult register(String id, String url) {
        var snapshot = topology.get();
        var snapshotNodeIdToInfo = snapshot.nodeIdToInfo();

        if (snapshotNodeIdToInfo.containsKey(id)) {
            nodeLastSeen.put(id, Instant.now());

            return RegistryResult.refreshed(snapshotNodeIdToInfo.get(id));
        }

        var newTopology = snapshot.forkNextVersion();

        var bucket = newTopology.hasher().addBucket();
        var nodeInfo = new NodeInfo(id, url, bucket);

        newTopology.nodeIdToInfo().put(id, nodeInfo);
        newTopology.bucketToNodeId().put(bucket, id);

        topology.set(newTopology);
        nodeLastSeen.put(id, Instant.now());

        return RegistryResult.registered(nodeInfo);
    }

    public synchronized RegistryResult deregister(String id) {
        var snapshot = topology.get();
        var snapshotNodeIdToInfo = snapshot.nodeIdToInfo();

        if (!snapshotNodeIdToInfo.containsKey(id)) {
            throw new UnknownNodeException("Deregister on unknown nodeId: " + id);
        }

        var newTopology = snapshot.forkNextVersion();
        var nodeInfo = newTopology.nodeIdToInfo().remove(id);

        var bucket = nodeInfo.bucket();
        newTopology.hasher().removeBucket(bucket);
        newTopology.bucketToNodeId().remove(bucket);

        topology.set(newTopology);
        nodeLastSeen.remove(id);

        return RegistryResult.deregistered(nodeInfo);
    }

    private synchronized void evict() {
        var startDateTime = Instant.now();
        var threshold = Instant.now().minusMillis(config.getEvictionThresholdMs());

        var staleNodes = new ArrayList<String>();
        for (var entry : nodeLastSeen.entrySet()) {
            if (entry.getValue().isBefore(threshold)) {
                staleNodes.add(entry.getKey());
            }
        }

        if (staleNodes.isEmpty()) return;

        var newTopology = topology.get().forkNextVersion();

        var evictedNodes = new ArrayList<NodeInfo>();
        for (var nodeId : staleNodes) {
            var nodeInfo = newTopology.nodeIdToInfo().remove(nodeId);
            var bucket = nodeInfo.bucket();

            newTopology.hasher().removeBucket(bucket);
            newTopology.bucketToNodeId().remove(bucket);
            nodeLastSeen.remove(nodeId);
            evictedNodes.add(nodeInfo);
        }

        topology.set(newTopology);

        var endDateTime = Instant.now();
        log.atInfo()
                .addKeyValue("operation", RouterOperation.REGISTRY)
                .addKeyValue("registryStatus", RegistryResult.Status.EVICTED)
                .addKeyValue("targetNodes", evictedNodes)
                .addKeyValue("startDateTime", startDateTime)
                .addKeyValue("endDateTime", endDateTime)
                .addKeyValue("executionTime", Duration.between(startDateTime, endDateTime).toMillis())
                .log("Evicted {} nodes at version: {}", evictedNodes.size(), newTopology.version());
    }

    public NodeInfo resolveNode(String key) {
        var snapshot = topology.get();
        var snapshotBucketToNodeId = snapshot.bucketToNodeId();
        if (snapshotBucketToNodeId.isEmpty()) throw new UnknownNodeException("Empty registry");

        int bucket = snapshot.hasher().getBucket(Hashing.komihash5_0().hashCharsToLong(key));
        var nodeInfo = snapshot.nodeIdToInfo().get(snapshotBucketToNodeId.get(bucket));
        if (nodeInfo == null) throw new IllegalStateException("Topology and hasher is not in sync");

        return nodeInfo;
    }

    public TopologyView topology() {
        return topology.get().view();
    }

    public Map<String, Instant> nodeLastSeen() {
        return Collections.unmodifiableMap(nodeLastSeen);
    }
}

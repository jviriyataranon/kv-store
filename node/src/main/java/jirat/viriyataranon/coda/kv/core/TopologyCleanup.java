package jirat.viriyataranon.coda.kv.core;

import com.dynatrace.hash4j.consistent.ConsistentHashing;
import com.dynatrace.hash4j.hashing.Hashing;
import com.dynatrace.hash4j.random.PseudoRandomGeneratorProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jirat.viriyataranon.coda.kv.config.KvConfig;
import jirat.viriyataranon.coda.kv.model.TopologyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class TopologyCleanup {

    private static final String THREAD_NAME = "topology-cleanup";

    private final KvConfig config;
    private final DataStore dataStore;

    private final LinkedBlockingQueue<TopologyResponse> queue = new LinkedBlockingQueue<>();
    private ExecutorService sweepExecutor;

    @PostConstruct
    public void start() {
        if (StringUtils.isEmpty(config.getRouterUrl())) {
            log.atWarn().log("No router configured, no cleanup needed");
            return;
        }

        sweepExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, THREAD_NAME));
        sweepExecutor.submit(this::sweepWatcher);
    }

    @PreDestroy
    public void stop() {
        if (sweepExecutor != null) sweepExecutor.shutdownNow();
    }

    public void enqueue(TopologyResponse topology) {
        queue.offer(topology);
    }

    private void sweepWatcher() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var startDateTime = Instant.now();
                var topology = queue.take();

                var hasher = ConsistentHashing
                        .jumpBackAnchorHash(PseudoRandomGeneratorProvider.splitMix64_V1())
                        .setState(topology.hasherState());

                var deleted = 0;
                for (var key : dataStore.keys()) {
                    int bucket = hasher.getBucket(Hashing.komihash5_0().hashCharsToLong(key));
                    var owner = topology.bucketToNode().get(bucket);

                    if (!config.getNodeId().equals(owner)) {
                        dataStore.delete(key);
                        deleted++;
                    }
                }

                if (deleted > 0) {
                    var endDateTime = Instant.now();
                    log.atInfo()
                            .addKeyValue("startDateTime", startDateTime)
                            .addKeyValue("endDateTime", endDateTime)
                            .addKeyValue("executionTime", Duration.between(startDateTime, endDateTime).toMillis())
                            .log("Topology sweep deleted {} obsolete keys", deleted);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.atWarn().setCause(e).log("Topology sweep failed");
            }
        }
    }
}

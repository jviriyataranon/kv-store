package jirat.viriyataranon.coda.kv.core;

import org.apache.commons.lang3.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jirat.viriyataranon.coda.kv.config.KvConfig;
import jirat.viriyataranon.coda.kv.model.RegisterRequest;
import jirat.viriyataranon.coda.kv.model.TopologyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class HeartbeatClient {

    private static final String THREAD_NAME = "heartbeat-client";
    private static final long AWAIT_TERMINATION_MS = 5000;

    private final KvConfig config;
    private final TopologyCleanup topologyCleanup;
    private final RestClient restClient;
    private final String nodeBaseUrl;

    private ScheduledExecutorService heartbeatScheduler;

    private volatile TopologyResponse cachedTopology;

    public HeartbeatClient(
            KvConfig config,
            TopologyCleanup topologyCleanup,
            RestClient restClient,
            @Value("${server.port}") int serverPort
    ) {
        this.config = config;
        this.topologyCleanup = topologyCleanup;
        this.restClient = restClient;

        this.nodeBaseUrl = String.format("%s%s:%s", config.getScheme(), config.resolveNodeAddress(), serverPort);
    }

    @PostConstruct
    public void start() {
        if (StringUtils.isEmpty(config.getRouterUrl())) {
            log.atWarn().log("No router configured, heartbeat disabled");
            return;
        }

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(THREAD_NAME).factory());
        heartbeatScheduler.scheduleAtFixedRate(
                this::heartbeat, 0, config.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void stop() {
        if (StringUtils.isEmpty(config.getRouterUrl())) {
            log.atWarn().log("No router configured, deregister skipped");
            return;
        }

        try {
            heartbeatScheduler.shutdown();
            heartbeatScheduler.awaitTermination(AWAIT_TERMINATION_MS, TimeUnit.MILLISECONDS);

            restClient.delete()
                    .uri(config.getRouterUrl() + "/internal/nodes/{id}", config.getNodeId())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.atWarn().setCause(e).log("Deregister failed on shutdown");
        }
    }

    private void heartbeat() {
        try {
            var response = restClient.put()
                    .uri(config.getRouterUrl() + "/internal/nodes/{id}", config.getNodeId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RegisterRequest(nodeBaseUrl))
                    .retrieve()
                    .body(TopologyResponse.class);

            if (response == null) return;

            if (cachedTopology == null || cachedTopology.version() != response.version()) {
                cachedTopology = response;
                topologyCleanup.enqueue(response);
            }
        } catch (Exception e) {
            log.atWarn().setCause(e).log("Heartbeat failed");
        }
    }
}

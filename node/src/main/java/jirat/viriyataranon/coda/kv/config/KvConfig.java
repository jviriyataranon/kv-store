package jirat.viriyataranon.coda.kv.config;

import jirat.viriyataranon.coda.kv.core.DataStore;
import jirat.viriyataranon.coda.kv.core.LockingDataStore;
import jirat.viriyataranon.coda.kv.core.OptimisticDataStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
@Configuration
@ConfigurationProperties("kv")
public class KvConfig {

    private String nodeId = UUID.randomUUID().toString();
    private String dataStoreType = "locking";
    private String routerUrl;
    private long heartbeatIntervalMs = 5000;
    private String scheme = "http://";
    private String nodeAddress;

    private int httpConnectTimeoutMs = 2000;
    private int httpReadTimeoutMs = 10000;

    public String resolveNodeAddress() {
        if (StringUtils.isNotEmpty(nodeAddress)) return nodeAddress;

        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    @Bean
    public RestClient restClient() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(httpConnectTimeoutMs))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(httpReadTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    public DataStore dataStore() {
        log.atInfo().log("Using [{}] data store", dataStoreType);

        return switch (dataStoreType) {
            case "locking" -> new LockingDataStore(new ConcurrentHashMap<>());
            case "optimistic" -> new OptimisticDataStore(new ConcurrentHashMap<>());
            default -> throw new IllegalStateException("Unsupported data store type: " + dataStoreType);
        };
    }
}

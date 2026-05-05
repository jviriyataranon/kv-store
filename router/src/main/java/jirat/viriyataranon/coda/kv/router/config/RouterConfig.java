package jirat.viriyataranon.coda.kv.router.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Data
@Slf4j
@Configuration
@ConfigurationProperties("router")
public class RouterConfig {

    private long evictionThresholdMs = 15000;
    private long evictionCheckIntervalMs = 5000;
    private int httpConnectTimeoutMs = 2000;
    private int httpReadTimeoutMs = 10000;
    private int httpMaxConnectionTotal = 300;
    private int httpMaxConnectionPerRoute = 100;

    @Bean
    public RestClient restClient() {
        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(httpMaxConnectionTotal);
        connectionManager.setDefaultMaxPerRoute(httpMaxConnectionPerRoute);

        var httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectionRequestTimeout(httpConnectTimeoutMs);
        requestFactory.setReadTimeout(httpReadTimeoutMs);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}

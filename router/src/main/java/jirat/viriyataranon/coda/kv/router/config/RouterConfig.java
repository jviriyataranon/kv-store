package jirat.viriyataranon.coda.kv.router.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Data
@Slf4j
@Configuration
@ConfigurationProperties("router")
public class RouterConfig {

    private long evictionThresholdMs = 15000;
    private long evictionCheckIntervalMs = 5000;
    private int httpConnectTimeoutMs = 2000;
    private int httpReadTimeoutMs = 10000;

    @Bean
    public RestClient restClient() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(httpConnectTimeoutMs))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(httpReadTimeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}

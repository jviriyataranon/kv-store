package jirat.viriyataranon.coda.kv.config;

import jirat.viriyataranon.coda.kv.core.DataStore;
import jirat.viriyataranon.coda.kv.core.LockingDataStore;
import jirat.viriyataranon.coda.kv.core.OptimisticDataStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
@Configuration
@ConfigurationProperties("kv")
public class KvConfig {

    private String dataStoreType = "locking";

    @Bean
    public DataStore dataStore() {
        log.info("Using [{}] data store", dataStoreType);

        return switch (dataStoreType) {
            case "locking" -> new LockingDataStore(new ConcurrentHashMap<>());
            case "optimistic" -> new OptimisticDataStore(new ConcurrentHashMap<>());
            default -> throw new IllegalStateException("Unsupported data store type: " + dataStoreType);
        };
    }
}

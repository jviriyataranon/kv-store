package jirat.viriyataranon.coda.kv.router.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
public class RouterRequestContext {

    public static final String KEY = "RouterRequestContext";

    private Instant startDateTime;
    private String correlationId;
    private String requestId;

    private RouterOperation operation;
    private RegistryResult.Status registryStatus;
    private List<NodeInfo> targetNodes;
    private String key;

    private Exception exception;
}

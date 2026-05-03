package jirat.viriyataranon.coda.kv.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

@Data
@NoArgsConstructor
public class KvRequestContext {

    public static final String KEY = "KvRequestContext";

    private Instant startDateTime;
    private String correlationId;
    private String requestId;

    private Operation operation;
    private String key;
    private JsonNode value;
    private Long ifVersion;
    private JsonNode returnValue;
    private Long returnVersion;

    private Exception exception;
}

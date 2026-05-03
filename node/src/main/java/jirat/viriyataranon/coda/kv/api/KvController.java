package jirat.viriyataranon.coda.kv.api;

import jirat.viriyataranon.coda.kv.config.KvConfig;
import jirat.viriyataranon.coda.kv.core.DataStore;
import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.KvListKeyResponse;
import jirat.viriyataranon.coda.kv.model.KvRequestContext;
import jirat.viriyataranon.coda.kv.model.KvResponse;
import jirat.viriyataranon.coda.kv.model.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/kv")
@RequiredArgsConstructor
public class KvController {

    private final KvConfig config;
    private final DataStore dataStore;
    private final ObjectMapper objectMapper;

    private static final int LIST_FLUSH_BATCH = 64;

    @GetMapping(produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> list(
            @RequestAttribute(KvRequestContext.KEY) KvRequestContext context
    ) {
        context.setOperation(Operation.LIST);

        StreamingResponseBody body = output -> {
            int count = 0;
            for (var key : dataStore.keys()) {
                output.write(objectMapper.writeValueAsBytes(new KvListKeyResponse(key, config.getNodeId())));
                output.write('\n');

                count++;
                if (count % LIST_FLUSH_BATCH == 0) output.flush();
            }

            output.flush();
        };

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_NDJSON).body(body);
    }

    @GetMapping("/{key}")
    public ResponseEntity<KvResponse> get(
            @RequestAttribute(KvRequestContext.KEY) KvRequestContext context,
            @PathVariable String key
    ) {
        context.setOperation(Operation.GET);
        context.setKey(key);

        try {
            var node = dataStore.get(key);
            var returnValue = node.value();
            var returnVersion = node.version();

            context.setReturnValue(returnValue);
            context.setReturnVersion(returnVersion);

            return ResponseEntity.ok(new KvResponse(key, returnValue, returnVersion));
        } catch (MissingKeyException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PutMapping("/{key}")
    public ResponseEntity<KvResponse> put(
            @RequestAttribute(KvRequestContext.KEY) KvRequestContext context,
            @PathVariable String key,
            @RequestBody JsonNode value,
            @RequestHeader(value = "ifVersion", required = false) Long ifVersion
    ) {
        context.setOperation(Operation.PUT);
        context.setKey(key);
        context.setValue(value);
        context.setIfVersion(ifVersion);

        try {
            var node = dataStore.put(key, value, ifVersion);
            var returnValue = node.value();
            var returnVersion = node.version();

            context.setReturnValue(returnValue);
            context.setReturnVersion(returnVersion);

            return ResponseEntity.ok(new KvResponse(key, returnValue, returnVersion));
        } catch (InvalidVersionException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PatchMapping("/{key}")
    public ResponseEntity<KvResponse> patch(
            @RequestAttribute(KvRequestContext.KEY) KvRequestContext context,
            @PathVariable String key,
            @RequestBody JsonNode value,
            @RequestHeader(value = "ifVersion", required = false) Long ifVersion
    ) {
        context.setOperation(Operation.PATCH);
        context.setKey(key);
        context.setValue(value);
        context.setIfVersion(ifVersion);

        try {
            var node = dataStore.patch(key, value, ifVersion);
            var returnValue = node.value();
            var returnVersion = node.version();

            context.setReturnValue(returnValue);
            context.setReturnVersion(returnVersion);

            return ResponseEntity.ok(new KvResponse(key, returnValue, returnVersion));
        } catch (InvalidVersionException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}

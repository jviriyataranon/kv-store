package jirat.viriyataranon.coda.kv.api;

import jirat.viriyataranon.coda.kv.core.DataStore;
import jirat.viriyataranon.coda.kv.exception.InvalidVersionException;
import jirat.viriyataranon.coda.kv.exception.MissingKeyException;
import jirat.viriyataranon.coda.kv.model.KvRequestContext;
import jirat.viriyataranon.coda.kv.model.KvResponse;
import jirat.viriyataranon.coda.kv.model.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/kv")
@RequiredArgsConstructor
public class KvController {

    private final DataStore dataStore;

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

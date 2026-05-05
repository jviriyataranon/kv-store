package jirat.viriyataranon.coda.kv.router.api;

import jirat.viriyataranon.coda.kv.router.core.NodeRegistry;
import jirat.viriyataranon.coda.kv.router.exception.UnknownNodeException;
import jirat.viriyataranon.coda.kv.router.model.RegisterRequest;
import jirat.viriyataranon.coda.kv.router.model.RouterOperation;
import jirat.viriyataranon.coda.kv.router.model.RouterRequestContext;
import jirat.viriyataranon.coda.kv.router.model.TopologyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/nodes")
@RequiredArgsConstructor
public class RegistrationController {

    private final NodeRegistry registry;

    @PutMapping("/{id}")
    public ResponseEntity<TopologyResponse> register(
            @PathVariable String id,
            @RequestBody RegisterRequest body,
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context
    ) {
        context.setOperation(RouterOperation.REGISTRY);

        var result = registry.register(id, body.url());
        context.setRegistryStatus(result.status());
        context.setTargetNodes(List.of(result.nodeInfo()));

        var topology = registry.topology();
        var response = new TopologyResponse(topology.version(), topology.state(), topology.bucketToNodeId());
        return ResponseEntity.ok().body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deregister(
            @PathVariable String id,
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context
    ) {
        context.setOperation(RouterOperation.REGISTRY);

        try {
            var result = registry.deregister(id);
            context.setRegistryStatus(result.status());
            context.setTargetNodes(List.of(result.nodeInfo()));

            return ResponseEntity.noContent().build();
        } catch (UnknownNodeException e) {
            context.setException(e);

            return ResponseEntity.notFound().build();
        }
    }
}

package jirat.viriyataranon.coda.kv.router.api;

import jakarta.servlet.http.HttpServletRequest;
import jirat.viriyataranon.coda.kv.router.core.NodeRegistry;
import jirat.viriyataranon.coda.kv.router.exception.UnknownNodeException;
import jirat.viriyataranon.coda.kv.router.model.NodeInfo;
import jirat.viriyataranon.coda.kv.router.model.RouterOperation;
import jirat.viriyataranon.coda.kv.router.model.RouterRequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/v1/kv")
@RequiredArgsConstructor
public class RouterController {

    private static final int LIST_FLUSH_BATCH = 64;
    private static final Set<String> FILTERED_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            ":status",
            ":method",
            ":path",
            ":scheme",
            ":authority"
    );

    private final NodeRegistry registry;
    private final RestClient restClient;

    @GetMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> get(
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context,
            @PathVariable String key,
            @RequestHeader HttpHeaders headers
    ) {
        context.setOperation(RouterOperation.GET);

        try {
            var target = registry.resolveNode(key);

            context.setKey(key);
            context.setTargetNodes(List.of(target));

            return restClient.get()
                    .uri(target.url() + "/v1/kv/{key}", key)
                    .headers(h -> generateRequestId(forwardHeaders(h, headers)))
                    .exchange(this::forwardResponse);
        } catch (UnknownNodeException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PutMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> put(
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context,
            @PathVariable String key,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request
    ) throws IOException {
        context.setOperation(RouterOperation.PUT);

        try {
            var target = registry.resolveNode(key);

            context.setKey(key);
            context.setTargetNodes(List.of(target));

            return restClient.put()
                    .uri(target.url() + "/v1/kv/{key}", key)
                    .headers(h -> generateRequestId(forwardHeaders(h, headers)))
                    .body(request.getInputStream().readAllBytes())
                    .exchange(this::forwardResponse);
        } catch (UnknownNodeException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PatchMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> patch(
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context,
            @PathVariable String key,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request
    ) throws IOException {
        context.setOperation(RouterOperation.PATCH);

        try {
            var target = registry.resolveNode(key);

            context.setKey(key);
            context.setTargetNodes(List.of(target));

            return restClient.patch()
                    .uri(target.url() + "/v1/kv/{key}", key)
                    .headers(h -> generateRequestId(forwardHeaders(h, headers)))
                    .body(request.getInputStream().readAllBytes())
                    .exchange(this::forwardResponse);
        } catch (UnknownNodeException e) {
            context.setException(e);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @GetMapping(produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> list(
            @RequestAttribute(RouterRequestContext.KEY) RouterRequestContext context,
            @RequestHeader HttpHeaders headers
    ) {
        context.setOperation(RouterOperation.LIST);

        var topology = registry.topology();
        var nodes = topology.nodeIdToInfo().values();
        if (nodes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        var targetNodes = new ArrayList<NodeInfo>();
        context.setTargetNodes(targetNodes);

        StreamingResponseBody body = output -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

                var futures = new ArrayList<CompletableFuture<Void>>();
                for (var node : nodes) {
                    targetNodes.add(node);
                    var future = CompletableFuture.runAsync(() -> {
                        try {
                            streamResponse(node, headers, output);
                        } catch (Exception e) {
                            log.atWarn()
                                    .addKeyValue("nodeId", node.nodeId())
                                    .setCause(e)
                                    .log("Partial stream failure");
                        }
                    }, executor);
                    futures.add(future);
                }

                futures.forEach(CompletableFuture::join);
            }

            output.flush();
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(body);
    }

    private void streamResponse(NodeInfo node, HttpHeaders headers, OutputStream output) {
        restClient.get()
                .uri(node.url() + "/v1/kv")
                .headers(h -> generateRequestId(forwardHeaders(h, headers)))
                .exchange((req, res) -> {
                    try (
                            InputStream in = res.getBody();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    ) {

                        var count = 0;
                        String line = reader.readLine();
                        while (line != null) {
                            synchronized (output) {
                                output.write(line.getBytes(StandardCharsets.UTF_8));
                                output.write('\n');

                                count++;
                                if (count % LIST_FLUSH_BATCH == 0) {
                                    output.flush();
                                }
                            }
                            line = reader.readLine();
                        }
                    }

                    return null;
                });
    }

    private HttpHeaders forwardHeaders(HttpHeaders outgoing, HttpHeaders incoming) {
        incoming.forEach((key, values) -> {
            if (FILTERED_HEADERS.contains(key.toLowerCase())) {
                return;
            }

            outgoing.put(key, values);
        });

        return outgoing;
    }

    private void generateRequestId(HttpHeaders headers) {
        headers.set("request-id", UUID.randomUUID().toString());
    }

    private ResponseEntity<byte[]> forwardResponse(HttpRequest req, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) throws IOException {
        var body = res.bodyTo(byte[].class);

        return ResponseEntity.status(res.getStatusCode())
                .headers(headers -> forwardHeaders(headers, res.getHeaders()))
                .body(body);
    }
}

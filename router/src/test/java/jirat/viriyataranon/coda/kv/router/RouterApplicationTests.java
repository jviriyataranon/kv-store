package jirat.viriyataranon.coda.kv.router;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.common.util.StringUtils;
import jirat.viriyataranon.coda.kv.router.core.NodeRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouterApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private RestTestClient rest;

    @Autowired
    private NodeRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    private static WireMockServer wireMock1;
    private static WireMockServer wireMock2;

    private static final String NODE1 = "node-1";
    private static final String NODE2 = "node-2";

    @BeforeAll
    static void startWireMocks() {
        wireMock1 = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock2 = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock1.start();
        wireMock2.start();
    }

    @AfterAll
    static void stopWireMocks() {
        wireMock1.stop();
        wireMock2.stop();
    }

    @BeforeEach
    void resetWireMocks() {
        wireMock1.resetAll();
        wireMock2.resetAll();
    }

    @AfterEach
    void deregisterAllNodes() {
        new ArrayList<>(registry.topology().nodeIdToInfo().keySet())
                .forEach(this::deregisterNode);
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    @Test
    void register_newNode_returns200WithTopologyContainingNode() {
        var body = registerNode(NODE1, nodeUrl(wireMock1.port())).getResponseBody();

        assertThat(body.get("version").asLong()).isGreaterThan(0);

        var nodeIds = new ArrayList<String>();
        body.get("bucketToNode").forEach(v -> nodeIds.add(v.asString()));
        assertThat(nodeIds).contains(NODE1);
    }

    @Test
    void register_sameNode_doesNotIncrementTopologyVersion() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        var versionBefore = registry.topology().version();

        registerNode(NODE1, nodeUrl(wireMock1.port())); // refresh

        assertThat(registry.topology().version()).isEqualTo(versionBefore);
    }

    @Test
    void deregister_existingNode_returns204() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));

        assertThat(deregisterNode(NODE1).getStatus()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deregister_unknownNode_returns404() {
        assertThat(deregisterNode("ghost-node").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── Routing — GET ────────────────────────────────────────────────────────

    @Test
    void get_forwardsToNode_returnsNodeResponse() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(get(urlEqualTo("/v1/kv/read-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"read-key\",\"value\":42,\"version\":1}")));

        var result = routerGet("/kv/read-key");
        var body = result.getResponseBody();

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(body.get("key").asString()).isEqualTo("read-key");
        assertThat(body.get("value").asInt()).isEqualTo(42);
        assertThat(body.get("version").asLong()).isEqualTo(1);

        wireMock1.verify(getRequestedFor(urlEqualTo("/v1/kv/read-key")));
    }

    @Test
    void get_nodeReturns404_routerForwards404() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(get(urlEqualTo("/v1/kv/missing-key"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(routerGet("/kv/missing-key").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_correlationIdHeaderForwardedToNode() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(get(urlEqualTo("/v1/kv/header-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"header-key\",\"value\":1,\"version\":1}")));

        rest.get()
                .uri(routerUrl("/kv/header-key"))
                .header("correlation-id", "test-corr-123")
                .exchange()
                .returnResult(JsonNode.class);

        wireMock1.verify(getRequestedFor(urlEqualTo("/v1/kv/header-key"))
                .withHeader("correlation-id", equalTo("test-corr-123")));
    }

    @Test
    void get_noNodes_returns503() {
        assertThat(routerGet("/kv/any-key").getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── Routing — PUT ────────────────────────────────────────────────────────

    @Test
    void put_forwardsBodyToNode_returnsNodeResponse() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(put(urlEqualTo("/v1/kv/write-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"write-key\",\"value\":42,\"version\":1}")));

        assertThat(routerPut("/kv/write-key", "42").getStatus()).isEqualTo(HttpStatus.OK);

        wireMock1.verify(putRequestedFor(urlEqualTo("/v1/kv/write-key"))
                .withRequestBody(equalTo("42")));
    }

    @Test
    void put_ifVersionHeaderForwardedToNode() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(put(urlEqualTo("/v1/kv/ver-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"ver-key\",\"value\":1,\"version\":2}")));

        routerPut("/kv/ver-key", "1", "99");

        wireMock1.verify(putRequestedFor(urlEqualTo("/v1/kv/ver-key"))
                .withHeader("ifVersion", equalTo("99")));
    }

    @Test
    void put_nodeReturns409_routerForwards409() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(put(urlEqualTo("/v1/kv/conflict-key"))
                .willReturn(aResponse().withStatus(409)));

        assertThat(routerPut("/kv/conflict-key", "42").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void put_noNodes_returns503() {
        assertThat(routerPut("/kv/any-key", "1").getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── Routing — PATCH ──────────────────────────────────────────────────────

    @Test
    void patch_forwardsBodyToNode_returnsNodeResponse() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(patch(urlEqualTo("/v1/kv/patch-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"key\":\"patch-key\",\"value\":{\"a\":1},\"version\":1}")));

        assertThat(routerPatch("/kv/patch-key", "{\"a\":1}").getStatus()).isEqualTo(HttpStatus.OK);

        wireMock1.verify(patchRequestedFor(urlEqualTo("/v1/kv/patch-key"))
                .withRequestBody(equalTo("{\"a\":1}")));
    }

    @Test
    void patch_nodeReturns409_routerForwards409() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(patch(urlEqualTo("/v1/kv/conflict-patch-key"))
                .willReturn(aResponse().withStatus(409)));

        assertThat(routerPatch("/kv/conflict-patch-key", "{}").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void patch_noNodes_returns503() {
        assertThat(routerPatch("/kv/any-key", "{}").getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    @Test
    void list_returnsNdjsonContentType() {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(get(urlEqualTo("/v1/kv"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("")));

        var result = rest.get().uri(routerUrl("/kv")).exchange().returnResult(byte[].class);

        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_NDJSON);
    }

    @Test
    void list_aggregatesNdjsonFromNode() throws Exception {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        wireMock1.stubFor(get(urlEqualTo("/v1/kv"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("{\"key\":\"list-a\",\"node\":\"node-1\"}\n{\"key\":\"list-b\",\"node\":\"node-1\"}\n")));

        var keys = listNdjson().stream().map(n -> n.get("key").asString()).toList();

        assertThat(keys).containsExactlyInAnyOrder("list-a", "list-b");
    }

    @Test
    void list_multipleNodes_aggregatesFromAll() throws Exception {
        registerNode(NODE1, nodeUrl(wireMock1.port()));
        registerNode(NODE2, nodeUrl(wireMock2.port()));

        wireMock1.stubFor(get(urlEqualTo("/v1/kv"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("{\"key\":\"key-from-node1\",\"node\":\"node-1\"}\n")));

        wireMock2.stubFor(get(urlEqualTo("/v1/kv"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody("{\"key\":\"key-from-node2\",\"node\":\"node-2\"}\n")));

        var keys = listNdjson().stream().map(n -> n.get("key").asString()).toList();
        assertThat(keys).containsExactlyInAnyOrder("key-from-node1", "key-from-node2");

        wireMock1.verify(getRequestedFor(urlEqualTo("/v1/kv")));
        wireMock2.verify(getRequestedFor(urlEqualTo("/v1/kv")));
    }

    @Test
    void list_noNodes_returns503() {
        var result = rest.get().uri(routerUrl("/kv")).exchange().returnResult(byte[].class);
        assertThat(result.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String nodeUrl(int nodePort) {
        return "http://localhost:" + nodePort;
    }

    private String routerUrl(String path) {
        return "http://localhost:" + port + "/v1" + path;
    }

    private EntityExchangeResult<JsonNode> registerNode(String id, String url) {
        return rest.put()
                .uri("http://localhost:" + port + "/internal/nodes/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"url\":\"" + url + "\"}")
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<byte[]> deregisterNode(String id) {
        return rest.delete()
                .uri("http://localhost:" + port + "/internal/nodes/" + id)
                .exchange()
                .returnResult(byte[].class);
    }

    private EntityExchangeResult<JsonNode> routerGet(String path) {
        return rest.get()
                .uri(routerUrl(path))
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> routerPut(String path, String json) {
        return rest.put()
                .uri(routerUrl(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> routerPut(String path, String json, String ifVersion) {
        return rest.put()
                .uri(routerUrl(path))
                .contentType(MediaType.APPLICATION_JSON)
                .header("ifVersion", ifVersion)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> routerPatch(String path, String json) {
        return rest.patch()
                .uri(routerUrl(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private List<JsonNode> listNdjson() throws Exception {
        var ndjson = rest.get()
                .uri(routerUrl("/kv"))
                .exchange()
                .returnResult(String.class)
                .getResponseBody();

        if (StringUtils.isBlank(ndjson)) return List.of();

        return ndjson.lines()
                .filter(StringUtils::isNotBlank)
                .map(l -> {
                    try {
                        return mapper.readTree(l);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }
}

package jirat.viriyataranon.coda.kv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KvApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private RestTestClient rest;

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── GET /kv/{key} ────────────────────────────────────────────────────────

    @Test
    void get_missingKey_returns404() {
        assertThat(get("/kv/no-such-key").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_existingKey_returnsKeyValueVersion() throws Exception {
        put("/kv/get-basic", "{\"x\":1}");

        JsonNode body = get("/kv/get-basic").getResponseBody();
        assertThat(body.get("key").asText()).isEqualTo("get-basic");
        assertThat(body.get("value").get("x").asInt()).isEqualTo(1);
        assertThat(body.get("version").asLong()).isEqualTo(1);
    }

    // ─── PUT /kv/{key} ────────────────────────────────────────────────────────

    @Test
    void put_newKey_createsWithVersion1() throws Exception {
        put("/kv/put-new", "\"hello\"");
        assertThat(get("/kv/put-new").getResponseBody().get("version").asLong()).isEqualTo(1);
    }

    @Test
    void put_existingKey_replacesValueAndIncrementsVersion() throws Exception {
        put("/kv/put-replace", "\"first\"");
        put("/kv/put-replace", "\"second\"");

        JsonNode body = get("/kv/put-replace").getResponseBody();
        assertThat(body.get("value").asText()).isEqualTo("second");
        assertThat(body.get("version").asLong()).isEqualTo(2);
    }

    @Test
    void put_repeatedWrites_versionIncrementsMonotonically() throws Exception {
        for (int i = 1; i <= 5; i++) {
            put("/kv/put-mono", String.valueOf(i));
            assertThat(get("/kv/put-mono").getResponseBody().get("version").asLong()).isEqualTo(i);
        }
    }

    @Test
    void put_noIfVersion_alwaysSucceeds() {
        put("/kv/put-nover", "1");
        assertThat(put("/kv/put-nover", "2").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void put_ifVersion_matchingVersion_succeeds() throws Exception {
        put("/kv/put-ver-ok", "\"v1\"");
        assertThat(put("/kv/put-ver-ok", "1", "\"v2\"").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(get("/kv/put-ver-ok").getResponseBody().get("version").asLong()).isEqualTo(2);
    }

    @Test
    void put_ifVersion_mismatch_returns409() {
        put("/kv/put-ver-bad", "\"v1\"");
        assertThat(put("/kv/put-ver-bad", "99", "\"v2\"").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void put_ifVersionOnMissingKey_returns409() {
        assertThat(put("/kv/put-missing-ver", "1", "\"v1\"").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void put_failedIfVersion_doesNotChangeStoredVersion() throws Exception {
        put("/kv/put-ver-stable", "\"v1\"");
        put("/kv/put-ver-stable", "99", "\"v2\"");  // ignored — wrong version
        assertThat(get("/kv/put-ver-stable").getResponseBody().get("version").asLong()).isEqualTo(1);
    }

    @Test
    void put_arbitraryJsonTypes_allAccepted() {
        assertThat(put("/kv/put-str", "\"hello\"").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(put("/kv/put-num", "42").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(put("/kv/put-arr", "[1,2,3]").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(put("/kv/put-nul", "null").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(put("/kv/put-bool", "true").getStatus()).isEqualTo(HttpStatus.OK);
    }

    // ─── PATCH /kv/{key} ──────────────────────────────────────────────────────

    @Test
    void patch_missingKey_upserts() throws Exception {
        patch("/kv/patch-upsert", "{\"a\":1}");

        JsonNode body = get("/kv/patch-upsert").getResponseBody();
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("value").get("a").asInt()).isEqualTo(1);
    }

    @Test
    void patch_bothObjects_shallowMerges() throws Exception {
        put("/kv/patch-merge", "{\"a\":1,\"b\":2}");
        patch("/kv/patch-merge", "{\"b\":99,\"c\":3}");

        JsonNode val = get("/kv/patch-merge").getResponseBody().get("value");
        assertThat(val.get("a").asInt()).isEqualTo(1);   // unchanged
        assertThat(val.get("b").asInt()).isEqualTo(99);  // overwritten
        assertThat(val.get("c").asInt()).isEqualTo(3);   // new field
    }

    @Test
    void patch_shallowMergeNotDeep_nestedObjectIsReplaced() throws Exception {
        put("/kv/patch-shallow", "{\"nested\":{\"a\":1,\"b\":2}}");
        patch("/kv/patch-shallow", "{\"nested\":{\"b\":99}}");

        // Shallow: entire "nested" key overwritten, not recursively merged
        JsonNode nested = get("/kv/patch-shallow").getResponseBody().get("value").get("nested");
        assertThat(nested.has("a")).isFalse();
        assertThat(nested.get("b").asInt()).isEqualTo(99);
    }

    @Test
    void patch_existingObjectNewNonObject_replaces() throws Exception {
        put("/kv/patch-obj-str", "{\"a\":1}");
        patch("/kv/patch-obj-str", "\"scalar\"");
        assertThat(get("/kv/patch-obj-str").getResponseBody().get("value").asText()).isEqualTo("scalar");
    }

    @Test
    void patch_existingNonObjectNewObject_replaces() throws Exception {
        put("/kv/patch-str-obj", "42");
        patch("/kv/patch-str-obj", "{\"a\":1}");
        assertThat(get("/kv/patch-str-obj").getResponseBody().get("value").get("a").asInt()).isEqualTo(1);
    }

    @Test
    void patch_bothNonObjects_replaces() throws Exception {
        put("/kv/patch-scalars", "1");
        patch("/kv/patch-scalars", "99");
        assertThat(get("/kv/patch-scalars").getResponseBody().get("value").asInt()).isEqualTo(99);
    }

    @Test
    void patch_versionIncrementsOnEachWrite() throws Exception {
        patch("/kv/patch-ver", "{\"a\":1}");  // v1
        patch("/kv/patch-ver", "{\"b\":2}");  // v2
        assertThat(get("/kv/patch-ver").getResponseBody().get("version").asLong()).isEqualTo(2);
    }

    @Test
    void patch_ifVersion_matchingVersion_succeeds() throws Exception {
        put("/kv/patch-ver-ok", "{\"a\":1}");
        assertThat(patch("/kv/patch-ver-ok", "1", "{\"b\":2}").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(get("/kv/patch-ver-ok").getResponseBody().get("version").asLong()).isEqualTo(2);
    }

    @Test
    void patch_ifVersion_mismatch_returns409() {
        put("/kv/patch-ver-bad", "{\"a\":1}");
        assertThat(patch("/kv/patch-ver-bad", "99", "{\"b\":2}").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void patch_ifVersionOnMissingKey_returns409() {
        assertThat(patch("/kv/patch-missing-ver", "1", "{\"a\":1}").getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void patch_failedIfVersion_doesNotChangeStoredValue() throws Exception {
        put("/kv/patch-ver-stable", "{\"a\":1}");
        patch("/kv/patch-ver-stable", "99", "{\"a\":999}");  // ignored
        assertThat(get("/kv/patch-ver-stable").getResponseBody().get("value").get("a").asInt()).isEqualTo(1);
    }

    // ─── Isolation: different keys are independent ─────────────────────────────

    @Test
    void isolation_opsOnDifferentKeys_doNotInterfere() throws Exception {
        put("/kv/iso-a", "\"alpha\"");
        put("/kv/iso-b", "\"beta\"");
        put("/kv/iso-b", "\"beta2\"");
        put("/kv/iso-b", "\"beta3\"");

        // iso-a should still be at version 1, iso-b at version 3
        assertThat(get("/kv/iso-a").getResponseBody().get("version").asLong()).isEqualTo(1);
        assertThat(get("/kv/iso-b").getResponseBody().get("version").asLong()).isEqualTo(3);
        assertThat(get("/kv/iso-a").getResponseBody().get("value").asText()).isEqualTo("alpha");
    }

    // ─── Concurrency ──────────────────────────────────────────────────────────

    @Test
    void concurrency_threeClientsHundredIncrementsEach_finalValueIs300() throws Exception {
        String key = "counter";
        put("/kv/" + key, "0");

        int threads = 3, increments = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                int done = 0;
                while (done < increments) {
                    JsonNode body = get("/kv/" + key).getResponseBody();
                    int cur = body.get("value").asInt();
                    long ver = body.get("version").asLong();
                    var res = put("/kv/" + key, String.valueOf(ver), String.valueOf(cur + 1));
                    if (res.getStatus().equals(HttpStatus.OK)) done++;
                    // 409 = CAS failure → retry without counting
                }
                return null;
            }));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (Future<Void> f : futures) f.get();  // rethrow any thread exceptions

        assertThat(get("/kv/" + key).getResponseBody().get("value").asInt()).isEqualTo(threads * increments);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + "/v1" + path;
    }

    private EntityExchangeResult<JsonNode> get(String path) {
        return rest.get()
                .uri(url(path))
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> put(String path, String json) {
        return rest.put()
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> put(String path, String version, String json) {
        return rest.put()
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .header("ifVersion", version)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> patch(String path, String json) {
        return rest.patch()
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }

    private EntityExchangeResult<JsonNode> patch(String path, String version, String json) {
        return rest.patch()
                .uri(url(path))
                .contentType(MediaType.APPLICATION_JSON)
                .header("ifVersion", version)
                .body(json)
                .exchange()
                .returnResult(JsonNode.class);
    }
}

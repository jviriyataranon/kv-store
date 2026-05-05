# KV Store

An in-memory key-value store built as a take-home assignment. Two Spring Boot services — a **node** (single-node KV store) and a **router** (consistent-hash proxy) — compose into a horizontally scalable cluster.

## Architecture

```
Client → Router :6999 ─┬→ Node 1 :7001
                        ├→ Node 2 :7002
                        └→ Node 3 :7003
```

The router distributes keys deterministically using **JumpBack Anchor Hashing** (hash4j). Each node self-registers with the router on startup and sends a heartbeat every 15 s. The router evicts nodes that miss the threshold and fans out `GET /v1/kv` list requests to all live nodes concurrently.

## Modules

| Module | Port | Description |
|---|---|---|
| `node/` | 7001 | In-memory KV store; handles GET / PUT / PATCH per-key with per-key atomicity and versioning |
| `router/` | 6999 | Stateless proxy; consistent-hash routing, node registration, scatter-gather list |

## API

All endpoints are exposed on the **router** (or directly on a node for single-node mode).

### Key operations

| Method | Path | Description |
|---|---|---|
| `GET` | `/v1/kv/{key}` | Read a key. Returns `{ key, value, version }` or 404 |
| `PUT` | `/v1/kv/{key}` | Full replace. Optional `ifVersion` header for optimistic locking (409 on mismatch) |
| `PATCH` | `/v1/kv/{key}` | Upsert; shallow-merges if both sides are JSON objects, otherwise replaces. Same `ifVersion` guard |
| `GET` | `/v1/kv` | List all keys. Returns NDJSON — one `{ key, node }` per line, aggregated from all nodes |

### Example

```bash
# Write
curl -X PUT http://localhost:6999/v1/kv/counter \
  -H 'Content-Type: application/json' \
  -d '1'

# Read
curl http://localhost:6999/v1/kv/counter
# {"key":"counter","value":1,"version":1}

# Conditional update (optimistic locking)
curl -X PUT http://localhost:6999/v1/kv/counter \
  -H 'Content-Type: application/json' \
  -H 'ifVersion: 1' \
  -d '2'

# Shallow merge
curl -X PATCH http://localhost:6999/v1/kv/config \
  -H 'Content-Type: application/json' \
  -d '{"timeout": 30}'

# List all keys (NDJSON)
curl http://localhost:6999/v1/kv
# {"key":"counter","node":"node1"}
# {"key":"config","node":"node2"}
```

## Running

### Multi-node cluster (1 router + 3 nodes)

```bash
make up      # build images and start via docker-compose
make down    # stop and remove containers
```

### Single-node dev mode

```bash
make run     # build and start a single node on :7001
make stop    # stop it
```

## Concurrency model

Two `DataStore` implementations are available, selectable via `kv.data-store-type` in `application.properties`:

| Implementation | Mechanism | Default |
|---|---|---|
| `optimistic` | `AtomicReference` + CAS retry loop | yes |
| `locking` | `ConcurrentHashMap.compute()` | no |

Both guarantee per-key atomicity. See [performance results](#performance) for the tradeoffs.

## Key design decisions

**Node registration & heartbeat** — nodes push `PUT /internal/nodes/{id}` to the router every heartbeat interval. The router responds with the current topology so nodes can clean up keys they no longer own after a topology change.

**Topology cleanup** — when a node receives an updated topology version it deletes any locally stored keys whose consistent-hash bucket now belongs to a different node, keeping memory usage bounded as the cluster scales.

**List scatter/gather** — the router fans out `GET /v1/kv` to all live nodes using virtual-thread `CompletableFuture`s and streams NDJSON lines to the client as nodes respond, with per-batch flushing to avoid buffering the full result set.

**Connection pooling** — both services use Apache HttpClient 5 with a `PoolingHttpClientConnectionManager` (router: 300 total / 100 per route; node: 20 / 5).

## Performance

Benchmarked with k6 across 12 scenarios (4 workloads × 3 contention levels). See [`performance/performance-result.md`](performance/performance-result.md) for full results.

**Summary — single node, ~4,300 req/s sustained throughput at sub-millisecond median latency:**

| Impl | Avg throughput | Mean latency | Worst-case max |
|---|---|---|---|
| Optimistic | ~4,309 req/s | flat ~1 ms | 1,000 ms (read-heavy c0.9) |
| Locking | ~4,006 req/s | rises at c0.5 | 327 ms (balanced c0.5) |

Optimistic wins on average throughput (+7.5%) and mean latency stability. Locking wins on worst-case tail latency — the CAS spin loop can starve readers under very high single-key write contention.

### Running the benchmark matrix

Requires [k6](https://k6.io/docs/get-started/installation/) and a running node or cluster.

```bash
# Against a single node (default)
./performance/run-matrix.sh

# Against the router / cluster
BASE_URL=http://localhost:6999 MAX_VUS=30 ./performance/run-matrix.sh
```

Each run is 5 minutes; 12 runs = ~60 min total. Results are written to `performance/results/<timestamp>/`.

## Testing

```bash
# Node tests (unit + integration)
cd node && ../mvnw test

# Router tests
cd router && ../mvnw test
```

Key test cases:

- **Concurrent counter** — 3 threads × 100 increments to the same key → asserts final value is 300
- **ifVersion CAS** — version mismatch returns 409; matching version succeeds
- **PATCH merge** — shallow merge when both sides are objects; replace otherwise
- **TopologyCleanup** — keys owned by another bucket are deleted; keys owned by this node are kept
- **List** — NDJSON content type; all written keys appear; each entry contains `key` and `node` fields

## Tech stack

- Java 21 + Spring Boot (Spring MVC, virtual threads)
- Jackson (arbitrary JSON values, `JsonNode`)
- hash4j JumpBack Anchor Hashing (consistent hashing)
- Apache HttpClient 5 (connection pooling)
- Docker + Docker Compose
- k6 (load testing)

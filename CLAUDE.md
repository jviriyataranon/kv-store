# KV Store — Take-Home Assignment

## Stack
Java + Spring Boot (Spring MVC, Jackson)

## Assignment Parts

### Part 1 — Single-node in-memory KV store
HTTP API on port 7001. Storage is in-memory only.

**Endpoints:**
- `GET /v1/kv/{key}` → `{ key, value, version }` or 404
- `PUT /v1/kv/{key}` — full replace; optional `ifVersion=<n>` as request header (409 on mismatch version)
- `PATCH /v1/kv/{key}` — upsert/shallow-merge if both sides are JSON objects, otherwise replace; same `ifVersion` header guard

**Key design constraints:**
- Per-key locking: concurrent ops on the same key are serialized; different keys proceed concurrently
- Version starts at 1, increments on every write; no version history stored
- Use `JsonNode` for values to handle arbitrary JSON and shallow-merge in PATCH

**Concurrency approach:**
- `ConcurrentHashMap<String, ReentrantLock>` for per-key locks
- `ConcurrentHashMap<String, KvEntry>` for data
- Alternatively: `ConcurrentHashMap.compute()` for atomic per-key ops without explicit locks

**Required test:** 3 concurrent clients each doing 100 counter increments to the same key → assert final value is 300.

### Part 2 — Multi-node scale-out
Multiple Part 1 instances (e.g., ports 7001, 7002, 7003) behind a stateless router on port 7000.

- Router hashes key to node: `Math.abs(key.hashCode()) % nodeCount`
- Router proxies requests via `RestTemplate` or `WebClient`
- `GET /v1/kv` — router fans out to all nodes, merges NDJSON (`{ key, node }` per line)
- All Part 1 semantics preserved across nodes: per-key atomicity, versioning, and full API behavior
- **Testing consideration:** verify keys spread across nodes, list endpoint aggregates from all nodes, and conditional updates work end-to-end through the router

### Part 3 — Design doc only (no implementation)
Pick one roadmap item (e.g., persistence, replication, TTL, cross-key transactions). Prepare a diagram + notes on the change, benefits, tradeoffs, and rough implementation sequence.

## Role of Claude
Help with design decisions, tradeoffs, and questions. The user is doing the implementation.

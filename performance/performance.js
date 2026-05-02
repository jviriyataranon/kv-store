/**
 * k6 performance test for the KV store.
 *
 * Run performance/warmup.js FIRST to seed keys and warm the JVM.
 *
 * Usage:
 *   k6 run performance/performance.js
 *   k6 run -e WORKLOAD=read-heavy -e CONTENTION=0.8 performance/performance.js
 *
 * ENV vars (all optional):
 *   BASE_URL      Target host (default: http://localhost:7001)
 *   WORKLOAD      balanced | read-heavy | write-heavy | update-heavy  (default: balanced)
 *   CONTENTION    0.0–1.0  fraction of requests going to the hot-key pool  (default: 0.2)
 *   HOT_KEYS      Size of the hot-key pool                                 (default: 10)
 *   COLD_KEYS     Size of the cold-key pool                                (default: 10000)
 *   THINK_MS      Think time in milliseconds between iterations            (default: 0)
 *   MAX_VUS       Peak VU count for the ramping stage                      (default: 200)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/latest/dist/bundle.js'

// 404 is an expected response for GET on a missing key.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 404));

// ── Configuration ─────────────────────────────────────────────────────────────

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:7001';
const WORKLOAD   = __ENV.WORKLOAD   || 'balanced';
const RESULT_PATH   = __ENV.RESULT_PATH   || 'results';
const CONTENTION = parseFloat(__ENV.CONTENTION || '0.2');
const HOT_KEYS   = parseInt(__ENV.HOT_KEYS     || '1');
const COLD_KEYS  = parseInt(__ENV.COLD_KEYS    || '10000');
const THINK_MS   = parseFloat(__ENV.THINK_MS   || '5');
const MAX_VUS    = parseInt(__ENV.MAX_VUS       || '30');

// Workload profiles: [readFrac, writeFrac, updateFrac]
const PROFILES = {
  'balanced':      [0.40, 0.30, 0.30],
  'read-heavy':    [0.80, 0.10, 0.10],
  'write-heavy':   [0.10, 0.70, 0.20],
  'update-heavy':  [0.10, 0.20, 0.70],
};

const [READ_F, WRITE_F] = PROFILES[WORKLOAD];
const WRITE_CUTOFF = READ_F + WRITE_F;

// ── Custom metrics ────────────────────────────────────────────────────────────

// Separate latency trends per operation so you can compare them independently.
const readLatency   = new Trend('kv_read_ms',   true);   // true = emit p50/p90/p95/p99
const writeLatency  = new Trend('kv_write_ms',  true);
const updateLatency = new Trend('kv_update_ms', true);

// Rate metrics: add(true) = "bad event occurred", add(false) = "good event".
const misses = new Rate('kv_miss_rate');    // 404s among GET requests
const errors = new Rate('kv_error_rate');   // unexpected non-2xx/404 responses

// ── k6 options ────────────────────────────────────────────────────────────────

export const options = {
  scenarios: {
    // Assumes warmup.js has already run: no warmup stage here.
    // Total duration: 60 s ramp + 180 s hold + 60 s ramp-down = 5 min.
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s',  target: MAX_VUS },  // ramp to peak
        { duration: '240s', target: MAX_VUS },  // hold at peak — steady-state measurement
        { duration: '30s',  target: 0 },        // ramp-down
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    kv_read_ms:       ['p(95)<100', 'p(99)<500'],
    kv_write_ms:      ['p(95)<200', 'p(99)<1000'],
    kv_update_ms:     ['p(95)<200', 'p(99)<1000'],
    kv_error_rate:    ['rate<0.01'],
    http_req_failed:  ['rate<0.01'],
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────

function randInt(max) { return Math.floor(Math.random() * max); }

function pickKey() {
  // High CONTENTION → many VUs fighting over the same small hot-key pool.
  // Low CONTENTION  → each request hits a mostly-unique key (throughput-bound).
  return Math.random() < CONTENTION
    ? `hot:${randInt(HOT_KEYS)}`
    : `cold:${randInt(COLD_KEYS)}`;
}

const BASE_HEADERS = { 'Content-Type': 'application/json' };

function doRead(key) {
  const res = http.get(`${BASE_URL}/v1/kv/${key}`, { tags: { name: 'GET /v1/kv/:key' } });
  readLatency.add(res.timings.duration);

  if (res.status === 200) {
    misses.add(false);
    errors.add(false);
  } else if (res.status === 404) {
    misses.add(true);
    errors.add(false);
  } else {
    errors.add(true);
  }

  check(res, { 'GET → 200|404': (r) => r.status === 200 || r.status === 404 });
}

function doWrite(key) {
  const body = JSON.stringify({ v: randInt(1000000) });
  const res = http.put(`${BASE_URL}/v1/kv/${key}`, body, { headers: BASE_HEADERS, tags: { name: 'PUT /v1/kv/:key' } });
  writeLatency.add(res.timings.duration);

  const ok = res.status >= 200 && res.status < 300;
  errors.add(!ok);

  check(res, { 'PUT → 2xx': (_) => ok });
}

function doUpdate(key) {
  const body = JSON.stringify({ v: randInt(1000000) });
  const res = http.patch(`${BASE_URL}/v1/kv/${key}`, body, { headers: BASE_HEADERS, tags: { name: 'PATCH /v1/kv/:key' } });
  updateLatency.add(res.timings.duration);

  const ok = res.status >= 200 && res.status < 300;
  errors.add(!ok);

  check(res, { 'PATCH → 2xx': (_) => ok });
}

// ── Summary export ────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const ts = new Date().toISOString().slice(0, 19).replace(/:/g, '-');
  const base = `${RESULT_PATH}/kv_${WORKLOAD}_c${CONTENTION}_${ts}`;
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    [`${base}.json`]: JSON.stringify(data, null, 2),
    [`${base}.html`]: htmlReport(data),
  };
}

// ── Setup ─────────────────────────────────────────────────────────────────────

export function setup() {
  console.log(`[perf] url=${BASE_URL} workload=${WORKLOAD} contention=${CONTENTION} hot=${HOT_KEYS} cold=${COLD_KEYS} vus=${MAX_VUS} think=${THINK_MS}ms`);
}

// ── Main iteration ────────────────────────────────────────────────────────────

export default function () {
  const key = pickKey();
  const r = Math.random();

  if (r < READ_F) {
    doRead(key);
  } else if (r < WRITE_CUTOFF) {
    doWrite(key);
  } else {
    doUpdate(key);
  }

  if (THINK_MS > 0) sleep(THINK_MS / 1000);
}

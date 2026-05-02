/**
 * k6 warmup script — run this ONCE before performance.js.
 *
 * Does two things:
 *   1. Seeds all hot and cold keys so performance.js starts with no 404 misses.
 *   2. Runs a 60 s burst at 10% of MAX_VUS so the JVM's JIT compiler is hot
 *      before any measurement begins.
 *
 * Usage:
 *   k6 run performance/warmup.js
 *   k6 run -e BASE_URL=http://localhost:7001 -e COLD_KEYS=1000 performance/warmup.js
 *
 * ENV vars (must match performance.js if overridden):
 *   BASE_URL      Target host            (default: http://localhost:7001)
 *   HOT_KEYS      Size of hot-key pool   (default: 10)
 *   COLD_KEYS     Size of cold-key pool  (default: 10000)
 *   MAX_VUS       Used to size the JIT warmup VU count (default: 200)
 */

import http from 'k6/http';

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:7001';
const HOT_KEYS  = parseInt(__ENV.HOT_KEYS  || '10');
const COLD_KEYS = parseInt(__ENV.COLD_KEYS || '10000');
const MAX_VUS   = parseInt(__ENV.MAX_VUS   || '100');

const BASE_HEADERS = { 'Content-Type': 'application/json' };

function randInt(max) { return Math.floor(Math.random() * max); }

export const options = {
  scenarios: {
    jit_warmup: {
      executor: 'constant-vus',
      // 10% of MAX_VUS is enough to trigger JIT compilation on all hot paths.
      vus: Math.max(5, Math.floor(MAX_VUS * 0.10)),
      duration: '60s',
    },
  },
  // No thresholds — warmup output is not a measurement.
};

export function setup() {
  console.log(`[warmup] seeding ${HOT_KEYS} hot keys and ${COLD_KEYS} cold keys...`);

  for (let i = 0; i < HOT_KEYS; i++) {
    const res = http.put(
      `${BASE_URL}/v1/kv/hot:${i}`,
      JSON.stringify({ v: 0 }),
      { headers: BASE_HEADERS },
    );
    if (res.status < 200 || res.status >= 300) {
      throw new Error(`setup: failed seeding hot:${i} — HTTP ${res.status}: ${res.body}`);
    }
  }

  for (let i = 0; i < COLD_KEYS; i++) {
    const res = http.put(
      `${BASE_URL}/v1/kv/cold:${i}`,
      JSON.stringify({ v: 0 }),
      { headers: BASE_HEADERS },
    );
    if (res.status < 200 || res.status >= 300) {
      throw new Error(`setup: failed seeding cold:${i} — HTTP ${res.status}: ${res.body}`);
    }
  }

  console.log('[warmup] seeding done — running 60 s JIT warmup burst...');
}

export default function () {
  const key = Math.random() < 0.2
    ? `hot:${randInt(HOT_KEYS)}`
    : `cold:${randInt(COLD_KEYS)}`;

  const r = Math.random();
  if (r < 0.40) {
    http.get(`${BASE_URL}/v1/kv/${key}`, { tags: { name: 'GET /v1/kv/:key' } });
  } else if (r < 0.70) {
    http.put(`${BASE_URL}/v1/kv/${key}`, JSON.stringify({ v: randInt(1000000) }), { headers: BASE_HEADERS, tags: { name: 'PUT /v1/kv/:key' } });
  } else {
    http.patch(`${BASE_URL}/v1/kv/${key}`, JSON.stringify({ v: randInt(1000000) }), { headers: BASE_HEADERS, tags: { name: 'PATCH /v1/kv/:key' } });
  }
}

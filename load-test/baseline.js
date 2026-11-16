// Marketplace single-replica read-surface baseline (roadmap A5 — "اختبار حمل
// أساس لسقف النسخة الواحدة"). Official k6 patterns:
//   - open-model load (constant-arrival-rate executor — "arrivals start at a
//     fixed rate regardless of how long each iteration takes", k6 docs
//     "Open and closed models"); the rate is the independent variable and
//     the ceiling is the highest rate where every threshold still holds.
//   - thresholds as the pass/fail gate (k6 docs "Thresholds"): error rate
//     under 1% and p95 under the budget.
//   - only HTTP 200 counts as success (http.expectedStatuses — the official
//     recipe for customizing the http_req_failed metric).
//
// The k6 BINARY is external to this repository (roadmap: "أداة خارج
// المستودع") — downloaded from the official grafana/k6 releases, never a
// Maven dependency. This script is the reproducible measurement harness.
//
// Run (stepped-rate ceiling method — see load-test/README.md):
//   k6 run -e RATE=200 -e DURATION=90s load-test/baseline.js
//
// Environment:
//   BASE_URL   target origin (default http://localhost:8080)
//   RATE       arrivals per second for the read-surface scenario (default 50)
//   DURATION   per-run duration (default 90s — long enough for the periodic
//              paths to settle, short enough for a rate ladder)
//   BUDGET_MS  latency budget for the p95 threshold (default 500)
//
// Traffic mix (steady-state production shape): 70% browse / 30% search over
// a small hot working set (3 browse pages × 4 search terms — every key is a
// cache HIT after the first iteration, mirroring hot pages under real
// traffic; the cold-miss stampede behavior is a separate measurement behind
// the A1 observability gate). The platform probe (liveness) runs at a
// constant 2 rps alongside — production probes keep firing under load.

import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '50', 10);
const DURATION = __ENV.DURATION || '90s';
const BUDGET_MS = parseInt(__ENV.BUDGET_MS || '500', 10);

// Only HTTP 200 is success: 4xx/5xx/timeouts all count into http_req_failed
// (official recipe — k6 docs "Tracking failures").
http.setResponseCallback(http.expectedStatuses(200));

export const options = {
  discardResponseBodies: true, // perf: bodies are not part of the measurement
  scenarios: {
    read_surface: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Open model: VUs are provisioned, not the control variable. A drop
      // in sustained iterations (dropped iterations in the summary) means
      // the target arrival rate outran the system — a knee signal.
      preAllocatedVUs: 100,
      maxVUs: 2000,
      exec: 'default',
    },
    platform_probe: {
      // The deploy platform keeps probing while traffic flows — keep that
      // background load present so the measurement is production-shaped.
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 2,
      exec: 'probe',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    [`http_req_duration{endpoint:browse}`]: [`p(95)<${BUDGET_MS}`],
    [`http_req_duration{endpoint:search}`]: [`p(95)<${BUDGET_MS}`],
    [`http_req_duration{endpoint:probe}`]: [`p(95)<${BUDGET_MS}`],
  },
};

const SEARCH_TERMS = ['garden', 'view', 'loft', 'house'];

export default function () {
  if (Math.random() < 0.7) {
    const page = Math.floor(Math.random() * 3); // hot pages 0..2
    http.get(`${BASE_URL}/api/v1/listings?page=${page}&size=20`, {
      tags: { endpoint: 'browse' },
    });
  } else {
    const q = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    http.get(`${BASE_URL}/api/v1/search?q=${q}`, {
      tags: { endpoint: 'search' },
    });
  }
}

export function probe() {
  http.get(`${BASE_URL}/actuator/health/liveness`, {
    tags: { endpoint: 'probe' },
  });
}

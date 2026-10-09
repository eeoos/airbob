import execution from 'k6/execution';
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { experimentTarget } from '../lib/cache-experiment-config.js';
import {
  canonicalAccommodationData,
  parseCacheBenchmarkFixture,
} from '../lib/accommodation-cache-benchmark.js';

function need(ok, message) {
  if (!ok) throw new Error(message);
}
function integer(name, fallback) {
  const raw = __ENV[name] ?? String(fallback);
  need(/^[1-9]\d*$/.test(raw), name + ' must be a positive integer');
  const value = Number(raw);
  need(Number.isSafeInteger(value), name + ' is too large');
  return value;
}
const target = experimentTarget(__ENV);
const origins = target.origins;
need(__ENV.CACHE_BENCHMARK_FIXTURE?.startsWith('/'), 'Fixture path must be absolute');
need(__ENV.RESULT_PATH?.startsWith('/'), 'Result path must be absolute');
need(/^[\x21-\x7e]+$/.test(__ENV.BENCHMARK_READ_MODEL_TOKEN || ''), 'Benchmark token required');
const fixture = parseCacheBenchmarkFixture(open(__ENV.CACHE_BENCHMARK_FIXTURE));
const kind = __ENV.EXPERIMENT_KIND;
need(['rate', 'burst'].includes(kind), 'EXPERIMENT_KIND must be rate or burst');
const rate = integer('RATE', 20);
const seconds = integer('SECONDS', 15);
const burst = integer('BURST', 60);
const vus = integer('VUS', 160);
need(rate <= target.limits.rate && seconds <= target.limits.seconds
  && burst <= target.limits.burst && vus <= target.limits.vus, 'Experiment bound exceeded');
const completed = new Counter('experiment_completed');
const valid = new Rate('experiment_valid_response');
const mismatch = new Counter('experiment_response_mismatch');
const latency = new Trend('experiment_latency_ms', true);
const started = new Trend('experiment_started_epoch_ms');
const ended = new Trend('experiment_ended_epoch_ms');
export const options = {
  hosts: target.hosts,
  insecureSkipTLSVerify: false,
  scenarios: {
    measure: kind === 'burst'
      ? { executor: 'per-vu-iterations', vus: burst, iterations: 1, maxDuration: '30s' }
      : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: seconds + 's',
        preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '10s' },
  },
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  // Threshold failures still produce evidence; the runner distinguishes SLO failure from invalid evidence.
  thresholds: { experiment_valid_response: ['rate==1'], dropped_iterations: ['count==0'] },
};

export function setup() {
  // This is a client release barrier, not a promise that all HTTP arrivals reach the server simultaneously.
  return { releaseAt: Date.now() + (kind === 'burst' ? 1500 : 0) };
}

export default function (data) {
  if (kind === 'burst') {
    const remaining = data.releaseAt - Date.now();
    if (remaining > 0) sleep(remaining / 1000);
  }
  const index = execution.scenario.iterationInTest;
  const origin = origins[(kind === 'burst' ? execution.vu.idInTest - 1 : index) % origins.length];
  // Burst/outage use one hot accommodation. Capacity uses a fixed round-robin fixture workload.
  const expected = fixture.accommodations[
    __ENV.DISTRIBUTION === 'uniform' ? index % fixture.accommodations.length : 0];
  const start = Date.now();
  const response = http.get(origin + '/api/v1/accommodations/' + expected.id, {
    headers: { 'X-Benchmark-Token': __ENV.BENCHMARK_READ_MODEL_TOKEN },
    redirects: 0,
    timeout: '8s',
    tags: { name: 'GET /api/v1/accommodations/{id}', app: String(origins.indexOf(origin)) },
  });
  let correct = false;
  if (response.status === 200) {
    try {
      const body = response.json();
      correct = body.success === true && body.data?.id === expected.id
        && canonicalAccommodationData(body.data) === expected.canonicalData;
    } catch (_) { /* Count corrupt payloads without recording private response content. */ }
    mismatch.add(correct ? 0 : 1);
  }
  completed.add(1);
  valid.add(correct);
  latency.add(response.timings.duration);
  started.add(start);
  ended.add(Date.now());
}

export function handleSummary(data) {
  const values = (name) => data.metrics[name]?.values || {};
  const duration = values('experiment_latency_ms');
  const count = values('experiment_completed').count || 0;
  const successful = values('experiment_valid_response').passes || 0;
  const first = values('experiment_started_epoch_ms').min;
  const last = values('experiment_ended_epoch_ms').max;
  const spanSeconds = Number.isFinite(first) && Number.isFinite(last) ? (last - first) / 1000 : null;
  const denominator = kind === 'rate' ? Math.max(seconds, spanSeconds || 0) : spanSeconds;
  const result = {
    schemaVersion: 1,
    kind, apps: target.scope === 'aws' ? integer('APP_INSTANCE_COUNT', 1) : origins.length,
    environment: target.scope, configuredRate: kind === 'rate' ? rate : null,
    configuredSeconds: kind === 'rate' ? seconds : null,
    requestedSamples: kind === 'burst' ? burst : rate * seconds,
    completed: count, successful,
    responseMismatches: values('experiment_response_mismatch').count || 0,
    dropped: values('dropped_iterations').count || 0,
    errorRate: count ? 1 - successful / count : 1,
    successRps: denominator ? successful / denominator : 0,
    requestSpanSeconds: spanSeconds,
    startedEpochMs: first ?? null,
    endedEpochMs: last ?? null,
    clientStartSpreadMs: kind === 'burst' ? values('experiment_started_epoch_ms').max - first : null,
    latencyMs: { p50: duration.med ?? null, p95: duration['p(95)'] ?? null,
      p99: duration['p(99)'] ?? null, max: duration.max ?? null },
    fixtureSha256: fixture.sha256,
  };
  return { [__ENV.RESULT_PATH]: JSON.stringify(result, null, 2) + '\n',
    stdout: JSON.stringify({ completed: count, p95: result.latencyMs.p95, errorRate: result.errorRate }) + '\n' };
}

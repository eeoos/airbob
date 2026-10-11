import execution from 'k6/execution';
import http from 'k6/http';
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
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
const responseCheck = __ENV.EXPERIMENT_RESPONSE_CHECK || 'canonical';
need(['canonical', 'body'].includes(responseCheck), 'Unknown response verification mode');
need(responseCheck !== 'body' || target.scope === 'aws'
  || (target.scope === 'local' && __ENV.LOCAL_CAPACITY_DIAGNOSTICS === 'true'),
'Exact-body checking requires the verified AWS runner or local capacity mode');
const expectedResponses = new SharedArray('verified-accommodation-responses', () => {
  const parsed = parseCacheBenchmarkFixture(open(__ENV.CACHE_BENCHMARK_FIXTURE));
  need(responseCheck !== 'body' || parsed.accommodations.every(entry => typeof entry.responseBody === 'string'),
    'Exact-body checking requires verified response bodies');
  // Keep only the representation used during measurement. Opening and validating the
  // complete fixture inside SharedArray avoids one object graph per preallocated VU.
  return parsed.accommodations.map(entry => ({ id: entry.id, fixtureSha256: parsed.sha256,
    ...(responseCheck === 'body' ? { responseBody: entry.responseBody } : { canonicalData: entry.canonicalData }) }));
});
const fixtureSha256 = expectedResponses[0].fixtureSha256;
const kind = __ENV.EXPERIMENT_KIND;
need(['rate', 'burst'].includes(kind), 'EXPERIMENT_KIND must be rate or burst');
const rate = integer('RATE', 20);
const seconds = integer('SECONDS', 15);
const burst = integer('BURST', 60);
const vus = integer('VUS', 160);
const rampRaw = __ENV.RAMP_SECONDS || '0';
need(/^(0|[1-9]\d*)$/.test(rampRaw), 'RAMP_SECONDS must be a nonnegative integer');
const rampSeconds = Number(rampRaw);
need(rampSeconds <= 60 && (!rampSeconds || (kind === 'rate'
  && (target.scope === 'aws' || __ENV.LOCAL_CAPACITY_DIAGNOSTICS === 'true'))), 'Invalid capacity ramp');
need(rate <= target.limits.rate && seconds <= target.limits.seconds
  && burst <= target.limits.burst && vus <= target.limits.vus, 'Experiment bound exceeded');
const completed = new Counter('experiment_completed');
const valid = new Rate('experiment_valid_response');
const mismatch = new Counter('experiment_response_mismatch');
const latency = new Trend('experiment_latency_ms', true);
const phaseNames = ['initial', 'middle', 'final'];
const phaseLatency = phaseNames.map(name => new Trend('experiment_' + name + '_latency_ms', true));
const started = new Trend('experiment_started_epoch_ms');
const ended = new Trend('experiment_ended_epoch_ms');
const rampCompleted = new Counter('experiment_ramp_completed');
const rampErrors = new Counter('experiment_ramp_errors');
export const options = {
  hosts: target.hosts,
  insecureSkipTLSVerify: false,
  scenarios: {
    measure: kind === 'burst'
      ? { executor: 'per-vu-iterations', vus: burst, iterations: 1, maxDuration: '30s' }
      : rampSeconds
      ? { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s',
        stages: [{ duration: rampSeconds + 's', target: rate }, { duration: seconds + 's', target: rate }],
        preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '10s' }
      : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: seconds + 's',
        preAllocatedVUs: vus, maxVUs: vus, gracefulStop: '10s' },
  },
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  // Threshold failures still produce evidence; the runner distinguishes SLO failure from invalid evidence.
  thresholds: { experiment_valid_response: ['rate==1'], dropped_iterations: ['count==0'],
    ...(rampSeconds ? { experiment_ramp_errors: ['count==0'] } : {}) },
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
  const expected = expectedResponses[
    __ENV.DISTRIBUTION === 'uniform' ? index % expectedResponses.length : 0];
  const start = Date.now();
  const measuredStart = execution.scenario.startTime + rampSeconds * 1000;
  const inRamp = rampSeconds > 0 && start < measuredStart;
  const response = http.get(origin + '/api/v1/accommodations/' + expected.id, {
    headers: { 'X-Benchmark-Token': __ENV.BENCHMARK_READ_MODEL_TOKEN },
    redirects: 0,
    timeout: '8s',
    tags: { name: 'GET /api/v1/accommodations/{id}', app: String(origins.indexOf(origin)) },
  });
  let correct = false;
  if (response.status === 200) {
    try {
      if (responseCheck === 'body') {
        // The runner checks semantic parity before the run. Every measured response still
        // has to match its full verified body; no sampling or status-only shortcuts.
        correct = response.body === expected.responseBody;
      } else {
        const body = response.json();
        correct = body.success === true && body.data?.id === expected.id
          && canonicalAccommodationData(body.data) === expected.canonicalData;
      }
    } catch (_) { /* Count corrupt payloads without recording private response content. */ }
    mismatch.add(correct ? 0 : 1);
  }
  if (inRamp) {
    // Keep the same VUs/connections during ramp and measurement. Validate every
    // response, while assigning latency and request counts by request start time.
    rampCompleted.add(1);
    rampErrors.add(correct ? 0 : 1);
    return;
  }
  completed.add(1);
  valid.add(correct);
  latency.add(response.timings.duration);
  if (kind === 'rate') {
    // Cohort by request start, so slow requests remain in their original period.
    const phase = Math.min(2, Math.max(0,
      Math.floor((start - measuredStart) / (seconds * 1000 / 3))));
    phaseLatency[phase].add(response.timings.duration);
  }
  started.add(start);
  ended.add(Date.now());
}

export function handleSummary(data) {
  const values = (name) => data.metrics[name]?.values || {};
  const duration = values('experiment_latency_ms');
  const count = values('experiment_completed').count || 0;
  const successful = values('experiment_valid_response').passes || 0;
  const rampCount = values('experiment_ramp_completed').count || 0;
  const rampErrorCount = values('experiment_ramp_errors').count || 0;
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
    phaseLatencyMs: Object.fromEntries(phaseNames.map(name => {
      const phase = values('experiment_' + name + '_latency_ms');
      return [name, { p50: phase.med ?? null, p95: phase['p(95)'] ?? null,
        p99: phase['p(99)'] ?? null, max: phase.max ?? null }];
    })),
    clientTimingsMs: Object.fromEntries(['http_req_blocked', 'http_req_connecting', 'iteration_duration'].map(name => {
      const metric = values(name);
      return [name, { p95: metric['p(95)'] ?? null, p99: metric['p(99)'] ?? null, max: metric.max ?? null }];
    })),
    fixtureSha256,
    fixtureStorage: 'shared-array',
    responseCheck,
    ...(rampSeconds ? {
      ramp: { seconds: rampSeconds, startRate: 1, completed: rampCount, errors: rampErrorCount },
      totalCompleted: count + rampCount,
      totalErrorRate: count + rampCount ? (count - successful + rampErrorCount) / (count + rampCount) : 1,
      serverCounterScope: 'ramp-and-measurement',
      droppedScope: 'whole-executor-including-ramp',
    } : {}),
  };
  return { [__ENV.RESULT_PATH]: JSON.stringify(result, null, 2) + '\n',
    stdout: JSON.stringify({ completed: count, p95: result.latencyMs.p95, errorRate: result.errorRate }) + '\n' };
}

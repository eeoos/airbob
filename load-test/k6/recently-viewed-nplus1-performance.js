import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { parseBenchmarkManifest } from './lib/benchmark-manifest.js';
import {
  authenticatedParams,
  loginBenchmarkAccount,
  resetRecentlyViewed,
} from './lib/benchmark-fixture.js';

if (!__ENV.BENCHMARK_MANIFEST) {
  throw new Error('BENCHMARK_MANIFEST is required');
}
if (!__ENV.TEST_PASSWORD || !__ENV.TEST_PASSWORD.trim()) {
  throw new Error('TEST_PASSWORD is required');
}

const TARGETS = {
  before: '/api/v2/members/recently-viewed',
  after: '/api/v1/members/recently-viewed',
};

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const manifest = parseBenchmarkManifest(open(__ENV.BENCHMARK_MANIFEST));
const BENCHMARK_EMAIL = __ENV.BENCHMARK_EMAIL || manifest.account.email;
const VARIANT = __ENV.VARIANT || 'before';
const EXPECTED_ROWS = Number(__ENV.EXPECTED_ROWS || 100);
const RATE = Number(__ENV.RATE || 2);
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '30s';
const MEASURE_DURATION = __ENV.MEASURE_DURATION || '1m';
const WARMUP_SETTLE_SECONDS = Number(__ENV.WARMUP_SETTLE_SECONDS || 5);
const SCENARIO_GRACEFUL_STOP_SECONDS = 5;
const PRE_ALLOCATED_VUS = Number(
  __ENV.PRE_ALLOCATED_VUS || Math.max(20, Math.ceil(RATE * 10)),
);
const RESULT_PATH = __ENV.K6_RESULT_PATH;
const targetPath = TARGETS[VARIANT];

if (!targetPath) {
  throw new Error('VARIANT must be one of: before, after');
}
if (!Number.isInteger(EXPECTED_ROWS) || EXPECTED_ROWS <= 0) {
  throw new Error('EXPECTED_ROWS must be a positive integer');
}
if (EXPECTED_ROWS > manifest.recentlyViewed.maxRows) {
  throw new Error(
    `EXPECTED_ROWS must not exceed manifest recentlyViewed.maxRows (${manifest.recentlyViewed.maxRows})`,
  );
}
if (!Number.isInteger(RATE) || RATE <= 0) {
  throw new Error('RATE must be a positive integer');
}
if (!Number.isInteger(PRE_ALLOCATED_VUS) || PRE_ALLOCATED_VUS <= 0) {
  throw new Error('PRE_ALLOCATED_VUS must be a positive integer');
}
if (!Number.isFinite(WARMUP_SETTLE_SECONDS) || WARMUP_SETTLE_SECONDS < 0) {
  throw new Error('WARMUP_SETTLE_SECONDS must be zero or greater');
}

function parseDurationSeconds(raw, variableName) {
  const unitSeconds = { ms: 0.001, s: 1, m: 60, h: 3600 };
  const compact = raw.replace(/\s+/g, '');
  const expression = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
  let seconds = 0;
  let consumed = '';
  let match;

  while ((match = expression.exec(compact)) !== null) {
    seconds += Number(match[1]) * unitSeconds[match[2]];
    consumed += match[0];
  }
  if (consumed !== compact || seconds <= 0) {
    throw new Error(`${variableName} must be a positive k6 duration, received: ${raw}`);
  }
  return seconds;
}

const warmupSeconds = parseDurationSeconds(WARMUP_DURATION, 'WARMUP_DURATION');
const measureSeconds = parseDurationSeconds(MEASURE_DURATION, 'MEASURE_DURATION');
const measureStartTime = `${warmupSeconds
  + SCENARIO_GRACEFUL_STOP_SECONDS
  + WARMUP_SETTLE_SECONDS}s`;

const clientDuration = new Trend('nplus1_client_duration', true);
const requestSuccess = new Rate('nplus1_request_success');

function arrivalRateScenario(exec, duration, startTime) {
  const scenario = {
    executor: 'constant-arrival-rate',
    exec,
    rate: RATE,
    timeUnit: '1s',
    duration,
    preAllocatedVUs: PRE_ALLOCATED_VUS,
    gracefulStop: `${SCENARIO_GRACEFUL_STOP_SECONDS}s`,
  };
  if (startTime) {
    scenario.startTime = startTime;
  }
  return scenario;
}

export const options = {
  setupTimeout: '5m',
  scenarios: {
    warmup: arrivalRateScenario('warmup', WARMUP_DURATION),
    measure: arrivalRateScenario('measure', MEASURE_DURATION, measureStartTime),
  },
  thresholds: {
    'nplus1_request_success{phase:measure}': ['rate==1'],
    'http_req_failed{phase:measure}': ['rate==0'],
    'dropped_iterations{scenario:measure}': ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
};

export function setup() {
  const sessionId = loginBenchmarkAccount({
    baseUrl: BASE_URL,
    email: BENCHMARK_EMAIL,
    password: __ENV.TEST_PASSWORD,
  });
  resetRecentlyViewed({
    baseUrl: BASE_URL,
    sessionId,
    accommodationIds: manifest.recentlyViewed.accommodationIds,
    datasetSize: EXPECTED_ROWS,
  });
  return { sessionId };
}

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function requestTarget(data, phase) {
  const tags = { phase, variant: VARIANT, expected_rows: String(EXPECTED_ROWS) };
  const response = http.get(
    `${BASE_URL}${targetPath}`,
    authenticatedParams(data.sessionId, { ...tags, name: `GET ${targetPath}` }),
  );
  const payload = parsePayload(response);
  const rows = payload && payload.data && payload.data.accommodations;
  const hasExpectedRows = Array.isArray(rows)
    && rows.length === EXPECTED_ROWS
    && payload.data.total_count === EXPECTED_ROWS;
  const success = response.status === 200 && hasExpectedRows;

  check(response, {
    [`${VARIANT} returns HTTP 200`]: (res) => res.status === 200,
    [`${VARIANT} returns ${EXPECTED_ROWS} rows`]: () => hasExpectedRows,
  }, tags);

  if (phase === 'measure') {
    clientDuration.add(response.timings.duration, tags);
    requestSuccess.add(success, tags);
  }
}

export function warmup(data) {
  requestTarget(data, 'warmup');
}

export function measure(data) {
  requestTarget(data, 'measure');
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

export function handleSummary(data) {
  const trend = data.metrics.nplus1_client_duration;
  const success = data.metrics.nplus1_request_success;
  const dropped = data.metrics['dropped_iterations{scenario:measure}'];
  const attempted = Number((success && success.values.passes) || 0)
    + Number((success && success.values.fails) || 0);
  const successful = Number((success && success.values.passes) || 0);
  const latency = (trend && trend.values) || {};
  const result = {
    variant: VARIANT,
    endpoint: targetPath,
    expected_rows: EXPECTED_ROWS,
    configured_rate_per_second: RATE,
    warmup_duration: WARMUP_DURATION,
    measure_duration: MEASURE_DURATION,
    requests: {
      attempted,
      successful,
      error_rate: attempted === 0 ? 1 : (attempted - successful) / attempted,
      achieved_rps: attempted / measureSeconds,
      dropped_iterations: Number((dropped && dropped.values.count) || 0),
    },
    latency_ms: {
      avg: finiteOrNull(latency.avg),
      min: finiteOrNull(latency.min),
      median: finiteOrNull(latency.med),
      p90: finiteOrNull(latency['p(90)']),
      p95: finiteOrNull(latency['p(95)']),
      max: finiteOrNull(latency.max),
    },
  };
  const json = `${JSON.stringify(result, null, 2)}\n`;
  const outputs = { stdout: json };
  if (RESULT_PATH) {
    outputs[RESULT_PATH] = json;
  }
  return outputs;
}

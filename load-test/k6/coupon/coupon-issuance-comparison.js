import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  buildCouponIssueTarget,
  classifyCouponIssueResponse,
  parseCouponSessionFixture,
  parseDurationSeconds,
  parsePhase,
  parsePositiveInteger,
  parseRequiredText,
  parseVariant,
  requireSessionCapacity,
  summarizeCouponBenchmarkMetrics,
  validateCouponWorkload,
} from './coupon-benchmark-fixture.js';
import {
  findExperimentCapsule,
  parseBenchmarkDatasetManifest,
  requireAccountCapacity,
} from '../lib/benchmark-dataset-manifest.js';
const { parseCouponAccountManifest } = require('./coupon-account-manifest.js');

function requiredEnvironment(name) {
  return parseRequiredText(__ENV[name], name);
}

function parseBaseUrl(raw) {
  const value = raw.replace(/\/+$/, '');
  if (!/^https?:\/\/[^/]+/.test(value)) {
    throw new Error('BASE_URL must be an http or https origin');
  }
  return value;
}

const BASE_URL = parseBaseUrl(requiredEnvironment('BASE_URL'));
const SESSION_FIXTURE = requiredEnvironment('SESSION_FIXTURE');
const BENCHMARK_DATASET_MANIFEST = requiredEnvironment('BENCHMARK_DATASET_MANIFEST');
const VARIANT = parseVariant(requiredEnvironment('VARIANT'));
const PHASE = parsePhase(__ENV.PHASE || 'measure');
const COUPON_ID = parsePositiveInteger(requiredEnvironment('COUPON_ID'), 'COUPON_ID');
const ISSUE_TARGET = buildCouponIssueTarget(
  VARIANT,
  COUPON_ID,
  __ENV.BENCHMARK_READ_MODEL_TOKEN,
);
const COUPON_STOCK = parsePositiveInteger(requiredEnvironment('COUPON_STOCK'), 'COUPON_STOCK');
const APP_VERSION = requiredEnvironment('APP_VERSION');
const APP_INSTANCE_COUNT = parsePositiveInteger(
  requiredEnvironment('APP_INSTANCE_COUNT'),
  'APP_INSTANCE_COUNT',
);
const ROUND = parsePositiveInteger(requiredEnvironment('ROUND'), 'ROUND');
const RUN_ORDER = parsePositiveInteger(requiredEnvironment('RUN_ORDER'), 'RUN_ORDER');
const RATE = parsePositiveInteger(__ENV.RATE || '100', 'RATE');
const DURATION = __ENV.DURATION || '30s';
const DURATION_SECONDS = parseDurationSeconds(DURATION);
const EXPERIMENT = __ENV.EXPERIMENT || 'scarcity';
const PLANNED_REQUESTS = validateCouponWorkload(EXPERIMENT, COUPON_STOCK, RATE, DURATION_SECONDS);
const PRE_ALLOCATED_VUS = parsePositiveInteger(
  __ENV.PRE_ALLOCATED_VUS || String(Math.max(50, RATE)),
  'PRE_ALLOCATED_VUS',
);
const MAX_VUS = parsePositiveInteger(
  __ENV.MAX_VUS || String(Math.max(PRE_ALLOCATED_VUS, RATE * 6)),
  'MAX_VUS',
);
const P99_LIMIT_MS = parsePositiveInteger(__ENV.P99_LIMIT_MS || '5000', 'P99_LIMIT_MS');
const P95_LIMIT_MS = parsePositiveInteger(__ENV.P95_LIMIT_MS || '1000', 'P95_LIMIT_MS');
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';
const GRACEFUL_STOP = __ENV.GRACEFUL_STOP || '30s';
const RESULT_PATH = __ENV.K6_RESULT_PATH
  || `build/k6/coupon-${PHASE}-${VARIANT}-${COUPON_ID}.json`;
const RUN_LABEL = requiredEnvironment('RUN_LABEL');

if (MAX_VUS < PRE_ALLOCATED_VUS) {
  throw new Error('MAX_VUS must be greater than or equal to PRE_ALLOCATED_VUS');
}

// Parse the large account manifest once, then retain only compact reporting metadata per VU.
const benchmarkDataset = new SharedArray('coupon-dataset-metadata', () => {
  const raw = open(BENCHMARK_DATASET_MANIFEST);
  const manifest = JSON.parse(raw).datasetVersion === 'coupon-accounts-v1'
    ? parseCouponAccountManifest(raw) : parseBenchmarkDatasetManifest(raw);
  const capsule = findExperimentCapsule(manifest, 'coupon-accounts-v1');
  return [{
    datasetVersion: manifest.datasetVersion,
    worldVersion: manifest.world.version,
    manifestSha256: crypto.sha256(raw, 'hex'),
    sourceDataset: manifest.sourceDataset || null,
    couponAccountCapsule: {
      capsuleId: capsule.capsuleId,
      accountPool: { capacity: capsule.accountPool.capacity },
    },
  }];
})[0];
const couponAccountCapsule = benchmarkDataset.couponAccountCapsule;
const BENCHMARK_DATASET_MANIFEST_SHA256 = benchmarkDataset.manifestSha256;
const sessions = new SharedArray('coupon-member-sessions', () => (
  parseCouponSessionFixture(open(SESSION_FIXTURE), BENCHMARK_DATASET_MANIFEST_SHA256)
));
const REQUIRED_SESSIONS = requireSessionCapacity(sessions, RATE, DURATION_SECONDS);
requireAccountCapacity(couponAccountCapsule, REQUIRED_SESSIONS);

const issueDuration = new Trend('coupon_issue_duration', true);
const successDuration = new Trend('coupon_issue_success_duration', true);
const soldOutDuration = new Trend('coupon_issue_sold_out_duration', true);
const startedAt = new Trend('coupon_request_started_at');
const finishedAt = new Trend('coupon_request_finished_at');
const successful = new Rate('coupon_issue_success');
const unexpectedRate = new Rate('coupon_issue_unexpected');
const invalidSetupRate = new Rate('coupon_issue_invalid_setup');
const outcomeCounters = {
  success: new Counter('coupon_issue_success_total'),
  sold_out: new Counter('coupon_issue_sold_out_total'),
  duplicate: new Counter('coupon_issue_duplicate_total'),
  not_issuable: new Counter('coupon_issue_not_issuable_total'),
  unprepared: new Counter('coupon_issue_unprepared_total'),
  authentication: new Counter('coupon_issue_authentication_total'),
  unexpected: new Counter('coupon_issue_unexpected_total'),
};
const invalidSetupOutcomes = new Set(['duplicate', 'not_issuable', 'unprepared', 'authentication']);

http.setResponseCallback(http.expectedStatuses(201, 409, 503));

export const options = {
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'],
  scenarios: {
    coupon_issuance: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: GRACEFUL_STOP,
      tags: { phase: PHASE, variant: VARIANT },
    },
  },
  thresholds: {
    [`coupon_issue_duration{phase:${PHASE},variant:${VARIANT}}`]: [
      `p(99)<=${P99_LIMIT_MS}`,
    ],
    [`coupon_issue_success_duration{phase:${PHASE},variant:${VARIANT}}`]: [
      `p(95)<=${P95_LIMIT_MS}`,
      `p(99)<=${P99_LIMIT_MS}`,
    ],
    [`coupon_issue_success_total{phase:${PHASE},variant:${VARIANT}}`]: ['count>0'],
    coupon_issue_unexpected: ['rate==0'],
    coupon_issue_invalid_setup: ['rate==0'],
    dropped_iterations: ['count==0'],
    http_req_failed: ['rate==0'],
    ...(EXPERIMENT === 'capacity' ? {
      coupon_issue_success: ['rate==1'],
      coupon_issue_sold_out_total: ['count==0'],
    } : {
      [`coupon_issue_sold_out_duration{phase:${PHASE},variant:${VARIANT}}`]: [
        `p(95)<=${P95_LIMIT_MS}`,
        `p(99)<=${P99_LIMIT_MS}`,
      ],
      coupon_issue_sold_out_total: ['count>0'],
    }),
  },
};

export function setup() {
  for (const counter of Object.values(outcomeCounters)) counter.add(0);
}

function responseErrorCode(response) {
  if (response.status === 201) {
    return undefined;
  }
  try {
    return response.json().error?.code;
  } catch (_) {
    return undefined;
  }
}

export default function () {
  const iteration = Number(exec.scenario.iterationInTest);
  // Arrival-rate scheduling can start a final iteration just past the duration boundary.
  if (iteration >= PLANNED_REQUESTS) return;
  const sessionId = sessions[iteration];
  if (!sessionId) {
    exec.test.abort(`SESSION_FIXTURE exhausted at iteration ${iteration}`);
  }

  const metricTags = { phase: PHASE, variant: VARIANT };
  startedAt.add(Date.now());
  const response = http.post(
    `${BASE_URL}${ISSUE_TARGET.path}`,
    null,
    {
      cookies: { SESSION_ID: sessionId },
      headers: ISSUE_TARGET.headers,
      timeout: REQUEST_TIMEOUT,
      tags: {
        ...metricTags,
        name: ISSUE_TARGET.metricName,
      },
    },
  );
  finishedAt.add(Date.now());

  const outcome = classifyCouponIssueResponse(response.status, responseErrorCode(response));
  const outcomeTags = { ...metricTags, outcome };
  issueDuration.add(response.timings.duration, outcomeTags);
  if (outcome === 'success') {
    successDuration.add(response.timings.duration, metricTags);
  } else if (outcome === 'sold_out') {
    soldOutDuration.add(response.timings.duration, metricTags);
  }
  successful.add(outcome === 'success');
  outcomeCounters[outcome].add(1, metricTags);
  unexpectedRate.add(outcome === 'unexpected', metricTags);
  invalidSetupRate.add(invalidSetupOutcomes.has(outcome), metricTags);
}

function format(value, digits = 2) {
  return Number.isFinite(value) ? value.toFixed(digits) : 'n/a';
}

export function handleSummary(data) {
  const benchmark = summarizeCouponBenchmarkMetrics(data);
  const {
    requestCount,
    requestRate,
    successRate,
    duration,
    successDuration: successfulDuration,
    outcomes,
    droppedIterations,
  } = benchmark;

  const stdout = [
    `coupon issuance: ${EXPERIMENT}/${VARIANT}/${PHASE} coupon=${COUPON_ID} run=${RUN_LABEL}`,
    `requests=${requestCount} rps=${format(requestRate)} success=${outcomes.success} success_rps=${format(successRate)}`,
    `all duration(ms) p50=${format(duration['p(50)'])} p95=${format(duration['p(95)'])} p99=${format(duration['p(99)'])}`,
    `success duration(ms) p50=${format(successfulDuration['p(50)'])} p95=${format(successfulDuration['p(95)'])} p99=${format(successfulDuration['p(99)'])}`,
    `outcomes success=${outcomes.success} sold_out=${outcomes.soldOut} duplicate=${outcomes.duplicate} not_issuable=${outcomes.notIssuable} unprepared=${outcomes.unprepared} unexpected=${outcomes.unexpected}`,
    `dropped_iterations=${droppedIterations}`,
    `result=${RESULT_PATH}`,
    '',
  ].join('\n');

  const artifact = {
    metadata: {
      generatedAt: new Date().toISOString(),
      experiment: EXPERIMENT,
      plannedRequests: PLANNED_REQUESTS,
      requestBudgetPolicy: 'at-most-planned-requests',
      p95LimitMs: P95_LIMIT_MS,
      p99LimitMs: P99_LIMIT_MS,
      runLabel: RUN_LABEL,
      baseUrl: BASE_URL,
      variant: VARIANT,
      phase: PHASE,
      couponId: COUPON_ID,
      couponStock: COUPON_STOCK,
      appVersion: APP_VERSION,
      appInstanceCount: APP_INSTANCE_COUNT,
      round: ROUND,
      runOrder: RUN_ORDER,
      rate: RATE,
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      requiredUniqueSessions: REQUIRED_SESSIONS,
      fixtureSessionCount: sessions.length,
      datasetVersion: benchmarkDataset.datasetVersion,
      worldVersion: benchmarkDataset.worldVersion,
      couponAccountCapsule: couponAccountCapsule.capsuleId,
      couponAccountCapacity: couponAccountCapsule.accountPool.capacity,
      benchmarkDatasetManifestSha256: BENCHMARK_DATASET_MANIFEST_SHA256,
      sourceDataset: benchmarkDataset.sourceDataset,
    },
    performance: benchmark,
    outcomes,
    summary: data,
  };

  return {
    stdout,
    [RESULT_PATH]: JSON.stringify(artifact, null, 2),
  };
}

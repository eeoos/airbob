import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  classifyCouponIssueResponse,
  parseCouponSessionFixture,
  parseDurationSeconds,
  parsePhase,
  parsePositiveInteger,
  parseRequiredText,
  parseVariant,
  requireSessionCapacity,
  summarizeCouponBenchmarkMetrics,
} from './lib/coupon-benchmark-fixture.js';

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
const VARIANT = parseVariant(requiredEnvironment('VARIANT'));
const PHASE = parsePhase(__ENV.PHASE || 'measure');
const COUPON_ID = parsePositiveInteger(requiredEnvironment('COUPON_ID'), 'COUPON_ID');
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
const PRE_ALLOCATED_VUS = parsePositiveInteger(
  __ENV.PRE_ALLOCATED_VUS || String(Math.max(50, RATE)),
  'PRE_ALLOCATED_VUS',
);
const MAX_VUS = parsePositiveInteger(
  __ENV.MAX_VUS || String(Math.max(PRE_ALLOCATED_VUS, RATE * 6)),
  'MAX_VUS',
);
const P99_LIMIT_MS = parsePositiveInteger(__ENV.P99_LIMIT_MS || '5000', 'P99_LIMIT_MS');
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';
const GRACEFUL_STOP = __ENV.GRACEFUL_STOP || '30s';
const RESULT_PATH = __ENV.K6_RESULT_PATH
  || `build/k6/coupon-${PHASE}-${VARIANT}-${COUPON_ID}.json`;
const RUN_LABEL = requiredEnvironment('RUN_LABEL');

if (MAX_VUS < PRE_ALLOCATED_VUS) {
  throw new Error('MAX_VUS must be greater than or equal to PRE_ALLOCATED_VUS');
}

const sessions = new SharedArray('coupon-member-sessions', () => (
  parseCouponSessionFixture(open(SESSION_FIXTURE))
));
const REQUIRED_SESSIONS = requireSessionCapacity(sessions, RATE, DURATION_SECONDS);

const issueDuration = new Trend('coupon_issue_duration', true);
const successDuration = new Trend('coupon_issue_success_duration', true);
const unexpectedRate = new Rate('coupon_issue_unexpected');
const invalidSetupRate = new Rate('coupon_issue_invalid_setup');
const outcomeCounters = {
  success: new Counter('coupon_issue_success_total'),
  sold_out: new Counter('coupon_issue_sold_out_total'),
  duplicate: new Counter('coupon_issue_duplicate_total'),
  not_issuable: new Counter('coupon_issue_not_issuable_total'),
  unprepared: new Counter('coupon_issue_unprepared_total'),
  lock_timeout: new Counter('coupon_issue_lock_timeout_total'),
  unexpected: new Counter('coupon_issue_unexpected_total'),
};
const invalidSetupOutcomes = new Set(['duplicate', 'not_issuable', 'unprepared']);

http.setResponseCallback(http.expectedStatuses(201, 409, 503));

export const options = {
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
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
      `p(99)<${P99_LIMIT_MS}`,
    ],
    [`coupon_issue_success_duration{phase:${PHASE},variant:${VARIANT}}`]: [
      `p(99)<${P99_LIMIT_MS}`,
    ],
    [`coupon_issue_success_total{phase:${PHASE},variant:${VARIANT}}`]: ['count>0'],
    coupon_issue_unexpected: ['rate==0'],
    coupon_issue_invalid_setup: ['rate==0'],
    dropped_iterations: ['count==0'],
    http_req_failed: ['rate==0'],
  },
};

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
  const sessionId = sessions[iteration];
  if (!sessionId) {
    exec.test.abort(`SESSION_FIXTURE exhausted at iteration ${iteration}`);
  }

  const metricTags = { phase: PHASE, variant: VARIANT };
  const response = http.post(
    `${BASE_URL}/api/v1/coupons/${COUPON_ID}/issue/${VARIANT}`,
    null,
    {
      cookies: { SESSION_ID: sessionId },
      timeout: REQUEST_TIMEOUT,
      tags: {
        ...metricTags,
        name: `POST /api/v1/coupons/{couponId}/issue/${VARIANT}`,
      },
    },
  );

  const outcome = classifyCouponIssueResponse(response.status, responseErrorCode(response));
  const outcomeTags = { ...metricTags, outcome };
  issueDuration.add(response.timings.duration, outcomeTags);
  if (outcome === 'success') {
    successDuration.add(response.timings.duration, metricTags);
  }
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
    `coupon issuance: ${VARIANT}/${PHASE} coupon=${COUPON_ID} run=${RUN_LABEL}`,
    `requests=${requestCount} rps=${format(requestRate)} success=${outcomes.success} success_rps=${format(successRate)}`,
    `all duration(ms) p50=${format(duration['p(50)'])} p95=${format(duration['p(95)'])} p99=${format(duration['p(99)'])}`,
    `success duration(ms) p50=${format(successfulDuration['p(50)'])} p95=${format(successfulDuration['p(95)'])} p99=${format(successfulDuration['p(99)'])}`,
    `outcomes success=${outcomes.success} sold_out=${outcomes.soldOut} duplicate=${outcomes.duplicate} not_issuable=${outcomes.notIssuable} unprepared=${outcomes.unprepared} lock_timeout=${outcomes.lockTimeout} unexpected=${outcomes.unexpected}`,
    `dropped_iterations=${droppedIterations}`,
    `result=${RESULT_PATH}`,
    '',
  ].join('\n');

  const artifact = {
    metadata: {
      generatedAt: new Date().toISOString(),
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

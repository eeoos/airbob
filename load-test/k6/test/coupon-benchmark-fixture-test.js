import { check } from 'k6';
import { Counter } from 'k6/metrics';
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
} from '../coupon/coupon-benchmark-fixture.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    contract_test_completed: ['count==1'],
  },
};

const contractTestCompleted = new Counter('contract_test_completed');

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const manifestSha256 = 'a'.repeat(64);
  const sessions = parseCouponSessionFixture(JSON.stringify({
    datasetVersion: 'coupon-issuance-v2',
    benchmarkDatasetManifestSha256: manifestSha256,
    sessions: ['session-a', 'session-b', 'session-c'],
  }), manifestSha256);
  const summary = summarizeCouponBenchmarkMetrics({
    metrics: {
      http_reqs: { values: { count: 10, rate: 5 } },
      coupon_issue_success_total: { values: { count: 3, rate: 1.5 } },
      coupon_issue_sold_out_total: { values: { count: 7 } },
      coupon_issue_duration: {
        values: { 'p(50)': 4, 'p(95)': 9, 'p(99)': 10 },
      },
      coupon_issue_success_duration: {
        values: { 'p(50)': 20, 'p(95)': 40, 'p(99)': 50 },
      },
      dropped_iterations: { values: { count: 0 } },
    },
  });
  const luaTarget = buildCouponIssueTarget('lua', 1);
  const dbTarget = buildCouponIssueTarget('db', 1, ' secret-token ');

  check(sessions, {
    'lua uses the production v1 endpoint': () => (
      luaTarget.path === '/api/v1/coupons/1/issue'
      && luaTarget.metricName === 'POST /api/v1/coupons/{couponId}/issue'
      && Object.keys(luaTarget.headers).length === 0
    ),
    'db uses the benchmark v2 endpoint and trimmed token': () => (
      dbTarget.path === '/api/v2/coupons/1/issue'
      && dbTarget.metricName === 'POST /api/v2/coupons/{couponId}/issue'
      && dbTarget.headers['X-Benchmark-Token'] === 'secret-token'
    ),
    'db rejects a missing benchmark token': () => rejects(() => (
      buildCouponIssueTarget('db', 1)
    )),
    'db rejects a blank benchmark token': () => rejects(() => (
      buildCouponIssueTarget('db', 1, ' ')
    )),
    'lua does not require a benchmark token': () => (
      buildCouponIssueTarget('lua', 1).path === '/api/v1/coupons/1/issue'
    ),
    'valid fixture returns sessions': (value) => value.length === 3,
    'malformed fixture is rejected': () => rejects(() => parseCouponSessionFixture('{', manifestSha256)),
    'wrong dataset version is rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v1',
      benchmarkDatasetManifestSha256: manifestSha256,
      sessions: ['session-a'],
    }), manifestSha256)),
    'manifest drift is rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v2',
      benchmarkDatasetManifestSha256: 'b'.repeat(64),
      sessions: ['session-a'],
    }), manifestSha256)),
    'blank sessions are rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v2',
      benchmarkDatasetManifestSha256: manifestSha256,
      sessions: [''],
    }), manifestSha256)),
    'duplicate sessions are rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v2',
      benchmarkDatasetManifestSha256: manifestSha256,
      sessions: ['session-a', 'session-a'],
    }), manifestSha256)),
    'db variant is accepted': () => parseVariant('db') === 'db',
    'removed lock variant is rejected': () => rejects(() => parseVariant('lock')),
    'lua variant is accepted': () => parseVariant('lua') === 'lua',
    'unknown variant is rejected': () => rejects(() => parseVariant('enum-strategy')),
    'measure phase is accepted': () => parsePhase('measure') === 'measure',
    'unknown phase is rejected': () => rejects(() => parsePhase('mixed')),
    'positive integer is parsed': () => parsePositiveInteger('500', 'RATE') === 500,
    'zero integer is rejected': () => rejects(() => parsePositiveInteger('0', 'RATE')),
    'required text is trimmed': () => parseRequiredText(' app-v1 ', 'APP_VERSION') === 'app-v1',
    'blank required text is rejected': () => rejects(() => parseRequiredText(' ', 'APP_VERSION')),
    'seconds duration is parsed': () => parseDurationSeconds('30s') === 30,
    'minutes duration is parsed': () => parseDurationSeconds('2m') === 120,
    'compound duration is rejected': () => rejects(() => parseDurationSeconds('1m30s')),
    'arrival boundary reserves one session': () => requireSessionCapacity(sessions, 1, 1) === 2,
    'enough sessions are accepted': () => requireSessionCapacity(sessions, 1, 2) === 3,
    'insufficient sessions are rejected': () => rejects(() => requireSessionCapacity(sessions, 2, 2)),
    'created response is success': () => classifyCouponIssueResponse(201) === 'success',
    'authentication failure invalidates the fixture': () => (
      classifyCouponIssueResponse(401, 'M004') === 'authentication'
      && classifyCouponIssueResponse(403, 'B001') === 'authentication'
    ),
    'capacity reserves enough stock for every request': () => validateCouponWorkload('capacity', 11, 5, 2) === 10,
    'capacity rejects exhausting stock': () => rejects(() => validateCouponWorkload('capacity', 10, 5, 2)),
    'scarcity requires fewer coupons than requests': () => rejects(() => validateCouponWorkload('scarcity', 10, 5, 2)),
    'scarcity accepts a sold-out workload': () => validateCouponWorkload('scarcity', 2, 5, 2) === 10,
    'unknown experiment is rejected': () => rejects(() => validateCouponWorkload('mixed', 2, 5, 2)),
    'total and successful RPS use the HTTP window including the final response': () => {
      const result = summarizeCouponBenchmarkMetrics({ metrics: {
        http_reqs: { values: { count: 20, rate: 99 } },
        coupon_request_started_at: { values: { min: 1000 } },
        coupon_request_finished_at: { values: { max: 5000 } },
        coupon_issue_success_total: { values: { count: 8, rate: 99 } },
        coupon_issue_sold_out_duration: { values: { 'p(95)': 2 } },
      } });
      return result.requestRate === 5 && result.successRate === 2 && result.measurementDurationSeconds === 4
        && result.soldOutDuration['p(95)'] === 2;
    },
    'sold out response is classified': () => classifyCouponIssueResponse(409, 'CP002') === 'sold_out',
    'removed lock timeout response is unexpected': () => (
      classifyCouponIssueResponse(503, 'CP012') === 'unexpected'
    ),
    'wrong status and code pair is unexpected': () => (
      classifyCouponIssueResponse(409, 'CP012') === 'unexpected'
    ),
    'summary keeps total and success RPS separate': () => (
      summary.requestRate === 5 && summary.successRate === 1.5
    ),
    'summary keeps total and success p99 separate': () => (
      summary.duration['p(99)'] === 10 && summary.successDuration['p(99)'] === 50
    ),
  });
  contractTestCompleted.add(1);
}

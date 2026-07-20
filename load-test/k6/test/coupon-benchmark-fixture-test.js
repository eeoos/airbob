import { check } from 'k6';
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
} from '../lib/coupon-benchmark-fixture.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const sessions = parseCouponSessionFixture(JSON.stringify({
    datasetVersion: 'coupon-issuance-v1',
    sessions: ['session-a', 'session-b', 'session-c'],
  }));
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
  const lockTarget = buildCouponIssueTarget('lock', 1, ' secret-token ');

  check(sessions, {
    'lua uses the production v1 endpoint': () => (
      luaTarget.path === '/api/v1/coupons/1/issue'
      && luaTarget.metricName === 'POST /api/v1/coupons/{couponId}/issue'
      && Object.keys(luaTarget.headers).length === 0
    ),
    'lock uses the benchmark v2 endpoint and trimmed token': () => (
      lockTarget.path === '/api/v2/coupons/1/issue'
      && lockTarget.metricName === 'POST /api/v2/coupons/{couponId}/issue'
      && lockTarget.headers['X-Benchmark-Token'] === 'secret-token'
    ),
    'lock rejects a missing benchmark token': () => rejects(() => (
      buildCouponIssueTarget('lock', 1)
    )),
    'lock rejects a blank benchmark token': () => rejects(() => (
      buildCouponIssueTarget('lock', 1, ' ')
    )),
    'lua does not require a benchmark token': () => (
      buildCouponIssueTarget('lua', 1).path === '/api/v1/coupons/1/issue'
    ),
    'valid fixture returns sessions': (value) => value.length === 3,
    'malformed fixture is rejected': () => rejects(() => parseCouponSessionFixture('{')),
    'wrong dataset version is rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v2',
      sessions: ['session-a'],
    }))),
    'blank sessions are rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v1',
      sessions: [''],
    }))),
    'duplicate sessions are rejected': () => rejects(() => parseCouponSessionFixture(JSON.stringify({
      datasetVersion: 'coupon-issuance-v1',
      sessions: ['session-a', 'session-a'],
    }))),
    'lock variant is accepted': () => parseVariant('lock') === 'lock',
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
    'sold out response is classified': () => classifyCouponIssueResponse(409, 'CP002') === 'sold_out',
    'lock timeout response is classified': () => (
      classifyCouponIssueResponse(503, 'CP012') === 'lock_timeout'
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
}

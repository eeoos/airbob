import { check } from 'k6';

import {
  buildTrafficOptions,
  parseTrafficRunConfig,
  summarizeTrafficMetrics,
} from '../lib/traffic-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const manifest = {
  datasetVersion: 'nplus1-v1',
  anchorTime: '2025-01-01T00:00:00',
  maxRequestedSize: 200,
  requiredRows: 201,
  account: { email: 'benchmark-nplus1@airbob.cloud' },
  review: {
    accommodationId: 901,
    publishedReviewCount: 201,
    reviewsWithImages: 201,
  },
  hostAccommodations: { expectedRows: 201, detailAccommodationId: 902 },
  guestReservations: { filterType: 'PAST', expectedRows: 201 },
  hostReservations: { filterType: 'PAST', expectedRows: 201 },
  wishlists: {
    expectedRows: 201,
    primaryWishlistId: 903,
    primaryWishlistAccommodationRows: 201,
  },
  recentlyViewed: {
    maxRows: 100,
    accommodationIds: Array.from({ length: 100 }, (_, index) => 1001 + index),
  },
};

const manifestRaw = JSON.stringify(manifest);
const commonEnvironment = {
  MODE: 'measure',
  ROLE: 'guest',
  TARGET: 'accommodation-detail',
  RATE: '5',
  DURATION: '1m',
  MIN_COMPLETED_SAMPLES: '300',
  ROUND: '2',
  RUN_ORDER: '1',
  RUN_LABEL: 'guest-accommodation-detail-r2-o1',
  APP_COMMIT: 'a'.repeat(40),
  APP_INSTANCE_COUNT: '1',
};
const targetNames = ['accommodation-detail', 'recently-viewed'];

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function metric(values) {
  return { values };
}

export default function () {
  const config = parseTrafficRunConfig(commonEnvironment, manifestRaw, targetNames);
  const measureOptions = buildTrafficOptions(config);
  const inspectOptions = buildTrafficOptions({ ...config, mode: 'inspect' });
  const warmupOptions = buildTrafficOptions({ ...config, mode: 'warmup' });
  const validSummary = summarizeTrafficMetrics({
    metrics: {
      traffic_request_success: metric({ passes: 300, fails: 0 }),
      traffic_client_duration: metric({
        avg: 12,
        min: 4,
        med: 10,
        'p(90)': 18,
        'p(95)': 20,
        'p(99)': 25,
        max: 30,
      }),
      traffic_completed_samples: metric({ count: 300 }),
      iterations: metric({ count: 300 }),
      http_req_failed: metric({ passes: 300, fails: 0, rate: 0 }),
      checks: metric({ passes: 600, fails: 0, rate: 1 }),
      dropped_iterations: metric({ count: 0 }),
    },
  }, config);
  const invalidSummary = summarizeTrafficMetrics({
    metrics: {
      traffic_request_success: metric({ passes: 199, fails: 1 }),
      traffic_client_duration: metric({ med: 10, 'p(95)': 20, 'p(99)': 25 }),
      traffic_completed_samples: metric({ count: 200 }),
      iterations: metric({ count: 200 }),
      http_req_failed: metric({ passes: 199, fails: 1, rate: 0.005 }),
      checks: metric({ passes: 398, fails: 2, rate: 0.995 }),
      dropped_iterations: metric({ count: 3 }),
    },
  }, config);
  const serialized = JSON.stringify(validSummary);

  check(config, {
    'strict nplus1 manifest is parsed': (value) => value.manifest.datasetVersion === 'nplus1-v1',
    'manifest hash is a canonical SHA-256': (value) => /^[0-9a-f]{64}$/.test(value.manifestSha256),
    'pipeline rehearsal cannot claim representative performance': (value) => (
      value.releaseKind === 'pipeline-rehearsal'
        && value.claimScope === 'pipeline-only'
    ),
    'single guest target and provenance are recorded': (value) => (
      value.role === 'guest'
        && value.target === 'accommodation-detail'
        && value.round === 2
        && value.runOrder === 1
        && value.appCommit === 'a'.repeat(40)
    ),
    'unknown dataset version is rejected': () => rejects(() => parseTrafficRunConfig(
      commonEnvironment,
      JSON.stringify({ ...manifest, datasetVersion: 'traffic-v1' }),
      targetNames,
    )),
    'unknown mode is rejected': () => rejects(() => parseTrafficRunConfig(
      { ...commonEnvironment, MODE: 'mixed' }, manifestRaw, targetNames,
    )),
    'non-guest role is rejected in the first slice': () => rejects(() => parseTrafficRunConfig(
      { ...commonEnvironment, ROLE: 'admin' }, manifestRaw, targetNames,
    )),
    'unknown and mixed targets are rejected': () => ['unknown', 'mixed'].every((target) => rejects(() => (
      parseTrafficRunConfig({ ...commonEnvironment, TARGET: target }, manifestRaw, targetNames)
    ))),
    'missing or non-positive rate is rejected': () => [undefined, '0', '-1'].every((rate) => {
      const environment = { ...commonEnvironment };
      if (rate === undefined) delete environment.RATE;
      else environment.RATE = rate;
      return rejects(() => parseTrafficRunConfig(environment, manifestRaw, targetNames));
    }),
    'missing or invalid duration is rejected': () => [undefined, '', '0s', 'later'].every((duration) => {
      const environment = { ...commonEnvironment };
      if (duration === undefined) delete environment.DURATION;
      else environment.DURATION = duration;
      return rejects(() => parseTrafficRunConfig(environment, manifestRaw, targetNames));
    }),
    'unsafe result path and run label are rejected': () => (
      rejects(() => parseTrafficRunConfig({
        ...commonEnvironment,
        K6_RESULT_PATH: '/tmp/result.json',
      }, manifestRaw, targetNames))
        && rejects(() => parseTrafficRunConfig({
          ...commonEnvironment,
          RUN_LABEL: '../secret',
        }, manifestRaw, targetNames))
    ),
    'measure uses only one constant-arrival-rate scenario': () => (
      Object.keys(measureOptions.scenarios).join(',') === 'measure'
        && measureOptions.scenarios.measure.executor === 'constant-arrival-rate'
        && measureOptions.scenarios.measure.rate === 5
        && measureOptions.scenarios.measure.duration === '1m'
        && measureOptions.scenarios.measure.exec === 'measure'
    ),
    'inspect is a network-free single iteration contract': () => (
      Object.keys(inspectOptions.scenarios).join(',') === 'inspect'
        && inspectOptions.scenarios.inspect.executor === 'shared-iterations'
        && inspectOptions.scenarios.inspect.iterations === 1
    ),
    'warmup is isolated from measure metrics': () => (
      Object.keys(warmupOptions.scenarios).join(',') === 'warmup'
        && warmupOptions.scenarios.warmup.exec === 'warmup'
        && !Object.prototype.hasOwnProperty.call(warmupOptions.scenarios, 'measure')
    ),
    'measure thresholds reject failures and drops': () => (
      JSON.stringify(measureOptions.thresholds.traffic_request_success) === JSON.stringify(['rate==1'])
        && JSON.stringify(measureOptions.thresholds.http_req_failed) === JSON.stringify(['rate==0'])
        && JSON.stringify(measureOptions.thresholds.dropped_iterations) === JSON.stringify(['count==0'])
    ),
    'valid summary records load integrity and percentiles': () => (
      validSummary.validity.status === 'valid'
        && validSummary.validity.reasons.length === 0
        && validSummary.load.iterations.started === 300
        && validSummary.load.iterations.completed === 300
        && validSummary.load.iterations.minimumRequired === 300
        && validSummary.performance.latencyMs.p95 === 20
        && validSummary.performance.latencyMs.p99 === 25
    ),
    'failure, drop, and minimum sample violations are explicit': () => (
      invalidSummary.validity.status === 'invalid'
        && invalidSummary.validity.reasons.includes('request-errors')
        && invalidSummary.validity.reasons.includes('check-failures')
        && invalidSummary.validity.reasons.includes('dropped-iterations')
        && invalidSummary.validity.reasons.includes('minimum-samples-not-met')
    ),
    'public artifact includes required provenance': () => (
      validSummary.metadata.role === 'guest'
        && validSummary.metadata.target === 'accommodation-detail'
        && validSummary.metadata.datasetVersion === 'nplus1-v1'
        && validSummary.metadata.manifestSha256 === config.manifestSha256
        && validSummary.metadata.round === 2
        && validSummary.metadata.runOrder === 1
    ),
    'public artifact excludes known secrets and secret field names': () => (
      !serialized.includes('benchmark-password')
        && !serialized.includes('benchmark-token')
        && !serialized.includes('SESSION_ID')
        && !serialized.includes('password')
        && !serialized.includes('cookie')
        && !serialized.includes('token')
    ),
  });
}

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import {
  parseBenchmarkManifest,
  parseRecentlyViewedSize,
} from './lib/benchmark-manifest.js';
import {
  authenticatedParams,
  loginBenchmarkAccount,
  resetRecentlyViewed,
  smokeCheckManifestTargets,
} from './lib/benchmark-fixture.js';

if (!__ENV.BENCHMARK_MANIFEST) {
  throw new Error('BENCHMARK_MANIFEST is required');
}
if (!__ENV.TEST_PASSWORD || !__ENV.TEST_PASSWORD.trim()) {
  throw new Error('TEST_PASSWORD is required');
}
if (!__ENV.BENCHMARK_READ_MODEL_TOKEN || !__ENV.BENCHMARK_READ_MODEL_TOKEN.trim()) {
  throw new Error('BENCHMARK_READ_MODEL_TOKEN is required');
}

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const manifest = parseBenchmarkManifest(open(__ENV.BENCHMARK_MANIFEST));
const datasetSize = parseRecentlyViewedSize(__ENV.DATASET_SIZE, manifest);
const measuredDuration = new Trend('benchmark_recently_viewed_duration', true);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    smoke: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    'http_req_failed{phase:measure}': ['rate==0'],
  },
};

export function setup() {
  const sessionId = loginBenchmarkAccount({
    baseUrl: BASE_URL,
    email: manifest.account.email,
    password: __ENV.TEST_PASSWORD,
  });

  smokeCheckManifestTargets({ baseUrl: BASE_URL, sessionId, manifest });
  resetRecentlyViewed({
    baseUrl: BASE_URL,
    sessionId,
    accommodationIds: manifest.recentlyViewed.accommodationIds,
    datasetSize,
    benchmarkToken: __ENV.BENCHMARK_READ_MODEL_TOKEN,
  });

  return { sessionId, expectedRows: datasetSize };
}

export default function (data) {
  const response = http.get(
    `${BASE_URL}/api/v1/members/recently-viewed`,
    {
      ...authenticatedParams(data.sessionId, {
        phase: 'measure',
        api: 'recently_viewed',
        name: 'GET /api/v1/members/recently-viewed',
      }),
    },
  );

  measuredDuration.add(response.timings.duration, {
    phase: 'measure',
    api: 'recently_viewed',
  });
  check(response, {
    'recently viewed returns 200': (res) => res.status === 200,
    'recently viewed returns requested rows': (res) => (
      res.json('data.total_count') === data.expectedRows
    ),
  });
}

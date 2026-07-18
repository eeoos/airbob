import http from 'k6/http';
import { Trend, Rate } from 'k6/metrics';
import {
  parseBenchmarkManifest,
  parseRecentlyViewedSize,
} from '../lib/benchmark-manifest.js';
import {
  authenticatedParams,
  loginBenchmarkAccount,
  resetRecentlyViewed,
} from '../lib/benchmark-fixture.js';

if (!__ENV.BENCHMARK_MANIFEST) {
  throw new Error('BENCHMARK_MANIFEST is required');
}
if (!__ENV.TEST_PASSWORD || !__ENV.TEST_PASSWORD.trim()) {
  throw new Error('TEST_PASSWORD is required');
}
if (!__ENV.K6_RESULT_PATH || !__ENV.K6_RESULT_PATH.trim()) {
  throw new Error('K6_RESULT_PATH is required');
}

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const MODE = __ENV.MODE || 'all';
const DURATION = __ENV.DURATION || '45s';
const RATE = Number(__ENV.RATE || 3);
const STAGGER_MS = Number(__ENV.STAGGER_MS || 100);
const manifest = parseBenchmarkManifest(open(__ENV.BENCHMARK_MANIFEST));
const SIZE = parseRecentlyViewedSize(__ENV.SIZE, manifest);

if (!['all', 'recent_only'].includes(MODE)) {
  throw new Error('MODE must be one of: all, recent_only');
}
if (MODE === 'all' && SIZE > 50) {
  throw new Error('MODE=all supports SIZE 1, 20, or 50; use MODE=recent_only for SIZE=100');
}
if (!Number.isInteger(RATE) || RATE <= 0) {
  throw new Error('RATE must be a positive integer');
}
if (!Number.isInteger(STAGGER_MS) || STAGGER_MS < 0) {
  throw new Error('STAGGER_MS must be a non-negative integer');
}

const clientDuration = new Trend('batch_fetch_client_duration', true);
const requestSuccess = new Rate('batch_fetch_request_success');

const API_DEFINITIONS = {
  reviews: {
    name: 'GET /api/v1/accommodations/{accommodationId}/reviews',
    path: () => `/api/v1/accommodations/${manifest.review.accommodationId}/reviews?size=${SIZE}`,
    arrayKey: 'reviews',
    rowContract: (row) => (
      Number.isInteger(row.id)
        && row.id > 0
        && Array.isArray(row.images)
        && row.images.length > 0
    ),
  },
  host_accommodations: {
    name: 'GET /api/v1/profile/host/accommodations',
    path: () => `/api/v1/profile/host/accommodations?size=${SIZE}&status=PUBLISHED`,
    arrayKey: 'accommodations',
    rowContract: (row) => Number.isInteger(row.id) && row.id > 0 && row.status === 'PUBLISHED',
  },
  guest_reservations: {
    name: 'GET /api/v1/profile/guest/reservations',
    path: () => `/api/v1/profile/guest/reservations?size=${SIZE}&filterType=PAST`,
    arrayKey: 'reservations',
    rowContract: (row) => (
      typeof row.reservation_uid === 'string'
        && row.accommodation
        && Number.isInteger(row.accommodation.id)
        && row.accommodation.id > 0
    ),
  },
  host_reservations: {
    name: 'GET /api/v1/profile/host/reservations',
    path: () => `/api/v1/profile/host/reservations?size=${SIZE}&filterType=PAST`,
    arrayKey: 'reservations',
    rowContract: (row) => (
      typeof row.reservation_uid === 'string'
        && row.status === 'CONFIRMED'
        && row.accommodation
        && Number.isInteger(row.accommodation.id)
        && row.accommodation.id > 0
    ),
  },
  wishlists: {
    name: 'GET /api/v1/members/wishlists',
    path: () => `/api/v1/members/wishlists?size=${SIZE}`,
    arrayKey: 'wishlists',
    rowContract: (row) => Number.isInteger(row.id) && row.id > 0,
  },
  wishlist_accommodations: {
    name: 'GET /api/v1/members/wishlists/accommodations/{wishlistId}',
    path: () => (
      `/api/v1/members/wishlists/accommodations/${manifest.wishlists.primaryWishlistId}?size=${SIZE}`
    ),
    arrayKey: 'wishlist_accommodations',
    rowContract: (row) => (
      row.accommodation
        && Number.isInteger(row.accommodation.id)
        && row.accommodation.id > 0
    ),
  },
  recently_viewed: {
    name: 'GET /api/v1/members/recently-viewed',
    path: () => '/api/v1/members/recently-viewed',
  },
};

const ALL_APIS = Object.keys(API_DEFINITIONS);
const ENABLED_APIS = MODE === 'recent_only' ? ['recently_viewed'] : ALL_APIS;

function taggedMetric(metric, api) {
  return `${metric}{api:${api},phase:measure,size:${SIZE}}`;
}

function scenarioOptions(api, scenarioIndex) {
  return {
    executor: 'constant-arrival-rate',
    exec: api,
    startTime: `${scenarioIndex * STAGGER_MS}ms`,
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(2, Math.ceil(RATE * 2)),
    maxVUs: Math.max(4, Math.ceil(RATE * 4)),
    gracefulStop: '10s',
  };
}

const scenarios = {};
const thresholds = {
  'batch_fetch_request_success': ['rate==1'],
  'http_req_failed{phase:measure}': ['rate==0'],
};

ENABLED_APIS.forEach((api, scenarioIndex) => {
  scenarios[api] = scenarioOptions(api, scenarioIndex);
  thresholds[taggedMetric('batch_fetch_client_duration', api)] = ['p(95)>=0'];
  thresholds[taggedMetric('batch_fetch_request_success', api)] = ['rate>=0'];
  thresholds[`dropped_iterations{scenario:${api}}`] = ['count>=0'];
});

export const options = {
  setupTimeout: '5m',
  scenarios,
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
};

export function setup() {
  const sessionId = loginBenchmarkAccount({
    baseUrl: BASE_URL,
    email: manifest.account.email,
    password: __ENV.TEST_PASSWORD,
  });
  const expectedRecentlyViewedIds = manifest.recentlyViewed.accommodationIds.slice(0, SIZE);

  // Setup intentionally performs no target GET. It only prepares authentication and
  // the deterministic recently-viewed collection before measurement begins.
  resetRecentlyViewed({
    baseUrl: BASE_URL,
    sessionId,
    accommodationIds: manifest.recentlyViewed.accommodationIds,
    datasetSize: SIZE,
  });

  return { sessionId, expectedRecentlyViewedIds };
}

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function hasCursorContract(payload, definition) {
  if (!payload || !payload.data || !payload.data.page_info) {
    return false;
  }

  const rows = payload.data[definition.arrayKey];
  const pageInfo = payload.data.page_info;
  return Array.isArray(rows)
    && rows.length === SIZE
    && rows.every(definition.rowContract)
    && pageInfo.current_size === SIZE
    && pageInfo.has_next === true
    && typeof pageInfo.next_cursor === 'string'
    && pageInfo.next_cursor.length > 0;
}

function hasRecentlyViewedContract(payload, expectedIds) {
  if (!payload || !payload.data || !Array.isArray(payload.data.accommodations)) {
    return false;
  }

  const rows = payload.data.accommodations;
  if (payload.data.total_count !== SIZE || rows.length !== SIZE) {
    return false;
  }

  const actualIds = rows.map((row) => row.accommodation_id).sort((a, b) => a - b);
  const sortedExpectedIds = expectedIds.slice().sort((a, b) => a - b);
  return actualIds.length === sortedExpectedIds.length
    && actualIds.every((id, index) => (
      Number.isInteger(id)
        && id > 0
        && id === sortedExpectedIds[index]
    ));
}

function measure(api, data) {
  const definition = API_DEFINITIONS[api];
  const tags = {
    api,
    size: String(SIZE),
    phase: 'measure',
  };
  const response = http.get(
    `${BASE_URL}${definition.path()}`,
    authenticatedParams(data.sessionId, {
      ...tags,
      name: definition.name,
    }),
  );
  const payload = parsePayload(response);
  const contractMatches = api === 'recently_viewed'
    ? hasRecentlyViewedContract(payload, data.expectedRecentlyViewedIds)
    : hasCursorContract(payload, definition);
  const success = response.status === 200 && contractMatches;

  // Exactly one sample is recorded in each benchmark metric for every target response.
  clientDuration.add(response.timings.duration, tags);
  requestSuccess.add(success, tags);
}

export function reviews(data) {
  measure('reviews', data);
}

export function host_accommodations(data) {
  measure('host_accommodations', data);
}

export function guest_reservations(data) {
  measure('guest_reservations', data);
}

export function host_reservations(data) {
  measure('host_reservations', data);
}

export function wishlists(data) {
  measure('wishlists', data);
}

export function wishlist_accommodations(data) {
  measure('wishlist_accommodations', data);
}

export function recently_viewed(data) {
  measure('recently_viewed', data);
}

function parseDurationSeconds(raw) {
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
    throw new Error(`DURATION must be a positive k6 duration, received: ${raw}`);
  }
  return seconds;
}

function metricTags(metricName) {
  const opening = metricName.indexOf('{');
  if (opening < 0 || !metricName.endsWith('}')) {
    return {};
  }

  return metricName.slice(opening + 1, -1).split(',').reduce((tags, entry) => {
    const separator = entry.indexOf(':');
    if (separator > 0) {
      tags[entry.slice(0, separator)] = entry.slice(separator + 1);
    }
    return tags;
  }, {});
}

function findTaggedMetric(data, baseName, requiredTags) {
  const names = Object.keys(data.metrics);
  for (let index = 0; index < names.length; index += 1) {
    const name = names[index];
    if (!name.startsWith(`${baseName}{`)) {
      continue;
    }
    const tags = metricTags(name);
    const matches = Object.keys(requiredTags).every((key) => tags[key] === String(requiredTags[key]));
    if (matches) {
      return data.metrics[name];
    }
  }
  return null;
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

function rateCounts(metric) {
  if (!metric || !metric.values) {
    return { attempted: 0, successful: 0 };
  }
  const successful = Number(metric.values.passes || 0);
  const failed = Number(metric.values.fails || 0);
  return { attempted: successful + failed, successful };
}

export function handleSummary(data) {
  const durationSeconds = parseDurationSeconds(DURATION);
  const aggregateRate = rateCounts(data.metrics.batch_fetch_request_success);
  const aggregateTrend = data.metrics.batch_fetch_client_duration;
  const aggregateDropped = data.metrics.dropped_iterations;
  const samples = ENABLED_APIS.map((api) => {
    const tags = { api, phase: 'measure', size: SIZE };
    const rateMetric = findTaggedMetric(data, 'batch_fetch_request_success', tags);
    const trendMetric = findTaggedMetric(data, 'batch_fetch_client_duration', tags);
    const droppedMetric = findTaggedMetric(data, 'dropped_iterations', { scenario: api });
    const counts = rateCounts(rateMetric);

    return {
      api,
      size: SIZE,
      attempted: counts.attempted,
      successful: counts.successful,
      p95_ms: finiteOrNull(trendMetric && trendMetric.values['p(95)']),
      dropped: Number((droppedMetric && droppedMetric.values.count) || 0),
    };
  });
  const count = aggregateRate.attempted;
  const errorRate = count === 0 ? 1 : (count - aggregateRate.successful) / count;
  const result = {
    duration_seconds: durationSeconds,
    aggregate: {
      count,
      success_count: aggregateRate.successful,
      p95_ms: finiteOrNull(aggregateTrend && aggregateTrend.values['p(95)']),
      error_rate: errorRate,
      rps: count / durationSeconds,
      dropped: Number((aggregateDropped && aggregateDropped.values.count) || 0),
    },
    samples,
  };

  return {
    [__ENV.K6_RESULT_PATH]: JSON.stringify(result),
  };
}

import { benchmarkHeaders } from './benchmark-fixture.js';

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

function metricValues(data, name) {
  return data.metrics?.[name]?.values || {};
}

export function parseRequiredText(raw, name) {
  requireCondition(
    typeof raw === 'string' && raw.trim().length > 0,
    `${name} is required`,
  );
  return raw.trim();
}

export function parseVariant(raw) {
  requireCondition(raw === 'before' || raw === 'after', 'VARIANT must be before or after');
  return raw;
}

export function parsePositiveInteger(raw, name) {
  const value = Number(raw);
  requireCondition(Number.isInteger(value) && value > 0, `${name} must be a positive integer`);
  return value;
}

export function parseDurationSeconds(raw, name = 'DURATION') {
  const compact = typeof raw === 'string' ? raw.replace(/\s+/g, '') : '';
  const expression = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
  const unitSeconds = { ms: 0.001, s: 1, m: 60, h: 3600 };
  let consumed = '';
  let seconds = 0;
  let match;

  while ((match = expression.exec(compact)) !== null) {
    consumed += match[0];
    seconds += Number(match[1]) * unitSeconds[match[2]];
  }

  requireCondition(
    consumed === compact && Number.isFinite(seconds) && seconds > 0,
    `${name} must be a positive k6 duration`,
  );
  return seconds;
}

export function parseIsoDate(raw, name) {
  const value = parseRequiredText(raw, name);
  requireCondition(/^\d{4}-\d{2}-\d{2}$/.test(value), `${name} must use YYYY-MM-DD`);
  const parsed = new Date(`${value}T00:00:00Z`);
  requireCondition(
    !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value,
    `${name} must be a valid date`,
  );
  return value;
}

export function parseReadModelRunConfig(environment, resultStem) {
  requireCondition(
    typeof resultStem === 'string' && /^[a-z0-9-]+$/.test(resultStem),
    'resultStem must contain lowercase letters, numbers, or hyphens',
  );
  const variant = parseVariant(parseRequiredText(environment.VARIANT, 'VARIANT'));
  const benchmarkToken = parseRequiredText(
    environment.BENCHMARK_READ_MODEL_TOKEN,
    'BENCHMARK_READ_MODEL_TOKEN',
  );
  const baseUrl = (environment.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  requireCondition(/^https?:\/\/[^/]+$/.test(baseUrl), 'BASE_URL must be an HTTP origin');
  const rate = parsePositiveInteger(environment.RATE || '5', 'RATE');
  const preAllocatedVUs = parsePositiveInteger(
    environment.PRE_ALLOCATED_VUS || String(Math.max(20, rate * 4)),
    'PRE_ALLOCATED_VUS',
  );
  const maxVUs = parsePositiveInteger(
    environment.MAX_VUS || String(Math.max(preAllocatedVUs, rate * 10)),
    'MAX_VUS',
  );
  const round = parsePositiveInteger(environment.ROUND || '1', 'ROUND');
  const runOrder = parsePositiveInteger(environment.RUN_ORDER || '1', 'RUN_ORDER');
  const datasetLabel = parseRequiredText(environment.DATASET_LABEL, 'DATASET_LABEL');
  const runLabel = environment.RUN_LABEL
    ? parseRequiredText(environment.RUN_LABEL, 'RUN_LABEL')
    : `${datasetLabel}-r${round}-${variant}`;
  const requestTimeout = environment.REQUEST_TIMEOUT || '10s';
  parseDurationSeconds(requestTimeout, 'REQUEST_TIMEOUT');

  return {
    variant,
    benchmarkToken,
    baseUrl,
    rate,
    warmupDuration: environment.WARMUP_DURATION || '30s',
    measureDuration: environment.MEASURE_DURATION || '1m',
    warmupSettleSeconds: Number(environment.WARMUP_SETTLE_SECONDS || 5),
    preAllocatedVUs,
    maxVUs,
    requestTimeout,
    round,
    runOrder,
    resultPath: environment.K6_RESULT_PATH
      || `build/k6/read-model/${resultStem}-${variant}-r${round}.json`,
    metadata: {
      run_label: runLabel,
      dataset_label: datasetLabel,
      app_version: environment.APP_VERSION || 'working-tree',
      app_instance_count: parsePositiveInteger(
        environment.APP_INSTANCE_COUNT || '1',
        'APP_INSTANCE_COUNT',
      ),
      round,
      run_order: runOrder,
    },
  };
}

export function buildReadModelOptions({
  rate,
  warmupDuration,
  measureDuration,
  warmupSettleSeconds,
  preAllocatedVUs,
  maxVUs,
}) {
  const parsedRate = parsePositiveInteger(String(rate), 'RATE');
  const parsedPreAllocatedVUs = parsePositiveInteger(
    String(preAllocatedVUs),
    'PRE_ALLOCATED_VUS',
  );
  const parsedMaxVUs = parsePositiveInteger(String(maxVUs), 'MAX_VUS');
  requireCondition(
    parsedMaxVUs >= parsedPreAllocatedVUs,
    'MAX_VUS must be greater than or equal to PRE_ALLOCATED_VUS',
  );
  requireCondition(
    Number.isInteger(warmupSettleSeconds) && warmupSettleSeconds >= 0,
    'WARMUP_SETTLE_SECONDS must be a non-negative integer',
  );

  const gracefulStopSeconds = 5;
  const warmupSeconds = parseDurationSeconds(warmupDuration, 'WARMUP_DURATION');
  const measureSeconds = parseDurationSeconds(measureDuration, 'MEASURE_DURATION');
  requireCondition(warmupSeconds >= 1, 'WARMUP_DURATION must be at least 1s');
  requireCondition(measureSeconds >= 1, 'MEASURE_DURATION must be at least 1s');
  const scenario = (exec, duration, startTime) => {
    const value = {
      executor: 'constant-arrival-rate',
      exec,
      rate: parsedRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: parsedPreAllocatedVUs,
      maxVUs: parsedMaxVUs,
      gracefulStop: `${gracefulStopSeconds}s`,
    };
    if (startTime) {
      value.startTime = startTime;
    }
    return value;
  };

  return {
    setupTimeout: '1m',
    scenarios: {
      warmup: scenario('warmup', warmupDuration),
      measure: scenario(
        'measure',
        measureDuration,
        `${warmupSeconds + gracefulStopSeconds + warmupSettleSeconds}s`,
      ),
    },
    thresholds: {
      'read_model_request_success{phase:measure}': ['rate==1'],
      'http_req_failed{phase:measure}': ['rate==0'],
      'dropped_iterations{scenario:measure}': ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  };
}

export function buildReadModelPath({
  domain,
  variant,
  accommodationId,
  size,
  from,
  to,
}) {
  const version = parseVariant(variant) === 'before' ? 'v2' : 'v1';
  if (domain === 'review') {
    const id = parsePositiveInteger(String(accommodationId), 'ACCOMMODATION_ID');
    return `/api/${version}/accommodations/${id}/reviews/summary`;
  }
  if (domain === 'wishlist') {
    const pageSize = parsePositiveInteger(String(size), 'PAGE_SIZE');
    requireCondition(pageSize <= 50, 'PAGE_SIZE must not exceed 50');
    return `/api/${version}/members/wishlists?size=${pageSize}`;
  }
  if (domain === 'revenue') {
    const parsedFrom = parseIsoDate(from, 'REVENUE_FROM');
    const parsedTo = parseIsoDate(to, 'REVENUE_TO');
    requireCondition(parsedFrom <= parsedTo, 'REVENUE_FROM must be on or before REVENUE_TO');
    return `/api/${version}/admin/stats/revenue?from=${parsedFrom}&to=${parsedTo}`;
  }
  throw new Error('domain must be review, wishlist, or revenue');
}

export function buildReadModelRequestParams({
  variant,
  benchmarkToken,
  sessionId,
  tags = {},
  timeout,
}) {
  const parsedVariant = parseVariant(variant);
  const params = { tags };
  if (typeof sessionId === 'string' && sessionId.trim()) {
    params.cookies = { SESSION_ID: sessionId.trim() };
  }
  if (timeout) {
    params.timeout = timeout;
  }
  if (parsedVariant === 'before') {
    params.headers = benchmarkHeaders(benchmarkToken);
  }
  return params;
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function isNonNegativeInteger(value) {
  return Number.isInteger(value) && value >= 0;
}

function matchesReviewContract(data, expectedCount) {
  return data.total_count === expectedCount
    && isFiniteNumber(data.average_rating)
    && data.average_rating >= 0
    && data.average_rating <= 5;
}

export function canonicalizeReadModelData(domain, data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('read model data must be an object');
  }

  if (domain === 'review') {
    return {
      total_count: data.total_count,
      average_rating: data.average_rating,
    };
  }
  if (domain === 'wishlist') {
    return {
      wishlists: Array.isArray(data.wishlists)
        ? data.wishlists.map((wishlist) => ({
          id: wishlist.id,
          name: wishlist.name,
          created_at: wishlist.created_at,
          wishlist_item_count: wishlist.wishlist_item_count,
          thumbnail_image_url: wishlist.thumbnail_image_url,
          is_contained: wishlist.is_contained,
          wishlist_accommodation_id: wishlist.wishlist_accommodation_id,
        }))
        : data.wishlists,
      page_info: data.page_info
        ? {
          has_next: data.page_info.has_next,
          next_cursor: data.page_info.next_cursor,
          current_size: data.page_info.current_size,
        }
        : data.page_info,
    };
  }
  if (domain === 'revenue') {
    return {
      from: data.from,
      to: data.to,
      items: Array.isArray(data.items)
        ? data.items.map((item) => ({
          date: item.date,
          gross_amount: item.gross_amount,
          refund_amount: item.refund_amount,
          net_amount: item.net_amount,
          payment_count: item.payment_count,
          refund_count: item.refund_count,
        }))
        : data.items,
    };
  }
  throw new Error('domain must be review, wishlist, or revenue');
}

export function readModelPayloadsEquivalent(domain, leftPayload, rightPayload) {
  if (!leftPayload || leftPayload.success !== true || !leftPayload.data
      || !rightPayload || rightPayload.success !== true || !rightPayload.data) {
    return false;
  }

  try {
    return JSON.stringify(canonicalizeReadModelData(domain, leftPayload.data))
      === JSON.stringify(canonicalizeReadModelData(domain, rightPayload.data));
  } catch (_) {
    return false;
  }
}

function matchesWishlistContract(data, expectedCount) {
  if (!Array.isArray(data.wishlists)
      || data.wishlists.length !== expectedCount
      || !data.page_info
      || data.page_info.current_size !== expectedCount
      || typeof data.page_info.has_next !== 'boolean') {
    return false;
  }

  return data.wishlists.every((wishlist) => (
    Number.isInteger(wishlist.id)
      && wishlist.id > 0
      && typeof wishlist.name === 'string'
      && isNonNegativeInteger(wishlist.wishlist_item_count)
      && (wishlist.thumbnail_image_url === null
        || (typeof wishlist.thumbnail_image_url === 'string'
          && wishlist.thumbnail_image_url.length > 0))
  ));
}

function matchesRevenueItem(item) {
  return typeof item.date === 'string'
    && /^\d{4}-\d{2}-\d{2}$/.test(item.date)
    && isFiniteNumber(item.gross_amount)
    && isFiniteNumber(item.refund_amount)
    && isFiniteNumber(item.net_amount)
    && isNonNegativeInteger(item.payment_count)
    && isNonNegativeInteger(item.refund_count);
}

function matchesRevenueContract(data, variant, expectedCount, from, to) {
  const expectedSource = variant === 'before' ? 'raw' : 'stats';
  return data.from === from
    && data.to === to
    && data.source === expectedSource
    && Array.isArray(data.items)
    && data.items.length === expectedCount
    && data.items.every(matchesRevenueItem);
}

export function matchesReadModelContract({
  domain,
  variant,
  payload,
  expectedCount,
  expectedData,
  expectedDataJson,
  from,
  to,
}) {
  if (!payload || payload.success !== true || !payload.data) {
    return false;
  }
  if (!Number.isInteger(expectedCount) || expectedCount <= 0) {
    return false;
  }

  let matchesStructure = false;
  if (domain === 'review') {
    matchesStructure = matchesReviewContract(payload.data, expectedCount);
  }
  if (domain === 'wishlist') {
    matchesStructure = matchesWishlistContract(payload.data, expectedCount);
  }
  if (domain === 'revenue') {
    matchesStructure = matchesRevenueContract(payload.data, variant, expectedCount, from, to);
  }
  if (!matchesStructure) {
    return false;
  }
  if (expectedData === undefined && expectedDataJson === undefined) {
    return true;
  }

  try {
    const expected = expectedDataJson === undefined
      ? JSON.stringify(expectedData)
      : expectedDataJson;
    return JSON.stringify(canonicalizeReadModelData(domain, payload.data)) === expected;
  } catch (_) {
    return false;
  }
}

export function summarizeReadModelMetrics(data, measureSeconds) {
  const success = metricValues(data, 'read_model_request_success');
  const latency = metricValues(data, 'read_model_client_duration');
  const dropped = metricValues(data, 'dropped_iterations{scenario:measure}');
  const successful = Number(success.passes || 0);
  const failed = Number(success.fails || 0);
  const attempted = successful + failed;

  return {
    requests: {
      attempted,
      successful,
      error_rate: attempted === 0 ? 1 : failed / attempted,
      achieved_rps: attempted / measureSeconds,
      dropped_iterations: Number(dropped.count || 0),
    },
    latency_ms: {
      avg: finiteOrNull(latency.avg),
      min: finiteOrNull(latency.min),
      median: finiteOrNull(latency.med),
      p90: finiteOrNull(latency['p(90)']),
      p95: finiteOrNull(latency['p(95)']),
      p99: finiteOrNull(latency['p(99)']),
      max: finiteOrNull(latency.max),
    },
  };
}

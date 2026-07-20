const DATASET_VERSION = 'coupon-issuance-v1';

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

export function parseRequiredText(raw, name) {
  requireCondition(
    typeof raw === 'string' && raw.trim().length > 0,
    `${name} is required`,
  );
  return raw.trim();
}

export function parseCouponSessionFixture(raw) {
  let fixture;
  try {
    fixture = JSON.parse(raw);
  } catch (_) {
    throw new Error('SESSION_FIXTURE must contain valid JSON');
  }

  requireCondition(
    fixture !== null && typeof fixture === 'object' && !Array.isArray(fixture),
    'SESSION_FIXTURE root must be an object',
  );
  requireCondition(
    fixture.datasetVersion === DATASET_VERSION,
    `SESSION_FIXTURE.datasetVersion must equal ${DATASET_VERSION}`,
  );
  requireCondition(
    Array.isArray(fixture.sessions) && fixture.sessions.length > 0,
    'SESSION_FIXTURE.sessions must be a non-empty array',
  );

  const sessions = fixture.sessions.map((sessionId, index) => {
    requireCondition(
      typeof sessionId === 'string' && sessionId.trim().length > 0,
      `SESSION_FIXTURE.sessions[${index}] must be a non-empty string`,
    );
    return sessionId.trim();
  });
  requireCondition(
    new Set(sessions).size === sessions.length,
    'SESSION_FIXTURE.sessions must contain unique session IDs',
  );

  return sessions;
}

export function parseVariant(raw) {
  requireCondition(raw === 'lock' || raw === 'lua', 'VARIANT must be lock or lua');
  return raw;
}

export function parsePhase(raw) {
  requireCondition(raw === 'warmup' || raw === 'measure', 'PHASE must be warmup or measure');
  return raw;
}

export function parsePositiveInteger(raw, name) {
  const value = Number(raw);
  requireCondition(Number.isInteger(value) && value > 0, `${name} must be a positive integer`);
  return value;
}

export function buildCouponIssueTarget(variant, couponId, benchmarkToken) {
  const parsedVariant = parseVariant(variant);
  const parsedCouponId = parsePositiveInteger(couponId, 'COUPON_ID');

  if (parsedVariant === 'lua') {
    return {
      path: `/api/v1/coupons/${parsedCouponId}/issue`,
      metricName: 'POST /api/v1/coupons/{couponId}/issue',
      headers: {},
    };
  }

  const token = parseRequiredText(benchmarkToken, 'BENCHMARK_READ_MODEL_TOKEN');
  return {
    path: `/api/v2/coupons/${parsedCouponId}/issue`,
    metricName: 'POST /api/v2/coupons/{couponId}/issue',
    headers: { 'X-Benchmark-Token': token },
  };
}

export function parseDurationSeconds(raw) {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(raw || '');
  requireCondition(Boolean(match), 'DURATION must use one unit, for example 30s or 2m');

  const value = Number(match[1]);
  const unitSeconds = {
    ms: 0.001,
    s: 1,
    m: 60,
    h: 3600,
  }[match[2]];
  const seconds = value * unitSeconds;
  requireCondition(Number.isFinite(seconds) && seconds > 0, 'DURATION must be greater than zero');
  return seconds;
}

export function requireSessionCapacity(sessions, rate, durationSeconds) {
  const required = Math.ceil(rate * durationSeconds) + 1;
  requireCondition(
    sessions.length >= required,
    `SESSION_FIXTURE needs at least ${required} unique sessions for this run`,
  );
  return required;
}

export function classifyCouponIssueResponse(status, errorCode) {
  if (status === 201) {
    return 'success';
  }

  const outcomes = {
    '409:CP002': 'sold_out',
    '409:CP003': 'duplicate',
    '409:CP005': 'not_issuable',
    '503:CP011': 'unprepared',
    '503:CP012': 'lock_timeout',
  };
  return outcomes[`${status}:${errorCode}`] || 'unexpected';
}

function metricValue(data, name, value, fallback = 0) {
  return data.metrics?.[name]?.values?.[value] ?? fallback;
}

export function summarizeCouponBenchmarkMetrics(data) {
  return {
    requestCount: metricValue(data, 'http_reqs', 'count'),
    requestRate: metricValue(data, 'http_reqs', 'rate'),
    successRate: metricValue(data, 'coupon_issue_success_total', 'rate'),
    duration: data.metrics?.coupon_issue_duration?.values || {},
    successDuration: data.metrics?.coupon_issue_success_duration?.values || {},
    outcomes: {
      success: metricValue(data, 'coupon_issue_success_total', 'count'),
      soldOut: metricValue(data, 'coupon_issue_sold_out_total', 'count'),
      duplicate: metricValue(data, 'coupon_issue_duplicate_total', 'count'),
      notIssuable: metricValue(data, 'coupon_issue_not_issuable_total', 'count'),
      unprepared: metricValue(data, 'coupon_issue_unprepared_total', 'count'),
      lockTimeout: metricValue(data, 'coupon_issue_lock_timeout_total', 'count'),
      unexpected: metricValue(data, 'coupon_issue_unexpected_total', 'count'),
    },
    droppedIterations: metricValue(data, 'dropped_iterations', 'count'),
  };
}

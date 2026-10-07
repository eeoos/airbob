// k6와 Node 테스트가 함께 사용하는 설정·응답 검증·결과 계산.
export const TARGETS = ['accommodation-detail', 'wishlist-accommodations', 'recently-viewed'];
export const METRICS = {
  started: 'review_summary_started',
  completed: 'review_summary_completed',
  success: 'review_summary_success',
  duration: 'review_summary_duration',
  completionTime: 'review_summary_completion_ms',
  measureStart: 'review_summary_measure_start_ms',
};

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function integer(raw, name, minimum = 1, maximum = Number.MAX_SAFE_INTEGER) {
  const value = Number(raw);
  requireCondition(typeof raw === 'string' && /^\d+$/.test(raw)
    && Number.isSafeInteger(value) && value >= minimum && value <= maximum,
  `${name}: ${minimum}~${maximum} 범위의 정수가 필요합니다.`);
  return value;
}

export function durationSeconds(raw, name) {
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(raw || '');
  const seconds = match ? Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0) : 0;
  requireCondition(Number.isSafeInteger(seconds) && seconds > 0, `${name}: 30s, 1m처럼 초 단위 이상의 시간을 지정하세요.`);
  return seconds;
}

export function parseConfig(env) {
  requireCondition(TARGETS.includes(env.TARGET), `TARGET은 ${TARGETS.join(', ')} 중 하나입니다.`);
  requireCondition(['before', 'after'].includes(env.VARIANT), 'VARIANT는 before 또는 after입니다.');
  const baseUrl = (env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
  const origin = /^https?:\/\/(\[[0-9a-fA-F:]+\]|[^\s/:?#@]+)(?::([1-9]\d{0,4}))?$/.exec(baseUrl);
  requireCondition(origin && (!origin[2] || Number(origin[2]) <= 65535), 'BASE_URL에는 경로·인증정보가 없는 HTTP(S) origin을 지정하세요.');
  const targetIp = env.TARGET_IP || null;
  requireCondition(!targetIp || /^(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3}$/.test(targetIp)
    && targetIp.split('.').every((part) => Number(part) <= 255), 'TARGET_IP에는 IPv4 주소를 지정하세요.');
  const token = env.BENCHMARK_READ_MODEL_TOKEN;
  requireCondition(typeof token === 'string' && /^[\x21-\x7e]+$/.test(token), 'BENCHMARK_READ_MODEL_TOKEN이 필요합니다.');
  const sessionId = env.BENCHMARK_SESSION_ID || null;
  const email = env.BENCHMARK_EMAIL || null;
  const password = env.BENCHMARK_PASSWORD || null;
  requireCondition(!sessionId || /^[A-Za-z0-9._~-]{16,512}$/.test(sessionId), 'BENCHMARK_SESSION_ID 형식이 올바르지 않습니다.');
  requireCondition(Boolean(email) === Boolean(password), 'BENCHMARK_EMAIL과 BENCHMARK_PASSWORD를 함께 지정하세요.');
  requireCondition(!(sessionId && email), '세션 또는 이메일·비밀번호 중 한 가지 로그인 방식만 지정하세요.');
  requireCondition(env.TARGET === 'accommodation-detail' || sessionId || email, '회원 목록 조회에는 로그인 정보가 필요합니다.');
  const targetId = env.TARGET === 'accommodation-detail'
    ? integer(env.ACCOMMODATION_ID, 'ACCOMMODATION_ID')
    : env.TARGET === 'wishlist-accommodations' ? integer(env.WISHLIST_ID, 'WISHLIST_ID') : null;
  const pageSize = env.TARGET === 'wishlist-accommodations' ? integer(env.PAGE_SIZE || '20', 'PAGE_SIZE', 1, 50) : null;
  const expectedRows = env.TARGET === 'accommodation-detail' ? 1
    : integer(env.EXPECTED_ROWS, 'EXPECTED_ROWS', 0, pageSize || 100);
  const expectedReviewCount = env.EXPECTED_REVIEW_COUNT === undefined || env.EXPECTED_REVIEW_COUNT === ''
    ? null : integer(env.EXPECTED_REVIEW_COUNT, 'EXPECTED_REVIEW_COUNT', 0);
  const cursor = env.TARGET === 'wishlist-accommodations' ? env.CURSOR || null : null;
  const rate = integer(env.RATE || '10', 'RATE');
  const warmupSeconds = durationSeconds(env.WARMUP_DURATION || '30s', 'WARMUP_DURATION');
  const measureSeconds = durationSeconds(env.MEASURE_DURATION || '1m', 'MEASURE_DURATION');
  const timeoutSeconds = durationSeconds(env.REQUEST_TIMEOUT || '5s', 'REQUEST_TIMEOUT');
  const settleSeconds = integer(env.SETTLE_SECONDS || '2', 'SETTLE_SECONDS', 0);
  const preAllocatedVUs = integer(env.PRE_ALLOCATED_VUS || String(Math.max(20, rate * 2)), 'PRE_ALLOCATED_VUS');
  const maxVUs = integer(env.MAX_VUS || String(preAllocatedVUs), 'MAX_VUS', preAllocatedVUs);
  requireCondition(Number.isSafeInteger(rate * measureSeconds), 'RATE × MEASURE_DURATION이 너무 큽니다.');
  requireCondition(typeof env.RESULT_PATH === 'string' && env.RESULT_PATH.endsWith('.json')
    && !/[\r\n\0]/.test(env.RESULT_PATH), 'RESULT_PATH에 결과 JSON 경로를 지정하세요.');
  return {
    target: env.TARGET, variant: env.VARIANT, baseUrl, hostname: origin[1], targetIp, token, sessionId, email, password,
    targetId, pageSize, expectedRows, expectedReviewCount, cursor, rate, warmupSeconds, measureSeconds,
    timeoutSeconds, settleSeconds, preAllocatedVUs, maxVUs, resultPath: env.RESULT_PATH,
    datasetLabel: env.DATASET_LABEL || 'unspecified', appRevision: env.APP_REVISION || 'unspecified',
  };
}

export function targetPath(config, variant) {
  requireCondition(['before', 'after'].includes(variant), '조회 버전이 올바르지 않습니다.');
  if (config.target === 'accommodation-detail') {
    // 리뷰 반정규화 After도 캐시를 사용하지 않는 V2 상세다.
    return `/api/v2/accommodations/${config.targetId}${variant === 'before' ? '/review-summary-before' : ''}`;
  }
  if (config.target === 'wishlist-accommodations') {
    const cursor = config.cursor ? `&cursor=${encodeURIComponent(config.cursor)}` : '';
    return `/api/${variant === 'before' ? 'v2' : 'v1'}/members/wishlists/accommodations/${config.targetId}`
      + `${variant === 'before' ? '/review-summary-before' : ''}?size=${config.pageSize}${cursor}`;
  }
  return variant === 'before' ? '/api/v2/members/recently-viewed/review-summary-before' : '/api/v1/members/recently-viewed';
}

export function buildOptions(config) {
  const gracefulSeconds = config.timeoutSeconds + 1;
  const scenario = (exec, duration, startTime) => ({
    executor: 'constant-arrival-rate', exec, rate: config.rate, timeUnit: '1s',
    duration: `${duration}s`, startTime: `${startTime}s`,
    preAllocatedVUs: config.preAllocatedVUs, maxVUs: config.maxVUs,
    gracefulStop: `${gracefulSeconds}s`,
  });
  return {
    ...(config.targetIp ? { hosts: { [config.hostname]: config.targetIp } } : {}),
    setupTimeout: `${config.timeoutSeconds * 3 + 10}s`,
    scenarios: {
      warmup: scenario('warmup', config.warmupSeconds, 0),
      measure: scenario('measure', config.measureSeconds, config.warmupSeconds + gracefulSeconds + config.settleSeconds),
    },
    thresholds: {
      [`${METRICS.success}{phase:measure}`]: ['rate==1'],
      [`${METRICS.completed}{phase:measure}`]: [`count>=${config.rate * config.measureSeconds}`],
      'http_req_failed{phase:measure}': ['rate==0'],
      'dropped_iterations{scenario:measure}': ['count==0'],
    },
    summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    tags: { target: config.target, variant: config.variant },
  };
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function id(value) { return Number.isSafeInteger(value) && value > 0; }
function reviewSummary(value) {
  return object(value) && Number.isSafeInteger(value.total_count) && value.total_count >= 0
    && Number.isFinite(value.average_rating) && value.average_rating >= 0 && value.average_rating <= 5
    && (value.total_count !== 0 || value.average_rating === 0);
}

export function matchesContract(config, payload) {
  const data = payload?.data;
  if (payload?.success !== true || !object(data)) return false;
  if (config.target === 'accommodation-detail') {
    return data.id === config.targetId && reviewSummary(data.review_summary)
      && (config.expectedReviewCount === null || data.review_summary.total_count === config.expectedReviewCount)
      && Array.isArray(data.amenities) && Array.isArray(data.images)
      && object(data.address_summary) && object(data.host) && object(data.policy)
      && typeof data.is_in_wishlist === 'boolean';
  }
  const wishlist = config.target === 'wishlist-accommodations';
  const rows = wishlist ? data.wishlist_accommodations : data.accommodations;
  if (!Array.isArray(rows) || rows.length !== config.expectedRows) return false;
  const ids = new Set();
  if (!rows.every((row) => {
    if (!object(row)) return false;
    const rowId = wishlist ? row.wishlist_accommodation_id : row.accommodation_id;
    if (!id(rowId) || ids.has(rowId)) return false;
    ids.add(rowId);
    return reviewSummary(row.review_summary) && object(row.address_summary)
      && typeof row.is_in_wishlist === 'boolean'
      && (wishlist ? id(row.accommodation?.id) && row.is_in_wishlist
        : typeof row.viewed_at === 'string' && Number.isFinite(Date.parse(row.viewed_at)));
  })) return false;
  if (config.expectedReviewCount !== null
    && rows.reduce((sum, row) => sum + row.review_summary.total_count, 0) !== config.expectedReviewCount) return false;
  if (!wishlist) return data.total_count === rows.length;
  const page = data.page_info;
  return typeof data.wishlist_name === 'string' && object(page) && page.current_size === rows.length
    && typeof page.has_next === 'boolean'
    && (page.has_next ? typeof page.next_cursor === 'string' && page.next_cursor.length > 0 : page.next_cursor === null);
}

export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (object(value)) return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}

export function canonicalData(target, data) {
  // 편의시설은 조회 순서를 보장하지 않는다. 이미지·페이지·최근 본 목록 순서는 그대로 비교한다.
  if (target === 'accommodation-detail') {
    return canonicalJson({ ...data, amenities: data.amenities.map(canonicalJson).sort() });
  }
  return canonicalJson(data);
}

export function summarize(config, data) {
  const metrics = data.metrics || {};
  const values = (name) => metrics[name]?.values || {};
  const success = values(METRICS.success);
  const started = values(METRICS.started).count || 0;
  const completed = values(METRICS.completed).count || 0;
  const failed = success.fails || 0;
  const dropped = values('dropped_iterations{scenario:measure}').count || 0;
  const duration = values(METRICS.duration);
  const preflight = data.setup_data;
  const reasons = [];
  if (preflight?.verified !== true) reasons.push('preflight-failed');
  if (completed < config.rate * config.measureSeconds) reasons.push('insufficient-samples');
  if (started !== completed) reasons.push('interrupted-requests');
  if (failed > 0 || (success.passes || 0) !== completed) reasons.push('response-errors');
  if (dropped > 0) reasons.push('dropped-iterations');
  const latency = { p50: duration.med, p95: duration['p(95)'], p99: duration['p(99)'], max: duration.max };
  if (!Object.values(latency).every(Number.isFinite)) reasons.push('missing-latency');
  const windowSeconds = Math.max(config.measureSeconds, (values(METRICS.completionTime).max || 0) / 1000);
  const startedAtMs = values(METRICS.measureStart).min;
  // setup_data에 있는 세션 및 원본 응답은 결과 파일에 기록하지 않는다.
  return {
    schemaVersion: 1, target: config.target, variant: config.variant,
    datasetLabel: config.datasetLabel, appRevision: config.appRevision,
    baseUrl: config.baseUrl, targetIp: config.targetIp, authenticated: Boolean(config.sessionId || config.email),
    parameters: { targetId: config.targetId, pageSize: config.pageSize, cursor: config.cursor,
      expectedRows: config.expectedRows, expectedReviewCount: config.expectedReviewCount },
    load: { rate: config.rate, warmupSeconds: config.warmupSeconds, measureSeconds: config.measureSeconds,
      timeoutSeconds: config.timeoutSeconds, settleSeconds: config.settleSeconds,
      preAllocatedVUs: config.preAllocatedVUs, maxVUs: config.maxVUs },
    responseHash: preflight?.responseHash || null,
    valid: reasons.length === 0, reasons,
    measurement: { started, completed, successful: success.passes || 0, failed, dropped,
      startedAt: Number.isFinite(startedAtMs) ? new Date(startedAtMs).toISOString() : null,
      finishedAt: Number.isFinite(startedAtMs) ? new Date(startedAtMs + windowSeconds * 1000).toISOString() : null,
      windowSeconds, achievedRps: completed / windowSeconds, errorRate: completed ? failed / completed : null,
      latencyMs: Object.fromEntries(Object.entries(latency).map(([key, value]) => [key, Number.isFinite(value) ? value : null])) },
  };
}

export function comparePair(before, after) {
  requireCondition(before.valid && after.valid, '실패하거나 요청이 누락된 실행은 성능 개선 비교에서 제외합니다.');
  requireCondition(before.variant === 'before' && after.variant === 'after', '전후 결과의 버전이 올바르지 않습니다.');
  for (const key of ['target', 'datasetLabel', 'appRevision', 'baseUrl', 'targetIp', 'authenticated', 'parameters', 'load', 'responseHash']) {
    requireCondition(canonicalJson(before[key]) === canonicalJson(after[key]), `전후 결과의 ${key}가 다릅니다.`);
  }
  const improvement = {};
  for (const percentile of ['p50', 'p95', 'p99']) {
    const a = before.measurement.latencyMs[percentile];
    const b = after.measurement.latencyMs[percentile];
    improvement[percentile] = a > 0 ? (a - b) / a * 100 : null;
  }
  return { before: before.measurement, after: after.measurement, latencyReductionPercent: improvement };
}

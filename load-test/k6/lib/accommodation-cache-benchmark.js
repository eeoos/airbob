import crypto from 'k6/crypto';

export const CACHE_BENCHMARK_METRICS = Object.freeze({
  completed: 'cache_benchmark_completed_samples',
  success: 'cache_benchmark_request_success',
  duration: 'cache_benchmark_client_duration',
});

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requiredText(raw, name) {
  requireCondition(
    typeof raw === 'string' && raw.length > 0 && raw === raw.trim(),
    `${name} is required and must not have surrounding whitespace`,
  );
  return raw;
}

function positiveInteger(raw, name) {
  requireCondition(typeof raw === 'string' && /^[1-9]\d*$/.test(raw), `${name} must be a positive integer`);
  const value = Number(raw);
  requireCondition(Number.isSafeInteger(value), `${name} must be a safe integer`);
  return value;
}

function durationSeconds(raw) {
  // Whole seconds keep the required sample count exact; compound durations are supported.
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(raw);
  const seconds = match
    ? Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0)
    : 0;
  requireCondition(Number.isSafeInteger(seconds) && seconds > 0,
    'DURATION must be a positive whole-second duration, for example 30s or 1m30s');
  return seconds;
}

function httpOrigin(raw) {
  requiredText(raw, 'BASE_URL');
  const value = raw.replace(/\/$/, '');
  const match = /^https?:\/\/(?:\[[0-9a-fA-F:]+\]|[^\s/:?#@]+)(?::([1-9]\d{0,4}))?$/.exec(value);
  requireCondition(match !== null && (!match[1] || Number(match[1]) <= 65535),
    'BASE_URL must be one HTTP origin without credentials, path, query, or fragment');
  return value;
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  if (isObject(value)) {
    return `{${Object.keys(value).sort().map(
      (key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`,
    ).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function canonicalAccommodationData(data) {
  requireCondition(isObject(data), 'accommodation data must be an object');
  // The DB does not promise amenity order. Other arrays (including images) retain their order.
  return `{${Object.keys(data).sort().map((key) => {
    const value = key === 'amenities' && Array.isArray(data[key])
      ? `[${data[key].map(canonicalJson).sort().join(',')}]`
      : canonicalJson(data[key]);
    return `${JSON.stringify(key)}:${value}`;
  }).join(',')}}`;
}

export function parseCacheBenchmarkFixture(raw) {
  let fixture;
  try {
    fixture = JSON.parse(raw);
  } catch (_) {
    throw new Error('CACHE_BENCHMARK_FIXTURE must contain JSON');
  }
  requireCondition(isObject(fixture) && fixture.schemaVersion === 1,
    'CACHE_BENCHMARK_FIXTURE must use schemaVersion 1');
  requireCondition(
    typeof fixture.datasetId === 'string'
      && /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(fixture.datasetId),
    'fixture datasetId must be a label of up to 128 characters',
  );
  requireCondition(
    Array.isArray(fixture.accommodations)
      && fixture.accommodations.length > 0
      && fixture.accommodations.length <= 1000,
    'fixture must contain between 1 and 1000 accommodations',
  );
  const ids = new Set();
  const accommodations = fixture.accommodations.map((entry) => {
    requireCondition(isObject(entry) && Number.isSafeInteger(entry.id) && entry.id > 0,
      'fixture accommodation ids must be positive safe integers');
    requireCondition(!ids.has(entry.id), 'fixture accommodation ids must be unique');
    requireCondition(isObject(entry.data) && entry.data.id === entry.id,
      'fixture data.id must match its accommodation id');
    ids.add(entry.id);
    return { id: entry.id, canonicalData: canonicalAccommodationData(entry.data) };
  });
  return {
    datasetId: fixture.datasetId,
    accommodations,
    sha256: crypto.sha256(raw, 'hex'),
  };
}

export function parseCacheBenchmarkConfig(environment, fixtureRaw) {
  const baseUrl = httpOrigin(environment.BASE_URL);
  const token = requiredText(environment.BENCHMARK_READ_MODEL_TOKEN, 'BENCHMARK_READ_MODEL_TOKEN');
  requireCondition(/^[\x21-\x7e]+$/.test(token),
    'BENCHMARK_READ_MODEL_TOKEN must contain only visible ASCII characters');
  const fixturePath = requiredText(environment.CACHE_BENCHMARK_FIXTURE, 'CACHE_BENCHMARK_FIXTURE');
  requireCondition(fixturePath.startsWith('/') && !/[\r\n\0]/.test(fixturePath),
    'CACHE_BENCHMARK_FIXTURE must be an absolute file path');
  const fixture = parseCacheBenchmarkFixture(fixtureRaw);
  const variant = environment.VARIANT;
  requireCondition(variant === 'before' || variant === 'after', 'VARIANT must be before or after');
  const mode = environment.MODE;
  requireCondition(mode === 'warmup' || mode === 'measure', 'MODE must be warmup or measure');
  const distribution = environment.DISTRIBUTION;
  requireCondition(['same-key', 'uniform', 'hotset-80-20'].includes(distribution),
    'DISTRIBUTION must be same-key, uniform, or hotset-80-20');
  requireCondition(distribution !== 'hotset-80-20' || fixture.accommodations.length >= 5,
    'hotset-80-20 requires at least 5 fixture accommodations');
  const rate = positiveInteger(environment.RATE, 'RATE');
  const duration = requiredText(environment.DURATION, 'DURATION');
  const seconds = durationSeconds(duration);
  const minimumCompletedSamples = rate * seconds;
  requireCondition(Number.isSafeInteger(minimumCompletedSamples), 'RATE * DURATION is too large');
  const preAllocatedVUs = positiveInteger(environment.PRE_ALLOCATED_VUS
    ?? String(Math.max(10, Math.min(200, Math.ceil(rate * 0.25)))), 'PRE_ALLOCATED_VUS');
  const maxVUs = positiveInteger(environment.MAX_VUS
    ?? String(Math.max(preAllocatedVUs, Math.min(1000, preAllocatedVUs * 4))), 'MAX_VUS');
  requireCondition(maxVUs >= preAllocatedVUs, 'MAX_VUS must be at least PRE_ALLOCATED_VUS');
  const runLabel = requiredText(environment.RUN_LABEL, 'RUN_LABEL');
  requireCondition(/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(runLabel),
    'RUN_LABEL must be a label of up to 128 characters');
  const appCommit = requiredText(environment.APP_COMMIT, 'APP_COMMIT');
  requireCondition(/^[0-9a-f]{40}$/.test(appCommit), 'APP_COMMIT must be one full Git commit');
  let resultPath = null;
  if (mode === 'measure') {
    resultPath = requiredText(environment.RESULT_PATH, 'RESULT_PATH');
    requireCondition(!/[\r\n\0]/.test(resultPath)
      && resultPath.endsWith('.json') && resultPath !== fixturePath,
    'RESULT_PATH must be a JSON file distinct from CACHE_BENCHMARK_FIXTURE');
  }
  const version = variant === 'before' ? 'v2' : 'v1';
  return {
    baseUrl,
    token,
    fixture,
    variant,
    mode,
    distribution,
    rate,
    duration,
    durationSeconds: seconds,
    minimumCompletedSamples,
    preAllocatedVUs,
    maxVUs,
    runLabel,
    appCommit,
    resultPath,
    endpointTemplate: `/api/${version}/accommodations/{id}`,
    hotKeys: distribution === 'hotset-80-20' ? Math.floor(fixture.accommodations.length / 5) : 0,
  };
}

export function selectCacheBenchmarkAccommodation(config, iteration) {
  requireCondition(Number.isSafeInteger(iteration) && iteration >= 0,
    'iteration must be a nonnegative safe integer');
  const entries = config.fixture.accommodations;
  if (config.distribution === 'same-key') {
    return entries[0];
  }
  if (config.distribution === 'uniform') {
    return entries[iteration % entries.length];
  }
  // floor(N / 5) hot keys, in fixture order; 8 hot and 2 cold in each block of ten.
  // Each partition is round-robin, making before and after request identical sequences.
  const slot = iteration % 10;
  const cycle = Math.floor(iteration / 10);
  const index = slot < 8
    ? ((cycle * 8) + slot) % config.hotKeys
    : config.hotKeys + (((cycle * 2) + slot - 8) % (entries.length - config.hotKeys));
  return entries[index];
}

export function buildCacheBenchmarkRequest(config, accommodationId) {
  requireCondition(Number.isSafeInteger(accommodationId) && accommodationId > 0,
    'accommodation id must be a positive safe integer');
  return {
    url: `${config.baseUrl}${config.endpointTemplate.replace('{id}', String(accommodationId))}`,
    params: {
      headers: { 'X-Benchmark-Token': config.token },
      // Redirects would turn one iteration into multiple HTTP requests.
      redirects: 0,
      timeout: '5s',
      tags: {
        name: `GET ${config.endpointTemplate}`,
        phase: config.mode,
        variant: config.variant,
        distribution: config.distribution,
      },
    },
  };
}

export function matchesCacheBenchmarkResponse(status, payload, expected) {
  return status === 200
    && payload?.success === true
    && isObject(payload.data)
    && payload.data.id === expected.id
    && canonicalAccommodationData(payload.data) === expected.canonicalData;
}

export function buildCacheBenchmarkOptions(config) {
  return {
    scenarios: {
      [config.mode]: {
        executor: 'constant-arrival-rate',
        rate: config.rate,
        timeUnit: '1s',
        duration: config.duration,
        preAllocatedVUs: config.preAllocatedVUs,
        maxVUs: config.maxVUs,
        gracefulStop: '10s',
      },
    },
    noCookiesReset: false,
    maxRedirects: 0,
    thresholds: {
      [CACHE_BENCHMARK_METRICS.completed]: [`count>=${config.minimumCompletedSamples}`],
      [CACHE_BENCHMARK_METRICS.success]: ['rate==1'],
      http_req_failed: ['rate==0'],
      dropped_iterations: ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  };
}

export function summarizeCacheBenchmark(data, config) {
  requireCondition(config.mode === 'measure', 'only measure can produce a comparison artifact');
  const values = (name) => data.metrics?.[name]?.values || {};
  const completed = Number(values(CACHE_BENCHMARK_METRICS.completed).count || 0);
  const success = values(CACHE_BENCHMARK_METRICS.success);
  const successful = Number(success.passes || 0);
  const failed = Number(success.fails || 0);
  const dropped = Number(values('dropped_iterations').count || 0);
  const latency = values(CACHE_BENCHMARK_METRICS.duration);
  const reasons = [];
  if (failed > 0 || Number(values('http_req_failed').rate || 0) > 0) {
    reasons.push('request-errors');
  }
  if (dropped > 0) {
    reasons.push('dropped-iterations');
  }
  if (completed < config.minimumCompletedSamples) {
    reasons.push('minimum-samples-not-met');
  }
  if (completed !== successful + failed) {
    reasons.push('inconsistent-sample-counts');
  }
  if (![latency.med, latency['p(95)'], latency['p(99)']].every(Number.isFinite)) {
    reasons.push('missing-latency-samples');
  }
  const finiteOrNull = (value) => Number.isFinite(value) ? value : null;
  return {
    schemaVersion: 1,
    metadata: {
      generatedAt: new Date().toISOString(),
      runLabel: config.runLabel,
      phase: config.mode,
      variant: config.variant,
      distribution: config.distribution,
      endpointTemplate: config.endpointTemplate,
      datasetId: config.fixture.datasetId,
      fixtureSha256: config.fixture.sha256,
      appCommit: config.appCommit,
      authentication: 'anonymous',
      totalKeys: config.fixture.accommodations.length,
      hotKeys: config.hotKeys,
    },
    load: {
      configuredRatePerSecond: config.rate,
      duration: config.duration,
      durationSeconds: config.durationSeconds,
      preAllocatedVUs: config.preAllocatedVUs,
      maxVUs: config.maxVUs,
      iterations: { completed, successful, minimumRequired: config.minimumCompletedSamples, dropped },
      achievedRps: completed / config.durationSeconds,
    },
    performance: {
      errorRate: completed === 0 ? 1 : failed / completed,
      latencyMs: {
        avg: finiteOrNull(latency.avg),
        min: finiteOrNull(latency.min),
        p50: finiteOrNull(latency.med),
        p95: finiteOrNull(latency['p(95)']),
        p99: finiteOrNull(latency['p(99)']),
        max: finiteOrNull(latency.max),
      },
    },
    validity: { status: reasons.length === 0 ? 'valid' : 'invalid', reasons },
  };
}

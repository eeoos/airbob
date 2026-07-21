export const ARTIFACT_SCHEMA_VERSION = 'bulk-write-benchmark-v1';
export const BULK_WRITE_ENDPOINT = '/api/v2/admin/benchmarks/bulk-write/wishlist-delete';
export const BULK_WRITE_REQUEST_NAME = (
  'POST /api/v2/admin/benchmarks/bulk-write/wishlist-delete'
);
export const BULK_WRITE_CANDIDATE = 'WISHLIST_DELETE';
export const BULK_WRITE_OPERATION_NAME = 'wishlist-delete-before';

const SQL_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'OTHER', 'TOTAL'];
const DATA_FIELDS = [
  'candidate',
  'variant',
  'dataset_size',
  'expected_rows',
  'verified_rows',
  'verification_succeeded',
  'target_wishlist_deleted',
  'target_memberships_deleted',
  'target_denormalized_state_preserved',
  'control_wishlist_preserved',
  'control_membership_preserved',
  'accommodations_preserved',
  'operation',
];
const OPERATION_FIELDS = [
  'operation_name',
  'outcome',
  'server_operation_nanos',
  'server_operation_ms',
  'hibernate_statements_by_type',
  'jdbc_batch_calls',
  'jdbc_submitted_rows',
  'jdbc_configured_batch_size',
  'jdbc_affected_rows',
];
const VERIFICATION_FIELDS = [
  'verification_succeeded',
  'target_wishlist_deleted',
  'target_memberships_deleted',
  'target_denormalized_state_preserved',
  'control_wishlist_preserved',
  'control_membership_preserved',
  'accommodations_preserved',
];
export const BULK_WRITE_HIBERNATE_METRICS = {
  SELECT: 'bulk_write_hibernate_select_statements',
  INSERT: 'bulk_write_hibernate_insert_statements',
  UPDATE: 'bulk_write_hibernate_update_statements',
  DELETE: 'bulk_write_hibernate_delete_statements',
  OTHER: 'bulk_write_hibernate_other_statements',
  TOTAL: 'bulk_write_hibernate_total_statements',
};

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  if (!isObject(value)) {
    return false;
  }
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  return actual.length === expected.length
    && actual.every((key, index) => key === expected[index]);
}

function parseCanonicalInteger(raw, name, minimum, maximum) {
  const value = typeof raw === 'number' ? String(raw) : raw;
  requireCondition(
    typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value),
    `${name} must be a canonical base-10 integer`,
  );
  const parsed = Number(value);
  requireCondition(
    Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum,
    `${name} must be between ${minimum} and ${maximum}`,
  );
  return parsed;
}

function parseRequiredPublicText(raw, name, fallback) {
  const source = raw === undefined ? fallback : raw;
  requireCondition(
    typeof source === 'string' && source.trim().length > 0,
    `${name} is required`,
  );
  return source.trim();
}

function parseStrictBoolean(raw, name, fallback) {
  const source = raw === undefined ? fallback : raw;
  requireCondition(source === 'true' || source === 'false', `${name} must be true or false`);
  return source === 'true';
}

export function parseSafeBaseUrl(raw) {
  requireCondition(typeof raw === 'string' && raw.length > 0, 'BASE_URL is required');
  requireCondition(raw === raw.trim(), 'BASE_URL must not contain surrounding whitespace');
  const match = /^(https?):\/\/([^/?#]+)\/?$/i.exec(raw);
  requireCondition(match !== null, 'BASE_URL must be an HTTP origin without a path, query, or fragment');

  const scheme = match[1].toLowerCase();
  const authority = match[2];
  requireCondition(!authority.includes('@'), 'BASE_URL must not contain credentials');

  let hostname;
  let port;
  if (authority.startsWith('[')) {
    const ipv6 = /^(\[[0-9a-fA-F:.]+\])(?::(\d+))?$/.exec(authority);
    requireCondition(ipv6 !== null, 'BASE_URL must contain a valid bracketed IPv6 host');
    hostname = ipv6[1].toLowerCase();
    port = ipv6[2];
  } else {
    const host = /^([a-zA-Z0-9.-]+)(?::(\d+))?$/.exec(authority);
    requireCondition(host !== null, 'BASE_URL must contain a valid host');
    hostname = host[1].toLowerCase();
    port = host[2];
  }

  if (port !== undefined) {
    const portNumber = Number(port);
    requireCondition(
      Number.isInteger(portNumber) && portNumber >= 1 && portNumber <= 65535,
      'BASE_URL port must be between 1 and 65535',
    );
  }
  if (scheme === 'http') {
    requireCondition(
      hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]',
      'HTTP BASE_URL is allowed only for exact loopback hosts',
    );
  }

  const normalizedPort = (scheme === 'http' && port === '80')
    || (scheme === 'https' && port === '443')
    ? ''
    : (port === undefined ? '' : `:${Number(port)}`);
  return `${scheme}://${hostname}${normalizedPort}`;
}

export function parseDatasetSize(raw) {
  return parseCanonicalInteger(raw, 'DATASET_SIZE', 0, 1000);
}

export function parseSamples(raw) {
  return parseCanonicalInteger(raw === undefined ? '10' : raw, 'SAMPLES', 1, 100);
}

export function parsePhase(raw) {
  requireCondition(raw === 'warmup' || raw === 'measure', 'PHASE must be warmup or measure');
  return raw;
}

export function parseBulkWriteVariant(raw) {
  requireCondition(raw === 'BEFORE', 'VARIANT must be BEFORE for the U2 baseline');
  return raw;
}

export function parseBulkWriteToken(raw) {
  requireCondition(
    typeof raw === 'string'
      && raw.length >= 32
      && raw.trim().length > 0
      && raw === raw.trim(),
    'BENCHMARK_BULK_WRITE_TOKEN must contain at least 32 non-padded characters',
  );
  return raw;
}

export function parseBulkWriteResultPath(raw, fallback) {
  const value = raw === undefined ? fallback : raw;
  requireCondition(
    typeof value === 'string' && value === value.trim(),
    'K6_RESULT_PATH must not contain surrounding whitespace',
  );
  requireCondition(
    value.length <= 255
      && /^build\/k6\/bulk-write\/[a-zA-Z0-9][a-zA-Z0-9._-]*\.json$/.test(value),
    'K6_RESULT_PATH must be one JSON file under build/k6/bulk-write',
  );
  const filename = value.slice('build/k6/bulk-write/'.length).toLowerCase();
  requireCondition(
    filename !== 'stdout.json' && filename !== 'stderr.json',
    'K6_RESULT_PATH must not use a reserved output name',
  );
  return value;
}

export function parseBulkWriteRunConfig(environment) {
  const variant = parseBulkWriteVariant(environment.VARIANT || 'BEFORE');
  const phase = parsePhase(environment.PHASE || 'measure');
  const datasetSize = parseDatasetSize(environment.DATASET_SIZE);
  const samples = parseSamples(environment.SAMPLES);
  const benchmarkToken = parseBulkWriteToken(environment.BENCHMARK_BULK_WRITE_TOKEN);
  const round = parseCanonicalInteger(environment.ROUND || '1', 'ROUND', 1, 1_000_000);
  const runOrder = parseCanonicalInteger(
    environment.RUN_ORDER || '1',
    'RUN_ORDER',
    1,
    1_000_000,
  );
  const appInstanceCount = parseCanonicalInteger(
    environment.APP_INSTANCE_COUNT || '1',
    'APP_INSTANCE_COUNT',
    1,
    1,
  );
  const resultPath = parseBulkWriteResultPath(
    environment.K6_RESULT_PATH,
    `build/k6/bulk-write/wishlist-delete-before-n${datasetSize}-${phase}-r${round}-o${runOrder}.json`,
  );

  return {
    baseUrl: parseSafeBaseUrl(environment.BASE_URL || 'http://localhost:8080'),
    benchmarkToken,
    variant,
    phase,
    datasetSize,
    samples,
    requestTimeout: parseRequiredPublicText(
      environment.REQUEST_TIMEOUT,
      'REQUEST_TIMEOUT',
      '30s',
    ),
    resultPath,
    runLabel: parseRequiredPublicText(
      environment.RUN_LABEL,
      'RUN_LABEL',
      `wishlist-delete-before-n${datasetSize}-${phase}-r${round}`,
    ),
    round,
    runOrder,
    appCommit: parseRequiredPublicText(environment.APP_COMMIT, 'APP_COMMIT'),
    appInstanceCount,
    schemaLabel: parseRequiredPublicText(environment.SCHEMA_LABEL, 'SCHEMA_LABEL'),
    jvmVersion: parseRequiredPublicText(environment.JVM_VERSION, 'JVM_VERSION'),
    mysqlVersion: parseRequiredPublicText(environment.MYSQL_VERSION, 'MYSQL_VERSION'),
    rewriteBatchedStatements: parseStrictBoolean(
      environment.REWRITE_BATCHED_STATEMENTS,
      'REWRITE_BATCHED_STATEMENTS',
    ),
  };
}

export function buildBulkWriteRequestBody({ variant, datasetSize }) {
  return JSON.stringify({
    variant: parseBulkWriteVariant(variant),
    dataset_size: parseDatasetSize(datasetSize),
  });
}

export function buildBulkWriteHeaders(benchmarkToken) {
  return {
    'Content-Type': 'application/json',
    'X-Bulk-Write-Benchmark-Token': parseBulkWriteToken(benchmarkToken),
  };
}

export function buildBulkWriteRequestParams({
  benchmarkToken,
  sessionId,
  timeout,
  tags = {},
}) {
  requireCondition(
    typeof sessionId === 'string' && sessionId.length > 0 && sessionId === sessionId.trim(),
    'SESSION_ID must be a non-padded string',
  );
  requireCondition(isObject(tags), 'request tags must be an object');
  return {
    cookies: { SESSION_ID: sessionId },
    headers: buildBulkWriteHeaders(benchmarkToken),
    redirects: 0,
    tags: { ...tags },
    timeout: parseRequiredPublicText(timeout, 'REQUEST_TIMEOUT'),
  };
}

export function buildBulkWriteOptions({ phase, samples }) {
  const parsedPhase = parsePhase(phase);
  const parsedSamples = parseSamples(samples);
  const scenarioName = `bulk_write_${parsedPhase}`;

  return {
    maxRedirects: 0,
    setupTimeout: '1m',
    summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
      [scenarioName]: {
        executor: 'shared-iterations',
        vus: 1,
        iterations: parsedSamples,
        maxDuration: '30m',
        tags: {
          candidate: BULK_WRITE_CANDIDATE,
          phase: parsedPhase,
          variant: 'BEFORE',
        },
      },
    },
    thresholds: {
      [`checks{phase:${parsedPhase}}`]: ['rate==1'],
      [`bulk_write_sample_success{phase:${parsedPhase}}`]: ['rate==1'],
      [`bulk_write_verification_success{phase:${parsedPhase}}`]: ['rate==1'],
      [`http_req_failed{phase:${parsedPhase}}`]: ['rate==0'],
    },
  };
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function durationUnitsMatch(nanos, millis) {
  const expected = nanos / 1_000_000;
  const tolerance = Math.max(1e-9, Math.abs(expected) * Number.EPSILON * 4);
  return Math.abs(millis - expected) <= tolerance;
}

export function matchesBulkWriteOperationContract(value) {
  if (!hasExactKeys(value, OPERATION_FIELDS)
      || value.operation_name !== BULK_WRITE_OPERATION_NAME
      || value.outcome !== 'SUCCESS'
      || !isNonNegativeInteger(value.server_operation_nanos)
      || typeof value.server_operation_ms !== 'number'
      || !Number.isFinite(value.server_operation_ms)
      || value.server_operation_ms < 0
      || !durationUnitsMatch(value.server_operation_nanos, value.server_operation_ms)
      || !hasExactKeys(value.hibernate_statements_by_type, SQL_TYPES)
      || value.jdbc_batch_calls !== 0
      || value.jdbc_submitted_rows !== 0
      || value.jdbc_configured_batch_size !== null
      || value.jdbc_affected_rows !== null) {
    return false;
  }

  const counts = value.hibernate_statements_by_type;
  if (!SQL_TYPES.every((type) => isNonNegativeInteger(counts[type]))) {
    return false;
  }
  return counts.TOTAL === counts.SELECT + counts.INSERT + counts.UPDATE + counts.DELETE + counts.OTHER;
}

export function matchesBulkWriteResponseContract(payload, expectedDatasetSize) {
  let datasetSize;
  try {
    datasetSize = parseDatasetSize(expectedDatasetSize);
  } catch (_) {
    return false;
  }

  if (!isObject(payload)
      || !Object.prototype.hasOwnProperty.call(payload, 'success')
      || !Object.prototype.hasOwnProperty.call(payload, 'data')
      || !Object.keys(payload).every((key) => ['success', 'data', 'error'].includes(key))
      || payload.success !== true
      || (Object.prototype.hasOwnProperty.call(payload, 'error') && payload.error !== null)
      || !hasExactKeys(payload.data, DATA_FIELDS)) {
    return false;
  }

  const data = payload.data;
  return data.candidate === BULK_WRITE_CANDIDATE
    && data.variant === 'BEFORE'
    && data.dataset_size === datasetSize
    && data.expected_rows === datasetSize
    && data.verified_rows === datasetSize
    && VERIFICATION_FIELDS.every((field) => data[field] === true)
    && matchesBulkWriteOperationContract(data.operation);
}

function metricValues(data, name) {
  return data.metrics?.[name]?.values || {};
}

function finiteOrNull(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function trendSummary(data, name) {
  const values = metricValues(data, name);
  return {
    count: finiteOrNull(values.count),
    avg: finiteOrNull(values.avg),
    min: finiteOrNull(values.min),
    median: finiteOrNull(values.med),
    p90: finiteOrNull(values['p(90)']),
    p95: finiteOrNull(values['p(95)']),
    p99: finiteOrNull(values['p(99)']),
    max: finiteOrNull(values.max),
  };
}

function rateSummary(data, name) {
  const values = metricValues(data, name);
  const successful = Number(values.passes || 0);
  const failed = Number(values.fails || 0);
  const attempted = successful + failed;
  return {
    attempted,
    successful,
    failed,
    success_rate: attempted === 0 ? null : successful / attempted,
  };
}

export function summarizeBulkWriteMetrics(data) {
  const hibernateStatementsByType = {};
  SQL_TYPES.forEach((type) => {
    hibernateStatementsByType[type] = trendSummary(
      data,
      BULK_WRITE_HIBERNATE_METRICS[type],
    );
  });

  return {
    samples: rateSummary(data, 'bulk_write_sample_success'),
    verification: rateSummary(data, 'bulk_write_verification_success'),
    server_operation_ms: trendSummary(data, 'bulk_write_server_operation_ms'),
    http_orchestration_ms: trendSummary(data, 'bulk_write_http_orchestration_ms'),
    verified_rows: trendSummary(data, 'bulk_write_verified_rows'),
    hibernate_statements_by_type: hibernateStatementsByType,
  };
}

function summarizeJdbcMetrics(data) {
  return {
    batch_calls: trendSummary(data, 'bulk_write_jdbc_batch_calls'),
    submitted_rows: trendSummary(data, 'bulk_write_jdbc_submitted_rows'),
  };
}

function sensitiveKey(key) {
  const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  return normalized.includes('token')
    || normalized.includes('secret')
    || normalized.includes('password')
    || normalized.includes('authorization')
    || normalized.includes('cookie')
    || normalized.includes('sessionid')
    || normalized.includes('userid')
    || normalized.includes('memberid')
    || normalized.includes('accountid');
}

function redactionValues(values) {
  const expanded = [];
  values.forEach((value) => {
    if (typeof value !== 'string' || value.length === 0) {
      return;
    }
    expanded.push(value);
    if (value.trim().length > 0 && value.trim() !== value) {
      expanded.push(value.trim());
    }
  });
  return [...new Set(expanded)].sort((left, right) => right.length - left.length);
}

function redactValue(value, secrets) {
  if (Array.isArray(value)) {
    return value.map((item) => redactValue(item, secrets));
  }
  if (isObject(value)) {
    const redacted = {};
    Object.entries(value).forEach(([key, item]) => {
      if (!sensitiveKey(key)) {
        redacted[key] = redactValue(item, secrets);
      }
    });
    return redacted;
  }
  if (typeof value !== 'string') {
    return value;
  }
  return secrets.reduce(
    (result, secret) => result.split(secret).join('[REDACTED]'),
    value,
  );
}

export function redactK6Summary(data, sensitiveValues = []) {
  requireCondition(isObject(data), 'k6 summary must be an object');
  requireCondition(Array.isArray(sensitiveValues), 'sensitiveValues must be an array');
  return redactValue(data, redactionValues(sensitiveValues));
}

export function buildBulkWriteArtifact({
  config,
  k6Summary,
  generatedAt = new Date().toISOString(),
  sensitiveValues = [],
}) {
  const performance = summarizeBulkWriteMetrics(k6Summary);
  const jdbc = summarizeJdbcMetrics(k6Summary);
  requireCondition(
    typeof config.rewriteBatchedStatements === 'boolean',
    'REWRITE_BATCHED_STATEMENTS must be a boolean',
  );
  const secrets = [
    ...sensitiveValues,
    config.benchmarkToken,
    config.benchmarkEmail,
    config.password,
    config.sessionId,
    config.userId,
  ];
  const metadata = {
    generated_at: generatedAt,
    candidate: BULK_WRITE_CANDIDATE,
    variant: parseBulkWriteVariant(config.variant),
    phase: parsePhase(config.phase),
    dataset_size: parseDatasetSize(config.datasetSize),
    samples: parseSamples(config.samples),
    endpoint: BULK_WRITE_ENDPOINT,
    operation_name: BULK_WRITE_OPERATION_NAME,
    run_label: parseRequiredPublicText(config.runLabel, 'RUN_LABEL'),
    round: parseCanonicalInteger(config.round, 'ROUND', 1, 1_000_000),
    run_order: parseCanonicalInteger(config.runOrder, 'RUN_ORDER', 1, 1_000_000),
    app_commit: parseRequiredPublicText(config.appCommit, 'APP_COMMIT'),
    app_instance_count: parseCanonicalInteger(
      config.appInstanceCount,
      'APP_INSTANCE_COUNT',
      1,
      1,
    ),
    schema_label: parseRequiredPublicText(config.schemaLabel, 'SCHEMA_LABEL'),
    jvm_version: parseRequiredPublicText(config.jvmVersion, 'JVM_VERSION'),
    mysql_version: parseRequiredPublicText(config.mysqlVersion, 'MYSQL_VERSION'),
    rewrite_batched_statements: config.rewriteBatchedStatements,
    request_timeout: parseRequiredPublicText(config.requestTimeout, 'REQUEST_TIMEOUT'),
  };

  return {
    schema_version: ARTIFACT_SCHEMA_VERSION,
    metadata: redactValue(metadata, redactionValues(secrets)),
    performance,
    verification: {
      expected_rows: metadata.dataset_size,
      verified_rows: performance.verified_rows,
      succeeded: performance.verification,
    },
    database_observation: {
      hibernate_statements_by_type: performance.hibernate_statements_by_type,
      jdbc: {
        batch_calls: jdbc.batch_calls.median,
        submitted_rows: jdbc.submitted_rows.median,
        configured_batch_size: null,
        affected_rows: null,
      },
    },
    k6_summary: redactK6Summary(k6Summary, secrets),
  };
}

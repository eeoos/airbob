export const ARTIFACT_SCHEMA_VERSION = 'bulk-write-benchmark-v1';

const SQL_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'OTHER', 'TOTAL'];
const WISHLIST_DATA_FIELDS = [
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
const WISHLIST_VERIFICATION_FIELDS = [
  'verification_succeeded',
  'target_wishlist_deleted',
  'target_memberships_deleted',
  'target_denormalized_state_preserved',
  'control_wishlist_preserved',
  'control_membership_preserved',
  'accommodations_preserved',
];
const RESERVATION_HISTORY_DATA_FIELDS = [
  'candidate',
  'variant',
  'dataset_size',
  'expected_rows',
  'verified_rows',
  'verification_succeeded',
  'target_reservations_expired',
  'target_histories_inserted',
  'future_pending_preserved',
  'non_pending_expired_preserved',
  'history_snapshots_preserved',
  'history_audit_context_preserved',
  'hold_removals_matched',
  'hold_removal_calls',
  'redis_network_excluded',
  'operation',
];
const RESERVATION_HISTORY_VERIFICATION_FIELDS = [
  'verification_succeeded',
  'target_reservations_expired',
  'target_histories_inserted',
  'future_pending_preserved',
  'non_pending_expired_preserved',
  'history_snapshots_preserved',
  'history_audit_context_preserved',
  'hold_removals_matched',
];
const ACCOMMODATION_AMENITY_DATA_FIELDS = [
  'candidate',
  'variant',
  'measurement',
  'workload_class',
  'active_amenity_code_count',
  'dataset_size',
  'old_target_rows_expected',
  'old_target_rows_deleted',
  'old_target_rows_verified',
  'replacement_rows_expected',
  'replacement_rows_verified',
  'replacement_map_expected',
  'replacement_map_verified',
  'target_parent_preserved',
  'history_effect_matched',
  'control_accommodation_preserved',
  'control_amenities_preserved',
  'verification_succeeded',
  'operation',
];

export const WISHLIST_DELETE_BENCHMARK = Object.freeze({
  candidate: 'WISHLIST_DELETE',
  endpoint: '/api/v2/admin/benchmarks/bulk-write/wishlist-delete',
  requestName: 'POST /api/v2/admin/benchmarks/bulk-write/wishlist-delete',
  operationPrefix: 'wishlist-delete',
  maximumDatasetSize: 1000,
  supportedVariants: Object.freeze(['BEFORE', 'AFTER']),
  dataFields: Object.freeze(WISHLIST_DATA_FIELDS),
  matchesData: (data, datasetSize) => (
    data.expected_rows === datasetSize
      && data.verified_rows === datasetSize
      && WISHLIST_VERIFICATION_FIELDS.every((field) => data[field] === true)
      && data.operation.jdbc_batch_calls === 0
      && data.operation.jdbc_submitted_rows === 0
      && data.operation.jdbc_configured_batch_size === null
      && data.operation.jdbc_affected_rows === null
  ),
});

export const RESERVATION_HISTORY_INSERT_BENCHMARK = Object.freeze({
  candidate: 'RESERVATION_HISTORY_INSERT',
  endpoint: '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert',
  requestName: 'POST /api/v2/admin/benchmarks/bulk-write/reservation-history-insert',
  operationPrefix: 'expired-reservation-cleanup',
  maximumDatasetSize: 2000,
  supportedVariants: Object.freeze(['BEFORE', 'AFTER']),
  dataFields: Object.freeze(RESERVATION_HISTORY_DATA_FIELDS),
  externalEffects: Object.freeze({
    holdRemovalMetric: 'bulk_write_hold_removal_calls',
    holdRemovalMode: 'RECORDED_NO_IO',
    redisNetworkExcluded: true,
  }),
  matchesData: (data, datasetSize, variant) => {
    const counts = data.operation.hibernate_statements_by_type;
    const operation = data.operation;
    const isBefore = variant === 'BEFORE'
      && counts.SELECT === 1
      && counts.INSERT === datasetSize
      && counts.UPDATE === datasetSize
      && counts.TOTAL === 1 + (datasetSize * 2)
      && operation.jdbc_batch_calls === 0;
    const isAfterEmpty = variant === 'AFTER'
      && datasetSize === 0
      && counts.SELECT === 1
      && counts.INSERT === 0
      && counts.UPDATE === 0
      && counts.TOTAL === 1
      && operation.jdbc_batch_calls === 0;
    const isAfterNonEmpty = variant === 'AFTER'
      && datasetSize > 0
      && counts.SELECT === 1
      && counts.INSERT === 0
      && counts.UPDATE === datasetSize
      && counts.TOTAL === datasetSize + 1
      && operation.jdbc_submitted_rows === datasetSize
      && operation.jdbc_batch_calls
        === Math.ceil(datasetSize / operation.jdbc_configured_batch_size)
      && (operation.jdbc_affected_rows === null
        || operation.jdbc_affected_rows === datasetSize);
    return data.expected_rows === datasetSize
      && data.verified_rows === datasetSize
      && RESERVATION_HISTORY_VERIFICATION_FIELDS.every((field) => data[field] === true)
      && data.hold_removal_calls === datasetSize
      && data.redis_network_excluded === true
      && counts.DELETE === 0
      && counts.OTHER === 0
      && (isBefore || isAfterEmpty || isAfterNonEmpty);
  },
});

function mapsMatch(left, right) {
  if (!isObject(left) || !isObject(right)) {
    return false;
  }
  const leftKeys = Object.keys(left).sort();
  const rightKeys = Object.keys(right).sort();
  return leftKeys.length === rightKeys.length
    && leftKeys.every((key, index) => (
      key === rightKeys[index]
      && Number.isSafeInteger(left[key])
      && left[key] > 0
      && left[key] === right[key]
    ));
}

export const ACCOMMODATION_AMENITY_DELETE_BENCHMARK = Object.freeze({
  candidate: 'ACCOMMODATION_AMENITY_DELETE',
  endpoint: '/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete',
  requestName: 'POST /api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete',
  operationPrefix: 'accommodation-amenity',
  operationName: (variant, measurement) => (
    measurement === 'FULL_REPLACEMENT'
      ? `accommodation-amenity-full-replacement-${variant.toLowerCase()}`
      : `accommodation-amenity-delete-only-${variant.toLowerCase()}`
  ),
  maximumDatasetSize: 100,
  supportedVariants: Object.freeze(['BEFORE', 'AFTER']),
  supportedMeasurements: Object.freeze(['FULL_REPLACEMENT', 'DELETE_ONLY']),
  activeCodeMetric: 'bulk_write_active_amenity_code_count',
  dataFields: Object.freeze(ACCOMMODATION_AMENITY_DATA_FIELDS),
  matchesData: (data, datasetSize, variant, measurement) => {
    const activeCodeCount = data.active_amenity_code_count;
    const replacementRows = data.replacement_rows_expected;
    const fullReplacement = measurement === 'FULL_REPLACEMENT';
    const counts = data.operation.hibernate_statements_by_type;
    const expectedWorkload = datasetSize <= activeCodeCount ? 'REALISTIC' : 'STRESS';
    const sqlMatches = variant === 'BEFORE'
      ? (fullReplacement
        ? counts.SELECT === 3
          && counts.DELETE === datasetSize
          && counts.INSERT === replacementRows + 1
          && counts.UPDATE === 1
          && counts.OTHER === 0
          && counts.TOTAL === datasetSize + replacementRows + 5
        : counts.SELECT === 1
          && counts.DELETE === datasetSize
          && counts.INSERT === 0
          && counts.UPDATE === 0
          && counts.OTHER === 0
          && counts.TOTAL === datasetSize + 1)
      : (fullReplacement
        ? counts.SELECT === 2
          && counts.DELETE === 1
          && counts.INSERT === replacementRows + 1
          && counts.UPDATE === 1
          && counts.OTHER === 0
          && counts.TOTAL === replacementRows + 5
        : counts.SELECT === 0
          && counts.DELETE === 1
          && counts.INSERT === 0
          && counts.UPDATE === 0
          && counts.OTHER === 0
          && counts.TOTAL === 1);
    return (variant === 'BEFORE' || variant === 'AFTER')
      && data.measurement === measurement
      && Number.isSafeInteger(activeCodeCount)
      && activeCodeCount > 0
      && data.workload_class === expectedWorkload
      && data.old_target_rows_expected === datasetSize
      && data.old_target_rows_deleted === datasetSize
      && data.old_target_rows_verified === datasetSize
      && Number.isSafeInteger(replacementRows)
      && replacementRows >= 0
      && replacementRows === data.replacement_rows_verified
      && replacementRows === Object.keys(data.replacement_map_expected || {}).length
      && mapsMatch(data.replacement_map_expected, data.replacement_map_verified)
      && (fullReplacement
        ? replacementRows === Math.min(datasetSize, activeCodeCount)
        : replacementRows === 0)
      && data.target_parent_preserved === true
      && data.history_effect_matched === true
      && data.control_accommodation_preserved === true
      && data.control_amenities_preserved === true
      && data.verification_succeeded === true
      && data.operation.jdbc_batch_calls === 0
      && data.operation.jdbc_submitted_rows === 0
      && data.operation.jdbc_configured_batch_size === null
      && data.operation.jdbc_affected_rows === null
      && sqlMatches;
  },
});

export function accommodationAmenityServerOperationMetricName(measurement) {
  const parsed = parseBulkWriteMeasurement(
    measurement,
    ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
  );
  return parsed === 'FULL_REPLACEMENT'
    ? 'bulk_write_accommodation_amenity_full_replacement_server_operation_ms'
    : 'bulk_write_accommodation_amenity_delete_only_server_operation_ms';
}

export const BULK_WRITE_ENDPOINT = WISHLIST_DELETE_BENCHMARK.endpoint;
export const BULK_WRITE_REQUEST_NAME = WISHLIST_DELETE_BENCHMARK.requestName;
export const BULK_WRITE_CANDIDATE = WISHLIST_DELETE_BENCHMARK.candidate;
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

function requireBenchmarkDefinition(benchmark) {
  requireCondition(isObject(benchmark), 'bulk-write benchmark definition must be an object');
  requireCondition(
    typeof benchmark.candidate === 'string' && benchmark.candidate.length > 0,
    'bulk-write benchmark candidate is required',
  );
  requireCondition(
    typeof benchmark.endpoint === 'string' && benchmark.endpoint.startsWith('/'),
    'bulk-write benchmark endpoint is required',
  );
  requireCondition(
    typeof benchmark.requestName === 'string' && benchmark.requestName.length > 0,
    'bulk-write benchmark request name is required',
  );
  requireCondition(
    typeof benchmark.operationPrefix === 'string' && benchmark.operationPrefix.length > 0,
    'bulk-write benchmark operation prefix is required',
  );
  if (benchmark.operationName !== undefined) {
    requireCondition(
      typeof benchmark.operationName === 'function',
      'bulk-write benchmark operation-name resolver is invalid',
    );
  }
  requireCondition(
    Number.isSafeInteger(benchmark.maximumDatasetSize)
      && benchmark.maximumDatasetSize >= 0,
    'bulk-write benchmark maximum dataset size is invalid',
  );
  requireCondition(
    Array.isArray(benchmark.supportedVariants)
      && benchmark.supportedVariants.length > 0,
    'bulk-write benchmark variants are required',
  );
  requireCondition(
    Array.isArray(benchmark.dataFields) && typeof benchmark.matchesData === 'function',
    'bulk-write benchmark response contract is required',
  );
  if (benchmark.supportedMeasurements !== undefined) {
    requireCondition(
      Array.isArray(benchmark.supportedMeasurements)
        && benchmark.supportedMeasurements.length > 0,
      'bulk-write benchmark measurements are required',
    );
  }
  if (benchmark.externalEffects !== undefined) {
    requireCondition(
      isObject(benchmark.externalEffects)
        && typeof benchmark.externalEffects.holdRemovalMetric === 'string'
        && typeof benchmark.externalEffects.holdRemovalMode === 'string'
        && typeof benchmark.externalEffects.redisNetworkExcluded === 'boolean',
      'bulk-write benchmark external-effect contract is invalid',
    );
  }
  return benchmark;
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

export function parseDatasetSize(raw, benchmark = WISHLIST_DELETE_BENCHMARK) {
  const definition = requireBenchmarkDefinition(benchmark);
  return parseCanonicalInteger(raw, 'DATASET_SIZE', 0, definition.maximumDatasetSize);
}

export function parseSamples(raw) {
  return parseCanonicalInteger(raw === undefined ? '10' : raw, 'SAMPLES', 1, 100);
}

export function parsePhase(raw) {
  requireCondition(raw === 'warmup' || raw === 'measure', 'PHASE must be warmup or measure');
  return raw;
}

export function parseBulkWriteVariant(raw, benchmark = WISHLIST_DELETE_BENCHMARK) {
  const definition = requireBenchmarkDefinition(benchmark);
  requireCondition(
    definition.supportedVariants.includes(raw),
    `VARIANT must be ${definition.supportedVariants.join(' or ')}`,
  );
  return raw;
}

export function parseBulkWriteMeasurement(raw, benchmark) {
  const definition = requireBenchmarkDefinition(benchmark);
  requireCondition(
    definition.supportedMeasurements !== undefined
      && definition.supportedMeasurements.includes(raw),
    `MEASUREMENT must be ${definition.supportedMeasurements?.join(' or ')}`,
  );
  return raw;
}

export function bulkWriteOperationName(
  rawVariant,
  benchmark = WISHLIST_DELETE_BENCHMARK,
  rawMeasurement = undefined,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  const variant = parseBulkWriteVariant(rawVariant, definition);
  if (definition.supportedMeasurements !== undefined) {
    const measurement = parseBulkWriteMeasurement(rawMeasurement, definition);
    return definition.operationName(variant, measurement);
  }
  return `${definition.operationPrefix}-${variant.toLowerCase()}`;
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

export function parseBulkWriteRunConfig(
  environment,
  benchmark = WISHLIST_DELETE_BENCHMARK,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  const variant = parseBulkWriteVariant(environment.VARIANT, definition);
  const measurement = definition.supportedMeasurements === undefined
    ? undefined
    : parseBulkWriteMeasurement(
      environment.MEASUREMENT || definition.supportedMeasurements[0],
      definition,
    );
  const operationName = bulkWriteOperationName(variant, definition, measurement);
  const phase = parsePhase(environment.PHASE || 'measure');
  const datasetSize = parseDatasetSize(environment.DATASET_SIZE, definition);
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
    `build/k6/bulk-write/${operationName}-n${datasetSize}-${phase}-r${round}-o${runOrder}.json`,
  );

  return {
    baseUrl: parseSafeBaseUrl(environment.BASE_URL || 'http://localhost:8080'),
    benchmarkToken,
    variant,
    ...(measurement === undefined ? {} : { measurement }),
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
      `${operationName}-n${datasetSize}-${phase}-r${round}`,
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

export function buildBulkWriteRequestBody(
  { variant, measurement, datasetSize },
  benchmark = WISHLIST_DELETE_BENCHMARK,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  const body = {
    variant: parseBulkWriteVariant(variant, definition),
  };
  if (definition.supportedMeasurements !== undefined) {
    body.measurement = parseBulkWriteMeasurement(measurement, definition);
  }
  body.dataset_size = parseDatasetSize(datasetSize, definition);
  return JSON.stringify(body);
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

export function buildBulkWriteOptions(
  { variant, measurement, phase, samples },
  benchmark = WISHLIST_DELETE_BENCHMARK,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  const parsedVariant = parseBulkWriteVariant(variant, definition);
  const parsedPhase = parsePhase(phase);
  const parsedSamples = parseSamples(samples);
  const parsedMeasurement = definition.supportedMeasurements === undefined
    ? undefined
    : parseBulkWriteMeasurement(measurement, definition);
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
          candidate: definition.candidate,
          phase: parsedPhase,
          variant: parsedVariant,
          ...(parsedMeasurement === undefined ? {} : { measurement: parsedMeasurement }),
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

function matchesJdbcOperation(value) {
  if (!isNonNegativeInteger(value.jdbc_batch_calls)
      || !isNonNegativeInteger(value.jdbc_submitted_rows)) {
    return false;
  }
  if (value.jdbc_batch_calls === 0) {
    return value.jdbc_submitted_rows === 0
      && value.jdbc_configured_batch_size === null
      && value.jdbc_affected_rows === null;
  }
  return value.jdbc_submitted_rows > 0
    && Number.isSafeInteger(value.jdbc_configured_batch_size)
    && value.jdbc_configured_batch_size > 0
    && (value.jdbc_affected_rows === null
      || isNonNegativeInteger(value.jdbc_affected_rows));
}

function durationUnitsMatch(nanos, millis) {
  const expected = nanos / 1_000_000;
  const tolerance = Math.max(1e-9, Math.abs(expected) * Number.EPSILON * 4);
  return Math.abs(millis - expected) <= tolerance;
}

export function matchesBulkWriteOperationContract(
  value,
  expectedVariant = 'BEFORE',
  benchmark = WISHLIST_DELETE_BENCHMARK,
  expectedMeasurement = undefined,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  let operationName;
  try {
    operationName = bulkWriteOperationName(expectedVariant, definition, expectedMeasurement);
  } catch (_) {
    return false;
  }

  if (!hasExactKeys(value, OPERATION_FIELDS)
      || value.operation_name !== operationName
      || value.outcome !== 'SUCCESS'
      || !isNonNegativeInteger(value.server_operation_nanos)
      || typeof value.server_operation_ms !== 'number'
      || !Number.isFinite(value.server_operation_ms)
      || value.server_operation_ms < 0
      || !durationUnitsMatch(value.server_operation_nanos, value.server_operation_ms)
      || !hasExactKeys(value.hibernate_statements_by_type, SQL_TYPES)
      || !matchesJdbcOperation(value)) {
    return false;
  }

  const counts = value.hibernate_statements_by_type;
  if (!SQL_TYPES.every((type) => isNonNegativeInteger(counts[type]))) {
    return false;
  }
  return counts.TOTAL === counts.SELECT + counts.INSERT + counts.UPDATE + counts.DELETE + counts.OTHER;
}

export function matchesBulkWriteResponseContract(
  payload,
  expectedDatasetSize,
  expectedVariant = 'BEFORE',
  benchmark = WISHLIST_DELETE_BENCHMARK,
  expectedMeasurement = undefined,
) {
  const definition = requireBenchmarkDefinition(benchmark);
  let datasetSize;
  let variant;
  let measurement;
  try {
    datasetSize = parseDatasetSize(expectedDatasetSize, definition);
    variant = parseBulkWriteVariant(expectedVariant, definition);
    measurement = definition.supportedMeasurements === undefined
      ? undefined
      : parseBulkWriteMeasurement(expectedMeasurement, definition);
  } catch (_) {
    return false;
  }

  if (!isObject(payload)
      || !Object.prototype.hasOwnProperty.call(payload, 'success')
      || !Object.prototype.hasOwnProperty.call(payload, 'data')
      || !Object.keys(payload).every((key) => ['success', 'data', 'error'].includes(key))
      || payload.success !== true
      || (Object.prototype.hasOwnProperty.call(payload, 'error') && payload.error !== null)
      || !hasExactKeys(payload.data, definition.dataFields)) {
    return false;
  }

  const data = payload.data;
  return data.candidate === definition.candidate
    && data.variant === variant
    && data.dataset_size === datasetSize
    && matchesBulkWriteOperationContract(data.operation, variant, definition, measurement)
    && definition.matchesData(data, datasetSize, variant, measurement);
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
    configured_batch_size: trendSummary(data, 'bulk_write_jdbc_configured_batch_size'),
    affected_rows: trendSummary(data, 'bulk_write_jdbc_affected_rows'),
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
}, benchmark = WISHLIST_DELETE_BENCHMARK) {
  const definition = requireBenchmarkDefinition(benchmark);
  const performance = summarizeBulkWriteMetrics(k6Summary);
  const jdbc = summarizeJdbcMetrics(k6Summary);
  const affectedRowsMetricValues = metricValues(
    k6Summary,
    'bulk_write_jdbc_affected_rows',
  );
  const affectedRowsKnownSamples = affectedRowsMetricValues.count === undefined
    ? 0
    : affectedRowsMetricValues.count;
  requireCondition(
    Number.isSafeInteger(performance.samples.successful)
      && performance.samples.successful >= 0
      && Number.isSafeInteger(affectedRowsKnownSamples)
      && affectedRowsKnownSamples >= 0
      && affectedRowsKnownSamples <= performance.samples.successful,
    'affected-row metric count must not exceed successful samples',
  );
  const affectedRowsUnknownSamples = performance.samples.successful
    - affectedRowsKnownSamples;
  const everySuccessfulAffectedRowKnown = performance.samples.successful > 0
    && affectedRowsKnownSamples === performance.samples.successful
    && jdbc.affected_rows.median !== null;
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
    candidate: definition.candidate,
    variant: parseBulkWriteVariant(config.variant, definition),
    ...(definition.supportedMeasurements === undefined
      ? {}
      : {
        measurement: parseBulkWriteMeasurement(config.measurement, definition),
        active_amenity_code_count: trendSummary(
          k6Summary,
          definition.activeCodeMetric,
        ).median,
      }),
    phase: parsePhase(config.phase),
    dataset_size: parseDatasetSize(config.datasetSize, definition),
    samples: parseSamples(config.samples),
    endpoint: definition.endpoint,
    operation_name: bulkWriteOperationName(config.variant, definition, config.measurement),
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
  if (definition.supportedMeasurements !== undefined) {
    requireCondition(
      Number.isSafeInteger(metadata.active_amenity_code_count)
        && metadata.active_amenity_code_count > 0,
      'active amenity code count must be a positive integer',
    );
    metadata.workload_class = metadata.dataset_size <= metadata.active_amenity_code_count
      ? 'REALISTIC'
      : 'STRESS';
  }
  const externalEffects = definition.externalEffects === undefined
    ? {}
    : {
      external_effects: {
        hold_removal_calls: trendSummary(
          k6Summary,
          definition.externalEffects.holdRemovalMetric,
        ).median,
        hold_removal_mode: definition.externalEffects.holdRemovalMode,
        redis_network_excluded: definition.externalEffects.redisNetworkExcluded,
      },
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
        configured_batch_size: jdbc.configured_batch_size.median,
        affected_rows: everySuccessfulAffectedRowKnown ? jdbc.affected_rows.median : null,
        affected_rows_known_samples: affectedRowsKnownSamples,
        affected_rows_unknown_samples: affectedRowsUnknownSamples,
      },
      ...externalEffects,
    },
    k6_summary: redactK6Summary(k6Summary, secrets),
  };
}

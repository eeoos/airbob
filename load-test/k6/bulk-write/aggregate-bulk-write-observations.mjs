#!/usr/bin/env node

import {
  closeSync,
  constants,
  fstatSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOURCE_SCHEMA_VERSION = 'bulk-write-benchmark-v1';
const OBSERVATION_SCHEMA_VERSION = 'bulk-write-observations-v1';
// Keep this closed allowlist aligned with the benchmark API contracts.
const BENCHMARKS = Object.freeze({
  RESERVATION_HISTORY_INSERT: Object.freeze({
    candidate: 'RESERVATION_HISTORY_INSERT',
    endpoint: '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert',
    operationPrefix: 'expired-reservation-cleanup',
    maximumDatasetSize: 2000,
    externalEffects: true,
  }),
  WISHLIST_DELETE: Object.freeze({
    candidate: 'WISHLIST_DELETE',
    endpoint: '/api/v2/admin/benchmarks/bulk-write/wishlist-delete',
    operationPrefix: 'wishlist-delete',
    maximumDatasetSize: 1000,
    externalEffects: false,
  }),
});
const SQL_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'OTHER', 'TOTAL'];
const METADATA_KEYS = [
  'generated_at',
  'candidate',
  'variant',
  'phase',
  'dataset_size',
  'samples',
  'endpoint',
  'operation_name',
  'run_label',
  'round',
  'run_order',
  'app_commit',
  'app_instance_count',
  'schema_label',
  'jvm_version',
  'mysql_version',
  'rewrite_batched_statements',
  'request_timeout',
];
const SHARED_METADATA_KEYS = [
  'candidate',
  'variant',
  'phase',
  'dataset_size',
  'samples',
  'endpoint',
  'operation_name',
  'round',
  'app_commit',
  'app_instance_count',
  'schema_label',
  'jvm_version',
  'mysql_version',
  'rewrite_batched_statements',
  'request_timeout',
];
const TREND_KEYS = ['count', 'avg', 'min', 'median', 'p90', 'p95', 'p99', 'max'];
const RATE_KEYS = ['attempted', 'successful', 'failed', 'success_rate'];
const SOURCE_KEYS = [
  'schema_version',
  'metadata',
  'performance',
  'verification',
  'database_observation',
  'k6_summary',
];
const SCRIPT_PATH = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(dirname(SCRIPT_PATH), '../../..');
const ARTIFACT_ROOT = resolve(REPO_ROOT, 'build/k6/bulk-write');
const MAX_SOURCE_BYTES = 512 * 1024;
const MAX_TOTAL_SOURCE_BYTES = 4 * 1024 * 1024;
const MAX_SCAN_DEPTH = 64;
const MAX_SCAN_NODES = 100_000;

function sensitiveEnvironmentKey(key) {
  const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  return [
    'token',
    'secret',
    'password',
    'authorization',
    'cookie',
    'sessionid',
    'userid',
    'memberid',
    'accountid',
    'email',
    'credential',
    'username',
  ].some((fragment) => normalized.includes(fragment))
    || key === 'BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA';
}

const KNOWN_SECRET_VALUES = [...new Set(Object.entries(process.env)
  .filter(([key, value]) => sensitiveEnvironmentKey(key) && typeof value === 'string' && value.length > 0)
  .map(([, value]) => value))]
  .sort((left, right) => right.length - left.length);

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value, keys) {
  if (!isObject(value)) {
    return false;
  }
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length
    && actual.every((key, index) => key === expected[index]);
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function isFiniteNonNegative(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function benchmarkDefinition(candidate) {
  const definition = BENCHMARKS[candidate];
  requireCondition(definition !== undefined, 'candidate is not allowlisted');
  return definition;
}

function boundedContains(value, { keyPredicate = () => false, stringPredicate = () => false }) {
  const stack = [{ value, depth: 0 }];
  let visitedNodes = 0;
  while (stack.length > 0) {
    const current = stack.pop();
    visitedNodes += 1;
    requireCondition(
      visitedNodes <= MAX_SCAN_NODES && current.depth <= MAX_SCAN_DEPTH,
      'input exceeds security scan limits',
    );
    if (Array.isArray(current.value)) {
      requireCondition(
        visitedNodes + stack.length + current.value.length <= MAX_SCAN_NODES
          && (current.value.length === 0 || current.depth < MAX_SCAN_DEPTH),
        'input exceeds security scan limits',
      );
      for (let index = current.value.length - 1; index >= 0; index -= 1) {
        stack.push({ value: current.value[index], depth: current.depth + 1 });
      }
      continue;
    }
    if (isObject(current.value)) {
      const entries = Object.entries(current.value);
      requireCondition(
        visitedNodes + stack.length + entries.length <= MAX_SCAN_NODES
          && (entries.length === 0 || current.depth < MAX_SCAN_DEPTH),
        'input exceeds security scan limits',
      );
      for (let index = entries.length - 1; index >= 0; index -= 1) {
        const [key, item] = entries[index];
        if (keyPredicate(key)) {
          return true;
        }
        stack.push({ value: item, depth: current.depth + 1 });
      }
      continue;
    }
    if (typeof current.value === 'string' && stringPredicate(current.value)) {
      return true;
    }
  }
  return false;
}

function sensitiveDataKey(key) {
  const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  return [
    'token',
    'secret',
    'password',
    'authorization',
    'cookie',
    'sessionid',
    'userid',
    'memberid',
    'accountid',
    'email',
    'credential',
  ].some((fragment) => normalized.includes(fragment));
}

function containsSensitiveData(value) {
  return boundedContains(value, {
    keyPredicate: sensitiveDataKey,
    stringPredicate: (candidate) => (
      /[^\s@]+@[^\s@]+\.[^\s@]+/.test(candidate)
        || /(?:bearer\s|password|secret|\btoken\b|session[_ -]?id|member[_ -]?id|user[_ -]?id|authorization|cookie|credential)/i
          .test(candidate)
    ),
  });
}

function containsKnownSecret(value) {
  return boundedContains(value, {
    stringPredicate: (candidate) => KNOWN_SECRET_VALUES.some(
      (secret) => candidate.includes(secret),
    ),
  });
}

function validateRate(value, name) {
  requireCondition(hasExactKeys(value, RATE_KEYS), `${name} rate contract is invalid`);
  requireCondition(
    value.attempted === 1
      && value.successful === 1
      && value.failed === 0
      && value.success_rate === 1,
    `${name} must contain one successful observation`,
  );
}

function validateTrend(value, name, expectedMedian = undefined) {
  requireCondition(hasExactKeys(value, TREND_KEYS), `${name} trend contract is invalid`);
  requireCondition(value.count === 1, `${name} trend count must be one`);
  TREND_KEYS.filter((key) => key !== 'count').forEach((key) => {
    requireCondition(isFiniteNonNegative(value[key]), `${name} trend contains an invalid value`);
    requireCondition(value[key] === value.median, `${name} one-sample trend is inconsistent`);
  });
  if (expectedMedian !== undefined) {
    requireCondition(value.median === expectedMedian, `${name} median is inconsistent`);
  }
}

function metricValues(source, name) {
  const metric = source.k6_summary.metrics?.[name];
  requireCondition(isObject(metric) && isObject(metric.values), 'required k6 metric is missing');
  return metric.values;
}

function validateMetricCount(source, name, expectedCount) {
  requireCondition(
    metricValues(source, name).count === expectedCount,
    'required k6 metric count is invalid',
  );
}

function validateOptionalZeroCountMetric(source, name) {
  const metrics = source.k6_summary.metrics;
  requireCondition(isObject(metrics), 'k6 metrics contract is invalid');
  if (!Object.prototype.hasOwnProperty.call(metrics, name)) {
    return;
  }
  const metric = metrics[name];
  requireCondition(
    isObject(metric)
      && hasExactKeys(metric.values, ['count'])
      && metric.values.count === 0,
    'optional k6 metric zero-count contract is invalid',
  );
}

function validateMetricValue(source, name, expectedValue) {
  const values = metricValues(source, name);
  const aggregateValues = Object.entries(values).filter(([key]) => key !== 'count');
  requireCondition(
    values.count === 1
      && Object.prototype.hasOwnProperty.call(values, 'med')
      && aggregateValues.length > 0
      && aggregateValues.every(([, value]) => (
        typeof value === 'number'
          && Number.isFinite(value)
          && value === expectedValue
      )),
    'required k6 metric value is inconsistent',
  );
}

function validateMetadata(metadata, parentLabel, sampleIndex, definition) {
  requireCondition(hasExactKeys(metadata, METADATA_KEYS), 'source metadata contract is invalid');
  requireCondition(
    typeof metadata.generated_at === 'string'
      && Number.isFinite(Date.parse(metadata.generated_at)),
    'source generation timestamp is invalid',
  );
  requireCondition(metadata.candidate === definition.candidate, 'source candidate is invalid');
  requireCondition(metadata.variant === 'BEFORE' || metadata.variant === 'AFTER', 'source variant is invalid');
  requireCondition(metadata.phase === 'measure', 'source phase must be measure');
  requireCondition(
    isNonNegativeInteger(metadata.dataset_size)
      && metadata.dataset_size <= definition.maximumDatasetSize,
    'source dataset size is invalid',
  );
  requireCondition(metadata.samples === 1, 'source must contain exactly one sample');
  requireCondition(metadata.endpoint === definition.endpoint, 'source endpoint is invalid');
  requireCondition(
    metadata.operation_name
      === `${definition.operationPrefix}-${metadata.variant.toLowerCase()}`,
    'source operation name is invalid',
  );
  requireCondition(
    metadata.run_label === `${parentLabel}-sample-${String(sampleIndex).padStart(3, '0')}`,
    'source child label is invalid',
  );
  requireCondition(isPositiveInteger(metadata.round), 'source round is invalid');
  requireCondition(metadata.run_order === sampleIndex, 'source sample index or run order is invalid');
  requireCondition(
    typeof metadata.app_commit === 'string' && metadata.app_commit.length > 0,
    'source app commit is invalid',
  );
  requireCondition(metadata.app_instance_count === 1, 'source app instance count is invalid');
  ['schema_label', 'jvm_version', 'mysql_version', 'request_timeout'].forEach((key) => {
    requireCondition(
      typeof metadata[key] === 'string' && metadata[key].length > 0,
      `source ${key} is invalid`,
    );
  });
  requireCondition(
    typeof metadata.rewrite_batched_statements === 'boolean',
    'source rewriteBatchedStatements value is invalid',
  );
}

function validateSuccessfulSummary(source, definition) {
  const { performance, verification } = source;
  requireCondition(hasExactKeys(performance, [
    'samples',
    'verification',
    'server_operation_ms',
    'http_orchestration_ms',
    'verified_rows',
    'hibernate_statements_by_type',
  ]), 'source performance contract is invalid');
  validateRate(performance.samples, 'sample');
  validateRate(performance.verification, 'verification');
  validateTrend(performance.server_operation_ms, 'server operation');
  validateTrend(performance.http_orchestration_ms, 'HTTP orchestration');
  validateTrend(performance.verified_rows, 'verified rows', source.metadata.dataset_size);
  requireCondition(
    hasExactKeys(performance.hibernate_statements_by_type, SQL_TYPES),
    'source performance Hibernate observation contract is invalid',
  );
  SQL_TYPES.forEach((type) => {
    validateTrend(
      performance.hibernate_statements_by_type[type],
      `performance Hibernate ${type}`,
    );
  });

  requireCondition(
    hasExactKeys(verification, ['expected_rows', 'verified_rows', 'succeeded']),
    'source verification contract is invalid',
  );
  requireCondition(
    verification.expected_rows === source.metadata.dataset_size,
    'source expected rows are invalid',
  );
  validateTrend(verification.verified_rows, 'verification rows', source.metadata.dataset_size);
  validateRate(verification.succeeded, 'source verification');

  const sampleValues = metricValues(source, 'bulk_write_sample_success');
  const verificationValues = metricValues(source, 'bulk_write_verification_success');
  requireCondition(
    sampleValues.passes === 1 && sampleValues.fails === 0 && sampleValues.rate === 1,
    'source sample metric is not successful',
  );
  requireCondition(
    verificationValues.passes === 1
      && verificationValues.fails === 0
      && verificationValues.rate === 1,
    'source verification metric is not successful',
  );

  validateMetricValue(
    source,
    'bulk_write_server_operation_ms',
    performance.server_operation_ms.median,
  );
  validateMetricValue(
    source,
    'bulk_write_http_orchestration_ms',
    performance.http_orchestration_ms.median,
  );
  validateMetricValue(source, 'bulk_write_verified_rows', source.metadata.dataset_size);
  ['bulk_write_jdbc_batch_calls', 'bulk_write_jdbc_submitted_rows']
    .forEach((name) => validateMetricCount(source, name, 1));
  if (definition.externalEffects) {
    validateMetricCount(source, 'bulk_write_hold_removal_calls', 1);
  } else {
    requireCondition(
      source.k6_summary.metrics?.bulk_write_hold_removal_calls === undefined,
      'source contains an unexpected external-effect metric',
    );
  }
  SQL_TYPES.forEach((type) => {
    validateMetricCount(source, `bulk_write_hibernate_${type.toLowerCase()}_statements`, 1);
  });
}

function validateDatabaseObservation(source, definition) {
  const observation = source.database_observation;
  const observationKeys = [
    'hibernate_statements_by_type',
    'jdbc',
  ];
  if (definition.externalEffects) {
    observationKeys.push('external_effects');
  }
  requireCondition(
    hasExactKeys(observation, observationKeys),
    'source database observation contract is invalid',
  );
  requireCondition(
    hasExactKeys(observation.hibernate_statements_by_type, SQL_TYPES),
    'source Hibernate observation contract is invalid',
  );

  const sql = {};
  SQL_TYPES.forEach((type) => {
    const value = observation.hibernate_statements_by_type[type];
    validateTrend(value, `Hibernate ${type}`);
    requireCondition(
      TREND_KEYS.every(
        (key) => value[key] === source.performance.hibernate_statements_by_type[type][key],
      ),
      `Hibernate ${type} observation is inconsistent`,
    );
    sql[type] = value.median;
    validateMetricValue(
      source,
      `bulk_write_hibernate_${type.toLowerCase()}_statements`,
      sql[type],
    );
  });
  requireCondition(
    SQL_TYPES.every((type) => isNonNegativeInteger(sql[type])),
    'source Hibernate statement count is invalid',
  );
  requireCondition(
    sql.TOTAL === sql.SELECT + sql.INSERT + sql.UPDATE + sql.DELETE + sql.OTHER,
    'source Hibernate total is inconsistent',
  );

  const jdbc = observation.jdbc;
  requireCondition(hasExactKeys(jdbc, [
    'batch_calls',
    'submitted_rows',
    'configured_batch_size',
    'affected_rows',
    'affected_rows_known_samples',
    'affected_rows_unknown_samples',
  ]), 'source JDBC observation contract is invalid');
  requireCondition(
    isNonNegativeInteger(jdbc.batch_calls) && isNonNegativeInteger(jdbc.submitted_rows),
    'source JDBC counts are invalid',
  );
  validateMetricValue(source, 'bulk_write_jdbc_batch_calls', jdbc.batch_calls);
  validateMetricValue(source, 'bulk_write_jdbc_submitted_rows', jdbc.submitted_rows);
  requireCondition(
    jdbc.affected_rows_known_samples + jdbc.affected_rows_unknown_samples === 1
      && isNonNegativeInteger(jdbc.affected_rows_known_samples)
      && isNonNegativeInteger(jdbc.affected_rows_unknown_samples),
    'source affected-row coverage is invalid',
  );

  const { variant, dataset_size: datasetSize } = source.metadata;
  const noJdbcActivity = definition.candidate === 'WISHLIST_DELETE'
    || variant === 'BEFORE'
    || datasetSize === 0;
  if (noJdbcActivity) {
    requireCondition(
      jdbc.batch_calls === 0
        && jdbc.submitted_rows === 0
        && jdbc.configured_batch_size === null
        && jdbc.affected_rows === null
        && jdbc.affected_rows_known_samples === 0
        && jdbc.affected_rows_unknown_samples === 1,
      'source non-batch JDBC observation is invalid',
    );
    validateOptionalZeroCountMetric(source, 'bulk_write_jdbc_configured_batch_size');
    validateOptionalZeroCountMetric(source, 'bulk_write_jdbc_affected_rows');
  } else {
    requireCondition(
      isPositiveInteger(jdbc.configured_batch_size)
        && jdbc.submitted_rows === datasetSize
        && jdbc.batch_calls === Math.ceil(datasetSize / jdbc.configured_batch_size),
      'source batch JDBC observation is invalid',
    );
    validateMetricCount(source, 'bulk_write_jdbc_configured_batch_size', 1);
    validateMetricValue(
      source,
      'bulk_write_jdbc_configured_batch_size',
      jdbc.configured_batch_size,
    );
    if (jdbc.affected_rows === null) {
      requireCondition(
        jdbc.affected_rows_known_samples === 0
          && jdbc.affected_rows_unknown_samples === 1,
        'source unknown affected-row coverage is invalid',
      );
      validateOptionalZeroCountMetric(source, 'bulk_write_jdbc_affected_rows');
    } else {
      requireCondition(
        jdbc.affected_rows === datasetSize
          && jdbc.affected_rows_known_samples === 1
          && jdbc.affected_rows_unknown_samples === 0,
        'source exact affected-row observation is invalid',
      );
      validateMetricCount(source, 'bulk_write_jdbc_affected_rows', 1);
      validateMetricValue(source, 'bulk_write_jdbc_affected_rows', jdbc.affected_rows);
    }
  }

  if (definition.candidate === 'RESERVATION_HISTORY_INSERT') {
    if (variant === 'BEFORE') {
      requireCondition(
        sql.SELECT === 1
          && sql.INSERT === datasetSize
          && sql.UPDATE === datasetSize
          && sql.DELETE === 0
          && sql.OTHER === 0
          && sql.TOTAL === 1 + (datasetSize * 2),
        'source BEFORE Hibernate observation is invalid',
      );
    } else {
      requireCondition(
        sql.SELECT === 1
          && sql.INSERT === 0
          && sql.UPDATE === datasetSize
          && sql.DELETE === 0
          && sql.OTHER === 0
          && sql.TOTAL === 1 + datasetSize,
        'source AFTER Hibernate observation is invalid',
      );
    }
  } else {
    const expectedDeleteCount = datasetSize === 0
      ? 0
      : (variant === 'BEFORE' ? datasetSize : 1);
    requireCondition(
      sql.SELECT === 2
        && sql.INSERT === 0
        && sql.UPDATE === 1
        && sql.DELETE === expectedDeleteCount
        && sql.OTHER === 0
        && sql.TOTAL === 3 + expectedDeleteCount,
      'source Wishlist Hibernate observation is invalid',
    );
  }

  if (definition.externalEffects) {
    const effects = observation.external_effects;
    requireCondition(hasExactKeys(effects, [
      'hold_removal_calls',
      'hold_removal_mode',
      'redis_network_excluded',
    ]), 'source external-effect contract is invalid');
    requireCondition(
      effects.hold_removal_calls === datasetSize
        && effects.hold_removal_mode === 'RECORDED_NO_IO'
        && effects.redis_network_excluded === true,
      'source external-effect observation is invalid',
    );
    validateMetricValue(source, 'bulk_write_hold_removal_calls', effects.hold_removal_calls);
  }
}

function validateSource(source, parentLabel, sampleIndex, definition) {
  requireCondition(isObject(source), 'source artifact must be an object');
  requireCondition(!containsKnownSecret(source), 'source artifact contains a known credential');
  requireCondition(!containsSensitiveData(source), 'source artifact contains sensitive data');
  requireCondition(hasExactKeys(source, SOURCE_KEYS), 'source artifact contract is invalid');
  requireCondition(source.schema_version === SOURCE_SCHEMA_VERSION, 'source schema version is invalid');
  requireCondition(isObject(source.k6_summary), 'source k6 summary is invalid');
  validateMetadata(source.metadata, parentLabel, sampleIndex, definition);
  validateSuccessfulSummary(source, definition);
  validateDatabaseObservation(source, definition);
}

function artifactRootIsTrusted() {
  try {
    if (realpathSync(REPO_ROOT) !== REPO_ROOT) {
      return false;
    }
    const components = [
      REPO_ROOT,
      resolve(REPO_ROOT, 'build'),
      resolve(REPO_ROOT, 'build/k6'),
      ARTIFACT_ROOT,
    ];
    if (!components.every((component) => {
      const stat = lstatSync(component);
      return stat.isDirectory() && !stat.isSymbolicLink();
    })) {
      return false;
    }
    return realpathSync(ARTIFACT_ROOT) === ARTIFACT_ROOT;
  } catch (_) {
    return false;
  }
}

function requireTrustedArtifactRoot() {
  requireCondition(artifactRootIsTrusted(), 'artifact root is not a trusted canonical directory');
}

function absoluteArtifactPath(relativePath) {
  const absolutePath = resolve(REPO_ROOT, relativePath);
  requireCondition(
    dirname(absolutePath) === ARTIFACT_ROOT,
    'artifact path is outside the fixed repository boundary',
  );
  return absolutePath;
}

function pathEntryExists(path) {
  try {
    lstatSync(path);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return false;
    }
    throw new Error('artifact path cannot be inspected safely');
  }
}

function readSource(path, parentLabel, sampleIndex, byteBudget, definition) {
  requireTrustedArtifactRoot();
  const absolutePath = absoluteArtifactPath(path);
  let entry;
  try {
    entry = lstatSync(absolutePath);
  } catch (_) {
    throw new Error('source artifact cannot be opened safely');
  }
  requireCondition(
    entry.isFile() && !entry.isSymbolicLink(),
    'source artifact must be a regular non-symbolic-link file',
  );
  requireCondition(
    Number.isInteger(constants.O_NOFOLLOW),
    'runtime does not support no-follow artifact reads',
  );

  let descriptor;
  try {
    descriptor = openSync(absolutePath, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (_) {
    throw new Error('source artifact cannot be opened safely');
  }

  let contents;
  try {
    const before = fstatSync(descriptor);
    requireCondition(before.isFile(), 'source artifact must remain a regular file');
    requireCondition(before.size <= MAX_SOURCE_BYTES, 'source artifact is too large');
    contents = readFileSync(descriptor, 'utf8');
    const after = fstatSync(descriptor);
    const encodedBytes = Buffer.byteLength(contents, 'utf8');
    requireCondition(
      before.dev === after.dev
        && before.ino === after.ino
        && before.size === after.size
        && encodedBytes === after.size,
      'source artifact changed while it was read',
    );
    byteBudget.total += encodedBytes;
    requireCondition(
      byteBudget.total <= MAX_TOTAL_SOURCE_BYTES,
      'source artifacts exceed the cumulative size limit',
    );
  } finally {
    closeSync(descriptor);
  }
  requireTrustedArtifactRoot();

  let parsed;
  try {
    parsed = JSON.parse(contents);
  } catch (_) {
    throw new Error('source artifact cannot be read as JSON');
  }
  validateSource(parsed, parentLabel, sampleIndex, definition);
  return parsed;
}

function sharedMetadata(metadata, parentLabel, sampleCount) {
  return {
    candidate: metadata.candidate,
    variant: metadata.variant,
    phase: metadata.phase,
    dataset_size: metadata.dataset_size,
    samples: sampleCount,
    endpoint: metadata.endpoint,
    operation_name: metadata.operation_name,
    run_label: parentLabel,
    round: metadata.round,
    app_commit: metadata.app_commit,
    app_instance_count: metadata.app_instance_count,
    schema_label: metadata.schema_label,
    jvm_version: metadata.jvm_version,
    mysql_version: metadata.mysql_version,
    rewrite_batched_statements: metadata.rewrite_batched_statements,
    request_timeout: metadata.request_timeout,
  };
}

function requireMatchingMetadata(reference, candidate) {
  SHARED_METADATA_KEYS.forEach((key) => {
    requireCondition(
      candidate[key] === reference[key],
      'source public experiment metadata does not match',
    );
  });
}

function nearestRank(sortedValues, percentile) {
  const rank = Math.max(1, Math.ceil(percentile * sortedValues.length));
  return sortedValues[rank - 1];
}

function normalizeSource(source, sourcePath, sampleIndex, definition) {
  const externalEffects = definition.externalEffects
    ? {
      external_effects: {
        hold_removal_calls: source.database_observation.external_effects.hold_removal_calls,
        hold_removal_mode: source.database_observation.external_effects.hold_removal_mode,
        redis_network_excluded: source.database_observation.external_effects.redis_network_excluded,
      },
    }
    : {};
  return {
    metadata: Object.fromEntries(METADATA_KEYS.map((key) => [key, source.metadata[key]])),
    observation: {
      sample_index: sampleIndex,
      source_path: sourcePath,
      variant: source.metadata.variant,
      dataset_size: source.metadata.dataset_size,
      round: source.metadata.round,
      run_order: source.metadata.run_order,
      server_operation_ms: source.performance.server_operation_ms.median,
      verification: {
        succeeded: true,
        verified_rows: source.verification.verified_rows.median,
      },
      hibernate_statements_by_type: Object.fromEntries(SQL_TYPES.map((type) => [
        type,
        source.database_observation.hibernate_statements_by_type[type].median,
      ])),
      jdbc: {
        batch_calls: source.database_observation.jdbc.batch_calls,
        submitted_rows: source.database_observation.jdbc.submitted_rows,
        configured_batch_size: source.database_observation.jdbc.configured_batch_size,
        affected_rows: source.database_observation.jdbc.affected_rows,
      },
      ...externalEffects,
    },
  };
}

function buildCompanion(parentLabel, normalizedSources) {
  const reference = normalizedSources[0].metadata;
  normalizedSources.slice(1).forEach((source) => (
    requireMatchingMetadata(reference, source.metadata)
  ));
  const observations = normalizedSources.map(({ observation }) => observation);
  /* Parsed k6 summaries are intentionally not retained beyond normalizeSource(). */
  const sortedServerOperationMs = observations
    .map((observation) => observation.server_operation_ms)
    .sort((left, right) => left - right);

  return {
    schema_version: OBSERVATION_SCHEMA_VERSION,
    metadata: sharedMetadata(reference, parentLabel, normalizedSources.length),
    statistics: {
      percentile_algorithm: 'nearest-rank',
      percentile_definition: 'sort ascending; rank = max(1, ceil(p * n)); value = sorted[rank - 1]',
      server_operation_ms: {
        count: sortedServerOperationMs.length,
        min: sortedServerOperationMs[0],
        p50: nearestRank(sortedServerOperationMs, 0.50),
        p95: nearestRank(sortedServerOperationMs, 0.95),
        max: sortedServerOperationMs[sortedServerOperationMs.length - 1],
      },
    },
    observations,
  };
}

function parseArguments(args) {
  requireCondition(
    args.length >= 7,
    'candidate, output, parent label, and source artifacts are required',
  );
  requireCondition(
    args[0] === '--candidate'
      && args[2] === '--output'
      && args[4] === '--run-label',
    'arguments are invalid',
  );
  const candidate = args[1];
  const definition = benchmarkDefinition(candidate);
  const outputPath = args[3];
  const parentLabel = args[5];
  const sourcePaths = args.slice(6);
  requireCondition(
    !containsKnownSecret([candidate, outputPath, parentLabel, ...sourcePaths]),
    'public arguments contain a known credential',
  );
  requireCondition(
    typeof parentLabel === 'string'
      && /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(parentLabel)
      && parentLabel.length <= 128,
    'parent run label is invalid',
  );
  requireCondition(sourcePaths.length >= 1 && sourcePaths.length <= 100, 'source count is invalid');
  requireCondition(
    typeof outputPath === 'string'
      && /^build\/k6\/bulk-write\/[a-zA-Z0-9][a-zA-Z0-9._-]*\.json$/.test(outputPath)
      && outputPath.length <= 255,
    'output path is invalid',
  );
  requireCondition(new Set(sourcePaths).size === sourcePaths.length, 'source paths must be unique');
  sourcePaths.forEach((path, offset) => {
    requireCondition(
      path === `build/k6/bulk-write/${parentLabel}-sample-${String(offset + 1).padStart(3, '0')}.json`,
      'source paths must be ordered and canonical',
    );
  });
  requireCondition(!sourcePaths.includes(outputPath), 'output path must differ from every source');
  requireTrustedArtifactRoot();
  requireCondition(
    !pathEntryExists(absoluteArtifactPath(outputPath)),
    'output path already exists',
  );
  return { outputPath, parentLabel, sourcePaths, definition };
}

function writeCompanion(outputPath, companion) {
  requireTrustedArtifactRoot();
  const absoluteOutput = absoluteArtifactPath(outputPath);
  const temporary = resolve(
    ARTIFACT_ROOT,
    `.${basename(absoluteOutput)}.${process.pid}.tmp`,
  );
  requireCondition(
    Number.isInteger(constants.O_NOFOLLOW),
    'runtime does not support no-follow artifact writes',
  );
  requireCondition(!pathEntryExists(absoluteOutput), 'output path already exists');

  let descriptor;
  let temporaryCreated = false;
  try {
    try {
      descriptor = openSync(
        temporary,
        constants.O_WRONLY
          | constants.O_CREAT
          | constants.O_EXCL
          | constants.O_NOFOLLOW,
        0o600,
      );
    } catch (_) {
      throw new Error('temporary companion artifact cannot be opened safely');
    }
    temporaryCreated = true;
    writeFileSync(descriptor, `${JSON.stringify(companion, null, 2)}\n`, 'utf8');
    fsyncSync(descriptor);
    const written = fstatSync(descriptor);
    requireCondition(
      written.isFile() && (written.mode & 0o777) === 0o600,
      'temporary companion artifact permissions are invalid',
    );
    closeSync(descriptor);
    descriptor = undefined;

    requireTrustedArtifactRoot();
    const temporaryEntry = lstatSync(temporary);
    requireCondition(
      temporaryEntry.isFile() && !temporaryEntry.isSymbolicLink(),
      'temporary companion artifact is not a regular file',
    );
    requireCondition(!pathEntryExists(absoluteOutput), 'output path already exists');
    renameSync(temporary, absoluteOutput);
    temporaryCreated = false;
  } finally {
    if (descriptor !== undefined) {
      closeSync(descriptor);
    }
    if (temporaryCreated && pathEntryExists(temporary)) {
      unlinkSync(temporary);
    }
  }
}

try {
  const {
    outputPath,
    parentLabel,
    sourcePaths,
    definition,
  } = parseArguments(process.argv.slice(2));
  const byteBudget = { total: 0 };
  const normalizedSources = [];
  sourcePaths.forEach((path, index) => {
    const source = readSource(path, parentLabel, index + 1, byteBudget, definition);
    normalizedSources.push(normalizeSource(source, path, index + 1, definition));
  });
  const companion = buildCompanion(parentLabel, normalizedSources);
  writeCompanion(outputPath, companion);
} catch (error) {
  process.stderr.write(`bulk-write observation aggregation failed: ${error.message}\n`);
  process.exitCode = 1;
}

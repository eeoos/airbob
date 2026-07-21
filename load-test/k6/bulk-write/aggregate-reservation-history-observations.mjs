#!/usr/bin/env node

import {
  existsSync,
  readFileSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, resolve } from 'node:path';

const SOURCE_SCHEMA_VERSION = 'bulk-write-benchmark-v1';
const OBSERVATION_SCHEMA_VERSION = 'bulk-write-observations-v1';
const CANDIDATE = 'RESERVATION_HISTORY_INSERT';
const ENDPOINT = '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert';
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

function containsSensitiveData(value) {
  if (Array.isArray(value)) {
    return value.some(containsSensitiveData);
  }
  if (isObject(value)) {
    return Object.entries(value).some(([key, item]) => {
      const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
      const sensitiveKey = [
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
      return sensitiveKey || containsSensitiveData(item);
    });
  }
  if (typeof value !== 'string') {
    return false;
  }
  return /[^\s@]+@[^\s@]+\.[^\s@]+/.test(value)
    || /(?:bearer\s|password|secret|\btoken\b|session[_ -]?id|member[_ -]?id|user[_ -]?id|authorization|cookie|credential)/i
      .test(value);
}

function containsKnownSecret(value) {
  if (Array.isArray(value)) {
    return value.some(containsKnownSecret);
  }
  if (isObject(value)) {
    return Object.values(value).some(containsKnownSecret);
  }
  return typeof value === 'string'
    && KNOWN_SECRET_VALUES.some((secret) => value.includes(secret));
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

function validateMetricValue(source, name, expectedValue) {
  const values = metricValues(source, name);
  requireCondition(
    values.count === 1
      && typeof values.med === 'number'
      && Number.isFinite(values.med)
      && values.med === expectedValue,
    'required k6 metric value is inconsistent',
  );
}

function validateMetadata(metadata, parentLabel, sampleIndex) {
  requireCondition(hasExactKeys(metadata, METADATA_KEYS), 'source metadata contract is invalid');
  requireCondition(
    typeof metadata.generated_at === 'string'
      && Number.isFinite(Date.parse(metadata.generated_at)),
    'source generation timestamp is invalid',
  );
  requireCondition(metadata.candidate === CANDIDATE, 'source candidate is invalid');
  requireCondition(metadata.variant === 'BEFORE' || metadata.variant === 'AFTER', 'source variant is invalid');
  requireCondition(metadata.phase === 'measure', 'source phase must be measure');
  requireCondition(isNonNegativeInteger(metadata.dataset_size), 'source dataset size is invalid');
  requireCondition(metadata.samples === 1, 'source must contain exactly one sample');
  requireCondition(metadata.endpoint === ENDPOINT, 'source endpoint is invalid');
  requireCondition(
    metadata.operation_name
      === `expired-reservation-cleanup-${metadata.variant.toLowerCase()}`,
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

function validateSuccessfulSummary(source) {
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
  ['bulk_write_jdbc_batch_calls', 'bulk_write_jdbc_submitted_rows', 'bulk_write_hold_removal_calls']
    .forEach((name) => validateMetricCount(source, name, 1));
  SQL_TYPES.forEach((type) => {
    validateMetricCount(source, `bulk_write_hibernate_${type.toLowerCase()}_statements`, 1);
  });
}

function validateDatabaseObservation(source) {
  const observation = source.database_observation;
  requireCondition(hasExactKeys(observation, [
    'hibernate_statements_by_type',
    'jdbc',
    'external_effects',
  ]), 'source database observation contract is invalid');
  requireCondition(
    hasExactKeys(observation.hibernate_statements_by_type, SQL_TYPES),
    'source Hibernate observation contract is invalid',
  );

  const sql = {};
  SQL_TYPES.forEach((type) => {
    const value = observation.hibernate_statements_by_type[type];
    validateTrend(value, `Hibernate ${type}`);
    requireCondition(
      value.median === source.performance.hibernate_statements_by_type[type].median,
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
  if (variant === 'BEFORE' || datasetSize === 0) {
    requireCondition(
      jdbc.batch_calls === 0
        && jdbc.submitted_rows === 0
        && jdbc.configured_batch_size === null
        && jdbc.affected_rows === null
        && jdbc.affected_rows_known_samples === 0
        && jdbc.affected_rows_unknown_samples === 1,
      'source non-batch JDBC observation is invalid',
    );
    validateMetricCount(source, 'bulk_write_jdbc_configured_batch_size', 0);
    validateMetricCount(source, 'bulk_write_jdbc_affected_rows', 0);
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
      validateMetricCount(source, 'bulk_write_jdbc_affected_rows', 0);
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

function validateSource(source, parentLabel, sampleIndex) {
  requireCondition(isObject(source), 'source artifact must be an object');
  requireCondition(!containsKnownSecret(source), 'source artifact contains a known credential');
  requireCondition(!containsSensitiveData(source), 'source artifact contains sensitive data');
  requireCondition(hasExactKeys(source, SOURCE_KEYS), 'source artifact contract is invalid');
  requireCondition(source.schema_version === SOURCE_SCHEMA_VERSION, 'source schema version is invalid');
  requireCondition(isObject(source.k6_summary), 'source k6 summary is invalid');
  validateMetadata(source.metadata, parentLabel, sampleIndex);
  validateSuccessfulSummary(source);
  validateDatabaseObservation(source);
}

function readSource(path, parentLabel, sampleIndex) {
  let parsed;
  try {
    requireCondition(statSync(path).size <= 10 * 1024 * 1024, 'source artifact is too large');
    parsed = JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    if (error instanceof Error && error.message === 'source artifact is too large') {
      throw error;
    }
    throw new Error('source artifact cannot be read as JSON');
  }
  validateSource(parsed, parentLabel, sampleIndex);
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

function buildCompanion(parentLabel, sources, sourcePaths) {
  const reference = sources[0].metadata;
  sources.slice(1).forEach((source) => requireMatchingMetadata(reference, source.metadata));
  const observations = sources.map((source, index) => ({
    sample_index: index + 1,
    source_path: sourcePaths[index],
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
    external_effects: {
      hold_removal_calls: source.database_observation.external_effects.hold_removal_calls,
      hold_removal_mode: source.database_observation.external_effects.hold_removal_mode,
      redis_network_excluded: source.database_observation.external_effects.redis_network_excluded,
    },
  }));
  const sortedServerOperationMs = observations
    .map((observation) => observation.server_operation_ms)
    .sort((left, right) => left - right);

  return {
    schema_version: OBSERVATION_SCHEMA_VERSION,
    metadata: sharedMetadata(reference, parentLabel, sources.length),
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
  requireCondition(args.length >= 5, 'output, parent label, and source artifacts are required');
  requireCondition(args[0] === '--output' && args[2] === '--run-label', 'arguments are invalid');
  const outputPath = args[1];
  const parentLabel = args[3];
  const sourcePaths = args.slice(4);
  requireCondition(
    !containsKnownSecret([outputPath, parentLabel, ...sourcePaths]),
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
  requireCondition(!existsSync(outputPath), 'output path already exists');
  return { outputPath, parentLabel, sourcePaths };
}

function writeCompanion(outputPath, companion) {
  const absoluteOutput = resolve(outputPath);
  const temporary = resolve(
    dirname(absoluteOutput),
    `.${basename(absoluteOutput)}.${process.pid}.tmp`,
  );
  try {
    writeFileSync(temporary, `${JSON.stringify(companion, null, 2)}\n`, {
      encoding: 'utf8',
      flag: 'wx',
      mode: 0o600,
    });
    renameSync(temporary, absoluteOutput);
  } finally {
    if (existsSync(temporary)) {
      unlinkSync(temporary);
    }
  }
}

try {
  const { outputPath, parentLabel, sourcePaths } = parseArguments(process.argv.slice(2));
  const sources = sourcePaths.map((path, index) => readSource(path, parentLabel, index + 1));
  const companion = buildCompanion(parentLabel, sources, sourcePaths);
  writeCompanion(outputPath, companion);
} catch (error) {
  process.stderr.write(`reservation observation aggregation failed: ${error.message}\n`);
  process.exitCode = 1;
}

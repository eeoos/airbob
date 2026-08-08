import { check } from 'k6';

import {
  RESERVATION_HISTORY_INSERT_BENCHMARK,
  bulkWriteOperationName,
  buildBulkWriteArtifact,
  buildBulkWriteOptions,
  buildBulkWriteRequestBody,
  matchesBulkWriteResponseContract,
  parseBulkWriteRunConfig,
  parseBulkWriteVariant,
  parseDatasetSize,
} from '../lib/bulk-write-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const TOKEN = '0123456789abcdef0123456789abcdef';
const BENCHMARK = RESERVATION_HISTORY_INSERT_BENCHMARK;

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function operation(datasetSize, overrides = {}, variant = 'BEFORE') {
  return {
    operation_name: `expired-reservation-cleanup-${variant.toLowerCase()}`,
    outcome: 'SUCCESS',
    server_operation_nanos: 12_500_000,
    server_operation_ms: 12.5,
    hibernate_statements_by_type: {
      SELECT: 1,
      INSERT: datasetSize,
      UPDATE: datasetSize,
      DELETE: 0,
      OTHER: 0,
      TOTAL: 1 + (datasetSize * 2),
    },
    jdbc_batch_calls: 0,
    jdbc_submitted_rows: 0,
    jdbc_configured_batch_size: null,
    jdbc_affected_rows: null,
    ...overrides,
  };
}

function payload(datasetSize, overrides = {}, variant = 'BEFORE') {
  return {
    success: true,
    data: {
      candidate: 'RESERVATION_HISTORY_INSERT',
      variant,
      dataset_size: datasetSize,
      expected_rows: datasetSize,
      verified_rows: datasetSize,
      verification_succeeded: true,
      target_reservations_expired: true,
      target_histories_inserted: true,
      future_pending_preserved: true,
      non_pending_expired_preserved: true,
      history_snapshots_preserved: true,
      history_audit_context_preserved: true,
      hold_removals_matched: true,
      hold_removal_calls: datasetSize,
      redis_network_excluded: true,
      operation: operation(datasetSize, {}, variant),
      ...overrides,
    },
  };
}

function summary(datasetSize) {
  return {
    metrics: {
      bulk_write_sample_success: { values: { passes: 1, fails: 0, rate: 1 } },
      bulk_write_verification_success: { values: { passes: 1, fails: 0, rate: 1 } },
      bulk_write_server_operation_ms: {
        values: { count: 1, avg: 12.5, min: 12.5, med: 12.5, max: 12.5 },
      },
      bulk_write_http_orchestration_ms: {
        values: { count: 1, avg: 20, min: 20, med: 20, max: 20 },
      },
      bulk_write_verified_rows: {
        values: { count: 1, avg: datasetSize, min: datasetSize, med: datasetSize, max: datasetSize },
      },
      bulk_write_hibernate_select_statements: {
        values: { count: 1, avg: 1, min: 1, med: 1, max: 1 },
      },
      bulk_write_hibernate_insert_statements: {
        values: { count: 1, avg: datasetSize, min: datasetSize, med: datasetSize, max: datasetSize },
      },
      bulk_write_hibernate_update_statements: {
        values: { count: 1, avg: datasetSize, min: datasetSize, med: datasetSize, max: datasetSize },
      },
      bulk_write_hibernate_delete_statements: {
        values: { count: 1, avg: 0, min: 0, med: 0, max: 0 },
      },
      bulk_write_hibernate_other_statements: {
        values: { count: 1, avg: 0, min: 0, med: 0, max: 0 },
      },
      bulk_write_hibernate_total_statements: {
        values: {
          count: 1,
          avg: 1 + (datasetSize * 2),
          min: 1 + (datasetSize * 2),
          med: 1 + (datasetSize * 2),
          max: 1 + (datasetSize * 2),
        },
      },
      bulk_write_jdbc_batch_calls: { values: { count: 1, avg: 0, min: 0, med: 0, max: 0 } },
      bulk_write_jdbc_submitted_rows: { values: { count: 1, avg: 0, min: 0, med: 0, max: 0 } },
      bulk_write_jdbc_configured_batch_size: { values: { count: 0 } },
      bulk_write_jdbc_affected_rows: { values: { count: 0 } },
      bulk_write_hold_removal_calls: {
        values: {
          count: 1,
          avg: datasetSize,
          min: datasetSize,
          med: datasetSize,
          max: datasetSize,
        },
      },
    },
    root_group: { name: '', checks: [], groups: [] },
    state: { isStdOutTTY: false, testRunDurationMs: 100 },
  };
}

export default function () {
  const config = parseBulkWriteRunConfig({
    BASE_URL: 'http://localhost:8080',
    VARIANT: 'BEFORE',
    PHASE: 'measure',
    DATASET_SIZE: '2000',
    SAMPLES: '1',
    BENCHMARK_BULK_WRITE_TOKEN: TOKEN,
    ROUND: '1',
    RUN_ORDER: '1',
    APP_COMMIT: 'abc123',
    APP_INSTANCE_COUNT: '1',
    SCHEMA_LABEL: 'airbob-bulk-write-v1',
    JVM_VERSION: '21.0.7',
    MYSQL_VERSION: '8.0.42',
    REWRITE_BATCHED_STATEMENTS: 'false',
  }, BENCHMARK);
  const benchmarkOptions = buildBulkWriteOptions(config, BENCHMARK);
  const valid = payload(3);
  const zero = payload(0);
  const afterOperation = operation(3, {
    hibernate_statements_by_type: {
      SELECT: 1, INSERT: 0, UPDATE: 3, DELETE: 0, OTHER: 0, TOTAL: 4,
    },
    jdbc_batch_calls: 2,
    jdbc_submitted_rows: 3,
    jdbc_configured_batch_size: 2,
    jdbc_affected_rows: 3,
  }, 'AFTER');
  const validAfter = payload(3, { operation: afterOperation }, 'AFTER');
  const afterZero = payload(0, {
    operation: operation(0, {
      hibernate_statements_by_type: {
        SELECT: 1, INSERT: 0, UPDATE: 0, DELETE: 0, OTHER: 0, TOTAL: 1,
      },
    }, 'AFTER'),
  }, 'AFTER');
  const artifact = buildBulkWriteArtifact({
    config,
    k6Summary: summary(2000),
    generatedAt: '2026-07-21T00:00:00.000Z',
  }, BENCHMARK);
  const afterArtifact = buildBulkWriteArtifact({
    config: { ...config, variant: 'AFTER' },
    k6Summary: {
      ...summary(3),
      metrics: {
        ...summary(3).metrics,
        bulk_write_jdbc_batch_calls: { values: { count: 1, avg: 2, min: 2, med: 2, max: 2 } },
        bulk_write_jdbc_submitted_rows: { values: { count: 1, avg: 3, min: 3, med: 3, max: 3 } },
        bulk_write_jdbc_configured_batch_size: { values: { count: 1, avg: 2, min: 2, med: 2, max: 2 } },
        bulk_write_jdbc_affected_rows: { values: { count: 1, avg: 3, min: 3, med: 3, max: 3 } },
      },
    },
    generatedAt: '2026-07-21T00:00:00.000Z',
  }, BENCHMARK);

  check(null, {
    'reservation dataset maximum is 2000': () => (
      parseDatasetSize('2000', BENCHMARK) === 2000
        && rejects(() => parseDatasetSize('2001', BENCHMARK))
    ),
    'reservation candidate supports BEFORE and AFTER': () => (
      parseBulkWriteVariant('BEFORE', BENCHMARK) === 'BEFORE'
        && parseBulkWriteVariant('AFTER', BENCHMARK) === 'AFTER'
    ),
    'reservation operation name and result path are isolated': () => (
      bulkWriteOperationName('BEFORE', BENCHMARK) === 'expired-reservation-cleanup-before'
        && config.resultPath === (
          'build/k6/bulk-write/expired-reservation-cleanup-before-n2000-measure-r1-o1.json'
        )
    ),
    'reservation request body keeps exact variant and dataset': () => {
      const body = JSON.parse(buildBulkWriteRequestBody({
        variant: 'BEFORE',
        datasetSize: 2000,
      }, BENCHMARK));
      return body.variant === 'BEFORE' && body.dataset_size === 2000;
    },
    'reservation options tag the correct candidate': () => (
      benchmarkOptions.scenarios.bulk_write_measure.tags.candidate
        === 'RESERVATION_HISTORY_INSERT'
    ),
    'valid N and zero-row contracts are accepted': () => (
      matchesBulkWriteResponseContract(valid, 3, 'BEFORE', BENCHMARK)
        && matchesBulkWriteResponseContract(zero, 0, 'BEFORE', BENCHMARK)
    ),
    'valid AFTER N=3 batch=2 and AFTER N=0 contracts are accepted': () => (
      matchesBulkWriteResponseContract(validAfter, 3, 'AFTER', BENCHMARK)
        && matchesBulkWriteResponseContract(afterZero, 0, 'AFTER', BENCHMARK)
    ),
    'AFTER rejects wrong batch call count': () => !matchesBulkWriteResponseContract(
      payload(3, { operation: { ...afterOperation, jdbc_batch_calls: 1 } }, 'AFTER'),
      3,
      'AFTER',
      BENCHMARK,
    ),
    'AFTER rejects wrong submitted rows': () => !matchesBulkWriteResponseContract(
      payload(3, { operation: { ...afterOperation, jdbc_submitted_rows: 2 } }, 'AFTER'),
      3,
      'AFTER',
      BENCHMARK,
    ),
    'AFTER rejects wrong configured batch size': () => !matchesBulkWriteResponseContract(
      payload(3, { operation: { ...afterOperation, jdbc_configured_batch_size: 3 } }, 'AFTER'),
      3,
      'AFTER',
      BENCHMARK,
    ),
    'wrong identity insert count is rejected': () => !matchesBulkWriteResponseContract(
      payload(3, { operation: operation(3, {
        hibernate_statements_by_type: {
          SELECT: 1, INSERT: 2, UPDATE: 3, DELETE: 0, OTHER: 0, TOTAL: 6,
        },
      }) }),
      3,
      'BEFORE',
      BENCHMARK,
    ),
    'wrong hold calls or Redis mode are rejected': () => (
      !matchesBulkWriteResponseContract(
        payload(3, { hold_removal_calls: 2 }), 3, 'BEFORE', BENCHMARK,
      )
        && !matchesBulkWriteResponseContract(
          payload(3, { redis_network_excluded: false }), 3, 'BEFORE', BENCHMARK,
        )
    ),
    'reservation artifact records its own metadata': () => (
      artifact.metadata.candidate === 'RESERVATION_HISTORY_INSERT'
        && artifact.metadata.endpoint
          === '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert'
        && artifact.metadata.operation_name === 'expired-reservation-cleanup-before'
        && artifact.metadata.dataset_size === 2000
    ),
    'reservation artifact makes Redis exclusion and logical hold calls explicit': () => (
      artifact.database_observation.external_effects.redis_network_excluded === true
        && artifact.database_observation.external_effects.hold_removal_mode === 'RECORDED_NO_IO'
        && artifact.database_observation.external_effects.hold_removal_calls === 2000
    ),
    'reservation artifacts preserve optional JDBC medians': () => (
      artifact.database_observation.jdbc.configured_batch_size === null
        && artifact.database_observation.jdbc.affected_rows === null
        && afterArtifact.database_observation.jdbc.configured_batch_size === 2
        && afterArtifact.database_observation.jdbc.affected_rows === 3
    ),
  });
}

import { check } from 'k6';

import {
  ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
  accommodationAmenityServerOperationMetricName,
  buildBulkWriteArtifact,
  buildBulkWriteRequestBody,
  bulkWriteOperationName,
  matchesBulkWriteResponseContract,
  parseBulkWriteRunConfig,
} from '../lib/bulk-write-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const TOKEN = '0123456789abcdef0123456789abcdef';

function operation(variant, measurement, datasetSize, activeCodeCount) {
	const replacementRows = measurement === 'FULL_REPLACEMENT'
		? Math.min(datasetSize, activeCodeCount)
		: 0;
	const fullReplacement = measurement === 'FULL_REPLACEMENT';
	const before = variant === 'BEFORE';
	return {
		operation_name: ACCOMMODATION_AMENITY_DELETE_BENCHMARK.operationName(
			variant,
			measurement,
		),
    outcome: 'SUCCESS',
    server_operation_nanos: 1_000_000,
    server_operation_ms: 1,
    hibernate_statements_by_type: {
			SELECT: fullReplacement ? (before ? 3 : 2) : (before ? 1 : 0),
			INSERT: fullReplacement ? replacementRows + 1 : 0,
			UPDATE: fullReplacement ? 1 : 0,
			DELETE: before ? datasetSize : 1,
			OTHER: 0,
			TOTAL: fullReplacement
				? (before ? datasetSize + replacementRows + 5 : replacementRows + 5)
				: (before ? datasetSize + 1 : 1),
    },
    jdbc_batch_calls: 0,
    jdbc_submitted_rows: 0,
    jdbc_configured_batch_size: null,
    jdbc_affected_rows: null,
  };
}

function payload(variant, measurement, datasetSize, activeCodeCount) {
  const replacementRows = measurement === 'FULL_REPLACEMENT'
    ? Math.min(datasetSize, activeCodeCount)
    : 0;
  const expectedMap = {};
  for (let index = 0; index < replacementRows; index += 1) {
    expectedMap[`AMENITY_${index}`] = index + 1;
  }
  return {
    success: true,
    data: {
      candidate: 'ACCOMMODATION_AMENITY_DELETE',
		variant,
      measurement,
      workload_class: datasetSize <= activeCodeCount ? 'REALISTIC' : 'STRESS',
      active_amenity_code_count: activeCodeCount,
      dataset_size: datasetSize,
      old_target_rows_expected: datasetSize,
      old_target_rows_deleted: datasetSize,
      old_target_rows_verified: datasetSize,
      replacement_rows_expected: replacementRows,
      replacement_rows_verified: replacementRows,
      replacement_map_expected: expectedMap,
      replacement_map_verified: expectedMap,
      target_parent_preserved: true,
      history_effect_matched: true,
      control_accommodation_preserved: true,
      control_amenities_preserved: true,
      verification_succeeded: true,
		operation: operation(variant, measurement, datasetSize, activeCodeCount),
    },
  };
}

function summary(activeCodeCount) {
  const trend = (value) => ({
    values: {
      count: 1,
      avg: value,
      min: value,
      med: value,
      'p(90)': value,
      'p(95)': value,
      'p(99)': value,
      max: value,
    },
  });
  return {
    metrics: {
      bulk_write_sample_success: { values: { passes: 1, fails: 0 } },
      bulk_write_verification_success: { values: { passes: 1, fails: 0 } },
      bulk_write_server_operation_ms: trend(1),
      bulk_write_http_orchestration_ms: trend(2),
      bulk_write_verified_rows: trend(31),
      bulk_write_active_amenity_code_count: trend(activeCodeCount),
      bulk_write_hibernate_select_statements: trend(3),
      bulk_write_hibernate_insert_statements: trend(31),
      bulk_write_hibernate_update_statements: trend(1),
      bulk_write_hibernate_delete_statements: trend(31),
      bulk_write_hibernate_other_statements: trend(0),
      bulk_write_hibernate_total_statements: trend(66),
      bulk_write_jdbc_batch_calls: trend(0),
      bulk_write_jdbc_submitted_rows: trend(0),
    },
  };
}

function config(variant, measurement, datasetSize) {
	return parseBulkWriteRunConfig({
		VARIANT: variant,
		MEASUREMENT: measurement,
		DATASET_SIZE: String(datasetSize),
    SAMPLES: '1',
    PHASE: 'measure',
    BENCHMARK_BULK_WRITE_TOKEN: TOKEN,
    ROUND: '1',
    RUN_ORDER: '1',
    APP_COMMIT: 'abc123',
    SCHEMA_LABEL: 'disposable-mysql',
    JVM_VERSION: '21',
    MYSQL_VERSION: '8.0.33',
    REWRITE_BATCHED_STATEMENTS: 'false',
  }, ACCOMMODATION_AMENITY_DELETE_BENCHMARK);
}

export default function () {
	const fullBefore = config('BEFORE', 'FULL_REPLACEMENT', 31);
	const deleteBefore = config('BEFORE', 'DELETE_ONLY', 31);
	const fullAfterEmpty = config('AFTER', 'FULL_REPLACEMENT', 0);
	const deleteAfterEmpty = config('AFTER', 'DELETE_ONLY', 0);
	const artifact = buildBulkWriteArtifact({
		config: fullBefore,
    k6Summary: summary(30),
  }, ACCOMMODATION_AMENITY_DELETE_BENCHMARK);

  check(null, {
    'candidate uses the protected accommodation amenity endpoint': () => (
      ACCOMMODATION_AMENITY_DELETE_BENCHMARK.endpoint
        === '/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete'
			&& ACCOMMODATION_AMENITY_DELETE_BENCHMARK.supportedVariants.length === 2
			&& ACCOMMODATION_AMENITY_DELETE_BENCHMARK.supportedVariants[0] === 'BEFORE'
			&& ACCOMMODATION_AMENITY_DELETE_BENCHMARK.supportedVariants[1] === 'AFTER'
	),
		'measurement and variant are canonical and isolate operation/result names': () => (
			fullBefore.measurement === 'FULL_REPLACEMENT'
			&& deleteBefore.measurement === 'DELETE_ONLY'
			&& fullAfterEmpty.variant === 'AFTER'
			&& deleteAfterEmpty.variant === 'AFTER'
			&& fullBefore.resultPath.includes('accommodation-amenity-full-replacement-before')
			&& deleteBefore.resultPath.includes('accommodation-amenity-delete-only-before')
			&& fullAfterEmpty.resultPath.includes('accommodation-amenity-full-replacement-after')
			&& deleteAfterEmpty.resultPath.includes('accommodation-amenity-delete-only-after')
      && bulkWriteOperationName(
        'BEFORE',
        ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
        'FULL_REPLACEMENT',
      ) === 'accommodation-amenity-full-replacement-before'
    ),
		'request includes exact Before and After variants with measurement': () => (
			buildBulkWriteRequestBody(fullBefore, ACCOMMODATION_AMENITY_DELETE_BENCHMARK)
				=== '{"variant":"BEFORE","measurement":"FULL_REPLACEMENT","dataset_size":31}'
			&& buildBulkWriteRequestBody(deleteAfterEmpty, ACCOMMODATION_AMENITY_DELETE_BENCHMARK)
				=== '{"variant":"AFTER","measurement":"DELETE_ONLY","dataset_size":0}'
	),
		'Before full replacement validates the observed N+R+5 formula': () => (
			matchesBulkWriteResponseContract(
				payload('BEFORE', 'FULL_REPLACEMENT', 31, 30),
        31,
        'BEFORE',
        ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
        'FULL_REPLACEMENT',
      )
    ),
		'Before delete-only validates SELECT 1 plus DELETE N separately': () => (
			matchesBulkWriteResponseContract(
				payload('BEFORE', 'DELETE_ONLY', 31, 30),
        31,
        'BEFORE',
        ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
        'DELETE_ONLY',
      )
		),
		'After full replacement N=0 validates one predicate DELETE': () => (
			matchesBulkWriteResponseContract(
				payload('AFTER', 'FULL_REPLACEMENT', 0, 30),
				0,
				'AFTER',
				ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
				'FULL_REPLACEMENT',
			)
		),
		'After delete-only N=0 validates one predicate DELETE': () => (
			matchesBulkWriteResponseContract(
				payload('AFTER', 'DELETE_ONLY', 0, 30),
				0,
				'AFTER',
				ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
				'DELETE_ONLY',
			)
		),
		'workload classification cannot be mislabeled': () => {
			const mislabeled = payload('BEFORE', 'DELETE_ONLY', 31, 30);
      mislabeled.data.workload_class = 'REALISTIC';
      return !matchesBulkWriteResponseContract(
        mislabeled,
        31,
        'BEFORE',
        ACCOMMODATION_AMENITY_DELETE_BENCHMARK,
        'DELETE_ONLY',
      );
    },
    'artifact records measurement workload class and runtime active count': () => (
      artifact.metadata.measurement === 'FULL_REPLACEMENT'
      && artifact.metadata.workload_class === 'STRESS'
      && artifact.metadata.active_amenity_code_count === 30
    ),
    'measurement Trends have distinct names': () => (
      accommodationAmenityServerOperationMetricName('FULL_REPLACEMENT')
        === 'bulk_write_accommodation_amenity_full_replacement_server_operation_ms'
      && accommodationAmenityServerOperationMetricName('DELETE_ONLY')
        === 'bulk_write_accommodation_amenity_delete_only_server_operation_ms'
    ),
  });
}

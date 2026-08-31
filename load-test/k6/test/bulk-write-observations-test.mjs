import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  copyFileSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const testDir = dirname(fileURLToPath(import.meta.url));
const aggregator = resolve(
  testDir,
  '../bulk-write/aggregate-bulk-write-observations.mjs',
);
const SQL_TYPES = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'OTHER', 'TOTAL'];

function trend(value, count = 1) {
  return {
    count,
    avg: value,
    min: value,
    median: value,
    p90: value,
    p95: value,
    p99: value,
    max: value,
  };
}

function metric(value, count = 1) {
  return {
    values: {
      count,
      avg: value,
      min: value,
      med: value,
      'p(90)': value,
      'p(95)': value,
      'p(99)': value,
      max: value,
    },
  };
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function sourceArtifact({
  index,
  parentLabel = 'reservation-after-n25-r1',
  variant = 'AFTER',
  datasetSize = 25,
  serverOperationMs = index,
  affectedRows = datasetSize,
  runOrder = 2,
} = {}) {
  const isBefore = variant === 'BEFORE';
  const isAfterEmpty = variant === 'AFTER' && datasetSize === 0;
  const sql = isBefore
    ? {
      SELECT: 1,
      INSERT: datasetSize,
      UPDATE: datasetSize,
      DELETE: 0,
      OTHER: 0,
      TOTAL: 1 + (datasetSize * 2),
    }
    : {
      SELECT: 1,
      INSERT: 0,
      UPDATE: isAfterEmpty ? 0 : datasetSize + 1,
      DELETE: 0,
      OTHER: 0,
      TOTAL: isAfterEmpty ? 1 : datasetSize + 2,
    };
  const batchCalls = isBefore || isAfterEmpty ? 0 : 1;
  const submittedRows = isBefore || isAfterEmpty ? 0 : datasetSize;
  const configuredBatchSize = isBefore || isAfterEmpty ? null : datasetSize;
  const exactAffectedRows = isBefore || isAfterEmpty ? null : affectedRows;
  const knownAffectedRows = exactAffectedRows === null ? 0 : 1;
  const hibernate = Object.fromEntries(SQL_TYPES.map((type) => [type, trend(sql[type])]));
  const childLabel = `${parentLabel}-sample-${String(index).padStart(3, '0')}`;
  const operationName = `expired-reservation-cleanup-${variant.toLowerCase()}`;
  const metrics = {
    bulk_write_sample_success: { values: { passes: 1, fails: 0, rate: 1 } },
    bulk_write_verification_success: { values: { passes: 1, fails: 0, rate: 1 } },
    bulk_write_server_operation_ms: metric(serverOperationMs),
    bulk_write_http_orchestration_ms: metric(serverOperationMs + 5),
    bulk_write_verified_rows: metric(datasetSize),
    bulk_write_jdbc_batch_calls: metric(batchCalls),
    bulk_write_jdbc_submitted_rows: metric(submittedRows),
  };
  SQL_TYPES.forEach((type) => {
    metrics[`bulk_write_hibernate_${type.toLowerCase()}_statements`] = metric(sql[type]);
  });
  if (configuredBatchSize !== null) {
    metrics.bulk_write_jdbc_configured_batch_size = metric(configuredBatchSize);
  }
  if (exactAffectedRows !== null) {
    metrics.bulk_write_jdbc_affected_rows = metric(exactAffectedRows);
  }

  const successfulRate = {
    attempted: 1,
    successful: 1,
    failed: 0,
    success_rate: 1,
  };
  return {
    schema_version: 'bulk-write-benchmark-v1',
    metadata: {
      generated_at: `2026-07-21T00:00:${String(index).padStart(2, '0')}.000Z`,
      candidate: 'RESERVATION_HISTORY_INSERT',
      variant,
      phase: 'measure',
      dataset_size: datasetSize,
      samples: 1,
      endpoint: '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert',
      operation_name: operationName,
      run_label: childLabel,
      round: 1,
      run_order: runOrder,
      app_commit: 'abc123',
      app_instance_count: 1,
      schema_label: 'airbob-bulk-write-v1',
      jvm_version: '21.0.7',
      mysql_version: '8.0.42',
      rewrite_batched_statements: true,
      request_timeout: '30s',
      benchmark_dataset_version: 'benchmark-dataset-v2',
      benchmark_world_version: 'world-v2',
      benchmark_dataset_capsule_id: 'bulk-expiration-history-v1',
      benchmark_dataset_target_id: 'expired-payment-pending',
      benchmark_dataset_manifest_sha256: 'a'.repeat(64),
    },
    performance: {
      samples: successfulRate,
      verification: successfulRate,
      server_operation_ms: trend(serverOperationMs),
      http_orchestration_ms: trend(serverOperationMs + 5),
      verified_rows: trend(datasetSize),
      hibernate_statements_by_type: clone(hibernate),
    },
    verification: {
      expected_rows: datasetSize,
      verified_rows: trend(datasetSize),
      succeeded: successfulRate,
    },
    database_observation: {
      hibernate_statements_by_type: clone(hibernate),
      jdbc: {
        batch_calls: batchCalls,
        submitted_rows: submittedRows,
        configured_batch_size: configuredBatchSize,
        affected_rows: exactAffectedRows,
        affected_rows_known_samples: knownAffectedRows,
        affected_rows_unknown_samples: 1 - knownAffectedRows,
      },
    },
    k6_summary: {
      metrics,
      root_group: { name: '', checks: [], groups: [] },
      state: { isStdOutTTY: false, testRunDurationMs: 100 },
      source_only_safe_field: 'must not be copied',
    },
  };
}

function wishlistSourceArtifact({
  index,
  parentLabel = 'wishlist-after-n25-r1',
  variant = 'AFTER',
  datasetSize = 25,
  serverOperationMs = index,
} = {}) {
  const artifact = sourceArtifact({
    index,
    parentLabel,
    variant: 'BEFORE',
    datasetSize,
    serverOperationMs,
  });
  const deleteCount = datasetSize === 0
    ? 0
    : (variant === 'BEFORE' ? datasetSize : 1);
  const sql = {
    SELECT: 2,
    INSERT: 0,
    UPDATE: 1,
    DELETE: deleteCount,
    OTHER: 0,
    TOTAL: 3 + deleteCount,
  };
  const hibernate = Object.fromEntries(SQL_TYPES.map((type) => [type, trend(sql[type])]));

  artifact.metadata.candidate = 'WISHLIST_DELETE';
  artifact.metadata.variant = variant;
  artifact.metadata.endpoint = '/api/v2/admin/benchmarks/bulk-write/wishlist-delete';
  artifact.metadata.operation_name = `wishlist-delete-${variant.toLowerCase()}`;
  [
    'benchmark_dataset_version',
    'benchmark_world_version',
    'benchmark_dataset_capsule_id',
    'benchmark_dataset_target_id',
    'benchmark_dataset_manifest_sha256',
  ].forEach((key) => delete artifact.metadata[key]);
  artifact.performance.hibernate_statements_by_type = clone(hibernate);
  artifact.database_observation.hibernate_statements_by_type = clone(hibernate);
  artifact.database_observation.jdbc = {
    batch_calls: 0,
    submitted_rows: 0,
    configured_batch_size: null,
    affected_rows: null,
    affected_rows_known_samples: 0,
    affected_rows_unknown_samples: 1,
  };
  delete artifact.database_observation.external_effects;

  artifact.k6_summary.metrics.bulk_write_jdbc_batch_calls = metric(0);
  artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows = metric(0);
  delete artifact.k6_summary.metrics.bulk_write_jdbc_configured_batch_size;
  delete artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows;
  delete artifact.k6_summary.metrics.bulk_write_hold_removal_calls;
  SQL_TYPES.forEach((type) => {
    artifact.k6_summary.metrics[`bulk_write_hibernate_${type.toLowerCase()}_statements`]
      = metric(sql[type]);
  });
  return artifact;
}

function accommodationAmenitySourceArtifact({
	index,
	parentLabel = 'amenity-full-before-n31-r1',
	variant = 'BEFORE',
	measurement = 'FULL_REPLACEMENT',
  datasetSize = 31,
  activeCodeCount = 30,
  serverOperationMs = index,
} = {}) {
	const artifact = wishlistSourceArtifact({
		index,
		parentLabel,
		variant,
    datasetSize,
    serverOperationMs,
  });
  const replacementRows = measurement === 'FULL_REPLACEMENT'
    ? Math.min(datasetSize, activeCodeCount)
    : 0;
	const sql = measurement === 'FULL_REPLACEMENT' && variant === 'BEFORE'
		? {
      SELECT: 3,
      INSERT: replacementRows + 1,
      UPDATE: 1,
      DELETE: datasetSize,
      OTHER: 0,
      TOTAL: datasetSize + replacementRows + 5,
    }
		: measurement === 'DELETE_ONLY' && variant === 'BEFORE'
			? {
		SELECT: 1,
      INSERT: 0,
      UPDATE: 0,
      DELETE: datasetSize,
      OTHER: 0,
		TOTAL: datasetSize + 1,
		}
			: measurement === 'FULL_REPLACEMENT'
				? {
				SELECT: 2,
				INSERT: replacementRows + 1,
				UPDATE: 1,
				DELETE: 1,
				OTHER: 0,
				TOTAL: replacementRows + 5,
				}
				: {
				SELECT: 0,
				INSERT: 0,
				UPDATE: 0,
				DELETE: 1,
				OTHER: 0,
				TOTAL: 1,
				};
  const hibernate = Object.fromEntries(SQL_TYPES.map((type) => [type, trend(sql[type])]));
  const metricSuffix = measurement === 'FULL_REPLACEMENT'
    ? 'full_replacement'
    : 'delete_only';

	artifact.metadata.candidate = 'ACCOMMODATION_AMENITY_DELETE';
	artifact.metadata.variant = variant;
  artifact.metadata.measurement = measurement;
  artifact.metadata.workload_class = datasetSize <= activeCodeCount ? 'REALISTIC' : 'STRESS';
  artifact.metadata.active_amenity_code_count = activeCodeCount;
  artifact.metadata.endpoint =
    '/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete';
	artifact.metadata.operation_name = measurement === 'FULL_REPLACEMENT'
		? `accommodation-amenity-full-replacement-${variant.toLowerCase()}`
		: `accommodation-amenity-delete-only-${variant.toLowerCase()}`;
  artifact.performance.hibernate_statements_by_type = clone(hibernate);
  artifact.database_observation.hibernate_statements_by_type = clone(hibernate);
  artifact.k6_summary.metrics.bulk_write_active_amenity_code_count = metric(activeCodeCount);
  artifact.k6_summary.metrics[
    `bulk_write_accommodation_amenity_${metricSuffix}_server_operation_ms`
  ] = metric(serverOperationMs);
  SQL_TYPES.forEach((type) => {
    artifact.k6_summary.metrics[`bulk_write_hibernate_${type.toLowerCase()}_statements`]
      = metric(sql[type]);
  });
  return artifact;
}

function createCase(name = 'case') {
  const directory = mkdtempSync(resolve(tmpdir(), `reservation-observations-${name}-`));
  const copiedAggregator = resolve(
    directory,
    'load-test/k6/bulk-write/aggregate-bulk-write-observations.mjs',
  );
  mkdirSync(dirname(copiedAggregator), { recursive: true });
  copyFileSync(aggregator, copiedAggregator);
  mkdirSync(resolve(directory, 'build/k6/bulk-write'), { recursive: true });
  return directory;
}

function sourcePath(parentLabel, index) {
  return `build/k6/bulk-write/${parentLabel}-sample-${String(index).padStart(3, '0')}.json`;
}

function writeSource(directory, parentLabel, index, artifact) {
  const path = sourcePath(parentLabel, index);
  writeFileSync(resolve(directory, path), JSON.stringify(artifact, null, 2));
  return path;
}

function runAggregator(
  directory,
  parentLabel,
  sources,
  outputName = `${parentLabel}-observations.json`,
  environment = process.env,
  workingDirectory = directory,
  candidate = 'RESERVATION_HISTORY_INSERT',
) {
  const output = `build/k6/bulk-write/${outputName}`;
  const argumentsList = [
    resolve(directory, 'load-test/k6/bulk-write/aggregate-bulk-write-observations.mjs'),
    '--candidate',
    candidate,
  ];
  argumentsList.push(
    '--output',
    output,
    '--run-label',
    parentLabel,
    ...sources,
  );
  const result = spawnSync(process.execPath, argumentsList, {
    cwd: workingDirectory,
    encoding: 'utf8',
    env: environment,
  });
  return { ...result, output };
}

function assertRejected(result, directory, secret = undefined) {
  assert.notEqual(result.status, 0, `unexpected success: ${result.stdout}`);
  assert.throws(() => readFileSync(resolve(directory, result.output)), /ENOENT/);
  if (secret !== undefined) {
    assert.equal(`${result.stdout}${result.stderr}`.includes(secret), false);
  }
}

test('resolves the fixed repository artifact root independently of the caller working directory', () => {
  const directory = createCase('fixed-root');
  try {
    const parentLabel = 'reservation-after-fixed-root-r1';
    const source = writeSource(
      directory,
      parentLabel,
      1,
      sourceArtifact({ index: 1, parentLabel }),
    );
    const result = runAggregator(
      directory,
      parentLabel,
      [source],
      `${parentLabel}-observations.json`,
      process.env,
      tmpdir(),
    );
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotThrow(() => readFileSync(resolve(directory, result.output)));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('aggregates ten ordered observations and recomputes nearest-rank p50/p95', () => {
  const directory = createCase('ordered');
  try {
    const parentLabel = 'reservation-after-n25-r1';
    const rawValues = [10, 1, 9, 2, 8, 3, 7, 4, 6, 5];
    const sources = rawValues.map((value, offset) => writeSource(
      directory,
      parentLabel,
      offset + 1,
      sourceArtifact({ index: offset + 1, parentLabel, serverOperationMs: value }),
    ));

    const result = runAggregator(directory, parentLabel, sources);
    assert.equal(result.status, 0, result.stderr);
    const companion = JSON.parse(readFileSync(resolve(directory, result.output), 'utf8'));
    assert.equal(statSync(resolve(directory, result.output)).mode & 0o777, 0o600);

    assert.deepEqual(Object.keys(companion).sort(), [
      'metadata', 'observations', 'schema_version', 'statistics',
    ]);
    assert.equal(companion.schema_version, 'bulk-write-observations-v1');
    assert.equal(companion.metadata.run_label, parentLabel);
    assert.equal(companion.metadata.run_order, 2);
    assert.equal(companion.metadata.samples, 10);
    assert.equal(companion.metadata.benchmark_dataset_capsule_id, 'bulk-expiration-history-v1');
    assert.equal(companion.metadata.benchmark_dataset_manifest_sha256, 'a'.repeat(64));
    assert.equal(companion.observations.length, 10);
    assert.deepEqual(
      companion.observations.map((observation) => observation.sample_index),
      [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
    );
    assert.deepEqual(
      companion.observations.map((observation) => observation.run_order),
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
    );
    assert.deepEqual(
      companion.observations.map((observation) => observation.server_operation_ms),
      rawValues,
    );
    assert.equal(companion.observations[0].source_path, sources[0]);
    assert.equal(companion.observations[0].k6_summary, undefined);
    assert.equal(companion.observations[0].external_effects, undefined);
    assert.equal(JSON.stringify(companion).includes('must not be copied'), false);
    assert.deepEqual(companion.statistics.server_operation_ms, {
      count: 10,
      min: 1,
      p50: 5,
      p95: 10,
      max: 10,
    });
    assert.equal(companion.statistics.percentile_algorithm, 'nearest-rank');
    assert.match(companion.statistics.percentile_definition, /ceil\(p \* n\)/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('aggregates allowlisted Wishlist observations with its SQL and JDBC contract', () => {
  const cases = [
    {
      parentLabel: 'wishlist-before-empty-r1',
      variant: 'BEFORE',
      datasetSize: 0,
      expectedSql: { SELECT: 2, INSERT: 0, UPDATE: 1, DELETE: 0, OTHER: 0, TOTAL: 3 },
    },
    {
      parentLabel: 'wishlist-before-n25-r1',
      variant: 'BEFORE',
      datasetSize: 25,
      expectedSql: { SELECT: 2, INSERT: 0, UPDATE: 1, DELETE: 25, OTHER: 0, TOTAL: 28 },
    },
    {
      parentLabel: 'wishlist-after-empty-r1',
      variant: 'AFTER',
      datasetSize: 0,
      expectedSql: { SELECT: 2, INSERT: 0, UPDATE: 1, DELETE: 0, OTHER: 0, TOTAL: 3 },
    },
    {
      parentLabel: 'wishlist-after-n25-r1',
      variant: 'AFTER',
      datasetSize: 25,
      expectedSql: { SELECT: 2, INSERT: 0, UPDATE: 1, DELETE: 1, OTHER: 0, TOTAL: 4 },
    },
  ];

  for (const specification of cases) {
    const directory = createCase(specification.parentLabel);
    try {
      const rawValues = [9, 3, 7];
      const sources = rawValues.map((serverOperationMs, offset) => writeSource(
        directory,
        specification.parentLabel,
        offset + 1,
        wishlistSourceArtifact({
          index: offset + 1,
          parentLabel: specification.parentLabel,
			variant: specification.variant,
          datasetSize: specification.datasetSize,
          serverOperationMs,
        }),
      ));
      const result = runAggregator(
        directory,
        specification.parentLabel,
        sources,
        `${specification.parentLabel}-observations.json`,
        process.env,
        directory,
        'WISHLIST_DELETE',
      );
      assert.equal(result.status, 0, result.stderr);
      const companion = JSON.parse(readFileSync(resolve(directory, result.output), 'utf8'));
      assert.equal(companion.metadata.candidate, 'WISHLIST_DELETE');
      assert.deepEqual(
        companion.observations.map((observation) => observation.server_operation_ms),
        rawValues,
      );
      assert.deepEqual(
        companion.observations[0].hibernate_statements_by_type,
        specification.expectedSql,
      );
      assert.deepEqual(companion.observations[0].jdbc, {
        batch_calls: 0,
        submitted_rows: 0,
        configured_batch_size: null,
        affected_rows: null,
      });
      assert.equal(companion.observations[0].external_effects, undefined);
      assert.deepEqual(companion.statistics.server_operation_ms, {
        count: 3,
        min: 3,
        p50: 7,
        p95: 9,
        max: 9,
      });
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('aggregates AccommodationAmenity measurements without pooling workload classes', () => {
  const cases = [
    {
      parentLabel: 'amenity-full-realistic-n30-r1',
      measurement: 'FULL_REPLACEMENT',
      datasetSize: 30,
      activeCodeCount: 30,
      workloadClass: 'REALISTIC',
      expectedSql: { SELECT: 3, INSERT: 31, UPDATE: 1, DELETE: 30, OTHER: 0, TOTAL: 65 },
    },
		{
			parentLabel: 'amenity-full-after-n30-r1',
			variant: 'AFTER',
			measurement: 'FULL_REPLACEMENT',
			datasetSize: 30,
			activeCodeCount: 30,
			workloadClass: 'REALISTIC',
			expectedSql: { SELECT: 2, INSERT: 31, UPDATE: 1, DELETE: 1, OTHER: 0, TOTAL: 35 },
		},
		{
			parentLabel: 'amenity-delete-after-n0-r1',
			variant: 'AFTER',
			measurement: 'DELETE_ONLY',
			datasetSize: 0,
			activeCodeCount: 30,
			workloadClass: 'REALISTIC',
			expectedSql: { SELECT: 0, INSERT: 0, UPDATE: 0, DELETE: 1, OTHER: 0, TOTAL: 1 },
		},
    {
      parentLabel: 'amenity-delete-stress-n31-r1',
      measurement: 'DELETE_ONLY',
      datasetSize: 31,
      activeCodeCount: 30,
      workloadClass: 'STRESS',
      expectedSql: { SELECT: 1, INSERT: 0, UPDATE: 0, DELETE: 31, OTHER: 0, TOTAL: 32 },
    },
  ];

  for (const specification of cases) {
    const directory = createCase(specification.parentLabel);
    try {
      const sources = [4, 2, 6].map((serverOperationMs, offset) => writeSource(
        directory,
        specification.parentLabel,
        offset + 1,
        accommodationAmenitySourceArtifact({
          index: offset + 1,
          parentLabel: specification.parentLabel,
			variant: specification.variant,
          measurement: specification.measurement,
          datasetSize: specification.datasetSize,
          activeCodeCount: specification.activeCodeCount,
          serverOperationMs,
        }),
      ));
      const result = runAggregator(
        directory,
        specification.parentLabel,
        sources,
        `${specification.parentLabel}-observations.json`,
        process.env,
        directory,
        'ACCOMMODATION_AMENITY_DELETE',
      );
      assert.equal(result.status, 0, result.stderr);
      const companion = JSON.parse(readFileSync(resolve(directory, result.output), 'utf8'));
      assert.equal(companion.metadata.measurement, specification.measurement);
      assert.equal(companion.metadata.workload_class, specification.workloadClass);
      assert.equal(
        companion.metadata.active_amenity_code_count,
        specification.activeCodeCount,
      );
      assert.deepEqual(
        companion.observations[0].hibernate_statements_by_type,
        specification.expectedSql,
      );
      assert.equal(companion.observations[0].measurement, specification.measurement);
      assert.equal(companion.observations[0].workload_class, specification.workloadClass);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

const mixedAccommodationAmenityCases = [
	{
		name: 'variant',
		first: {
			variant: 'BEFORE', measurement: 'FULL_REPLACEMENT', datasetSize: 30, activeCodeCount: 30,
		},
		second: {
			variant: 'AFTER', measurement: 'FULL_REPLACEMENT', datasetSize: 30, activeCodeCount: 30,
		},
	},
  {
    name: 'measurement',
    first: { measurement: 'FULL_REPLACEMENT', datasetSize: 30, activeCodeCount: 30 },
    second: { measurement: 'DELETE_ONLY', datasetSize: 30, activeCodeCount: 30 },
  },
  {
    name: 'workload class',
    first: { measurement: 'DELETE_ONLY', datasetSize: 30, activeCodeCount: 30 },
    second: { measurement: 'DELETE_ONLY', datasetSize: 30, activeCodeCount: 30 },
    mutateSecond: (artifact) => { artifact.metadata.workload_class = 'STRESS'; },
  },
  {
    name: 'active amenity code count',
    first: { measurement: 'DELETE_ONLY', datasetSize: 31, activeCodeCount: 30 },
    second: { measurement: 'DELETE_ONLY', datasetSize: 31, activeCodeCount: 29 },
  },
];

for (const specification of mixedAccommodationAmenityCases) {
  test(`rejects AccommodationAmenity sources with different ${specification.name}`, () => {
    const directory = createCase(`amenity-mixed-${specification.name.replaceAll(' ', '-')}`);
    try {
      const parentLabel = 'amenity-mixed-source-r1';
      const sources = [specification.first, specification.second].map((source, offset) => {
        const artifact = accommodationAmenitySourceArtifact({
          index: offset + 1,
          parentLabel,
          ...source,
        });
        if (offset === 1 && specification.mutateSecond !== undefined) {
          specification.mutateSecond(artifact);
        }
        return writeSource(
          directory,
          parentLabel,
          offset + 1,
          artifact,
        );
      });

      assertRejected(runAggregator(
        directory,
        parentLabel,
        sources,
        `${parentLabel}-observations.json`,
        process.env,
        directory,
        'ACCOMMODATION_AMENITY_DELETE',
      ), directory);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('rejects Wishlist sources outside the allowlisted SQL, JDBC, and metadata contract', () => {
  const invalidWishlistCases = [
    {
      name: 'endpoint',
      mutate: (artifact) => { artifact.metadata.endpoint = '/wrong'; },
    },
    {
      name: 'operation name',
      mutate: (artifact) => { artifact.metadata.operation_name = 'wrong'; },
    },
    {
      name: 'maximum dataset size',
      mutate: (artifact) => { artifact.metadata.dataset_size = 1001; },
    },
    {
      name: 'DELETE formula',
      mutate: (artifact) => {
        artifact.performance.hibernate_statements_by_type.DELETE = trend(2);
        artifact.performance.hibernate_statements_by_type.TOTAL = trend(5);
        artifact.database_observation.hibernate_statements_by_type.DELETE = trend(2);
        artifact.database_observation.hibernate_statements_by_type.TOTAL = trend(5);
        artifact.k6_summary.metrics.bulk_write_hibernate_delete_statements = metric(2);
        artifact.k6_summary.metrics.bulk_write_hibernate_total_statements = metric(5);
      },
    },
    {
      name: 'JDBC activity',
      mutate: (artifact) => {
        artifact.database_observation.jdbc = {
          batch_calls: 1,
          submitted_rows: 25,
          configured_batch_size: 25,
          affected_rows: 25,
          affected_rows_known_samples: 1,
          affected_rows_unknown_samples: 0,
        };
        artifact.k6_summary.metrics.bulk_write_jdbc_batch_calls = metric(1);
        artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows = metric(25);
        artifact.k6_summary.metrics.bulk_write_jdbc_configured_batch_size = metric(25);
        artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows = metric(25);
      },
    },
    {
      name: 'external effects',
      mutate: (artifact) => {
        artifact.database_observation.external_effects = {
          hold_removal_calls: 25,
          hold_removal_mode: 'RECORDED_NO_IO',
          redis_network_excluded: true,
        };
        artifact.k6_summary.metrics.bulk_write_hold_removal_calls = metric(25);
      },
    },
    {
      name: 'external effect metric only',
      mutate: (artifact) => {
        artifact.k6_summary.metrics.bulk_write_hold_removal_calls = metric(25);
      },
    },
  ];

  for (const invalidCase of invalidWishlistCases) {
    const directory = createCase(`wishlist-invalid-${invalidCase.name.replaceAll(' ', '-')}`);
    try {
      const parentLabel = 'wishlist-after-invalid-r1';
      const artifact = wishlistSourceArtifact({
        index: 1,
        parentLabel,
        variant: 'AFTER',
        datasetSize: 25,
      });
      invalidCase.mutate(artifact);
      const source = writeSource(directory, parentLabel, 1, artifact);
      assertRejected(runAggregator(
        directory,
        parentLabel,
        [source],
        `${parentLabel}-observations.json`,
        process.env,
        directory,
        'WISHLIST_DELETE',
      ), directory);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('preserves BEFORE null JDBC values and AFTER exact or unknown affected rows', () => {
  const cases = [
    { parentLabel: 'reservation-before-n25-r1', variant: 'BEFORE', affectedRows: null },
    { parentLabel: 'reservation-after-exact-n25-r1', variant: 'AFTER', affectedRows: 25 },
    { parentLabel: 'reservation-after-unknown-n25-r1', variant: 'AFTER', affectedRows: null },
  ];
  for (const specification of cases) {
    const directory = createCase(specification.variant.toLowerCase());
    try {
      const source = writeSource(
        directory,
        specification.parentLabel,
        1,
        sourceArtifact({
          index: 1,
          parentLabel: specification.parentLabel,
          variant: specification.variant,
          affectedRows: specification.affectedRows,
        }),
      );
      const result = runAggregator(directory, specification.parentLabel, [source]);
      assert.equal(result.status, 0, result.stderr);
      const companion = JSON.parse(readFileSync(resolve(directory, result.output), 'utf8'));
      assert.equal(companion.observations[0].variant, specification.variant);
      assert.equal(companion.observations[0].jdbc.affected_rows, specification.affectedRows);
      if (specification.variant === 'BEFORE') {
        assert.deepEqual(companion.observations[0].jdbc, {
          batch_calls: 0,
          submitted_rows: 0,
          configured_batch_size: null,
          affected_rows: null,
        });
      }
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('accepts omitted optional zero-count metrics for real empty and unknown JDBC cases', () => {
  const cases = [
    {
      parentLabel: 'reservation-before-empty-r1',
      variant: 'BEFORE',
      datasetSize: 0,
      omittedMetrics: [
        'bulk_write_jdbc_configured_batch_size',
        'bulk_write_jdbc_affected_rows',
      ],
    },
    {
      parentLabel: 'reservation-after-empty-r1',
      variant: 'AFTER',
      datasetSize: 0,
      omittedMetrics: [
        'bulk_write_jdbc_configured_batch_size',
        'bulk_write_jdbc_affected_rows',
      ],
    },
    {
      parentLabel: 'reservation-after-unknown-r1',
      variant: 'AFTER',
      datasetSize: 25,
      affectedRows: null,
      omittedMetrics: ['bulk_write_jdbc_affected_rows'],
    },
  ];

  for (const specification of cases) {
    const directory = createCase(`omitted-${specification.variant.toLowerCase()}`);
    try {
      const artifact = sourceArtifact({
        index: 1,
        parentLabel: specification.parentLabel,
        variant: specification.variant,
        datasetSize: specification.datasetSize,
        affectedRows: specification.affectedRows,
      });
      specification.omittedMetrics.forEach((name) => {
        assert.equal(Object.hasOwn(artifact.k6_summary.metrics, name), false);
      });
      const source = writeSource(
        directory,
        specification.parentLabel,
        1,
        artifact,
      );
      const result = runAggregator(directory, specification.parentLabel, [source]);
      assert.equal(result.status, 0, result.stderr);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('accepts a present well-formed zero-count representation for optional metrics', () => {
  const directory = createCase('explicit-zero-count');
  try {
    const parentLabel = 'reservation-before-explicit-zero-r1';
    const artifact = sourceArtifact({ index: 1, parentLabel, variant: 'BEFORE' });
    artifact.k6_summary.metrics.bulk_write_jdbc_configured_batch_size = {
      values: { count: 0 },
    };
    artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows = {
      values: { count: 0 },
    };
    const source = writeSource(directory, parentLabel, 1, artifact);
    const result = runAggregator(directory, parentLabel, [source]);
    assert.equal(result.status, 0, result.stderr);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

const invalidCases = [
  {
    name: 'legacy benchmark dataset version',
    mutate: (artifact) => {
      artifact.metadata.benchmark_dataset_version = 'benchmark-dataset-v1';
      artifact.metadata.benchmark_world_version = 'world-v1';
    },
  },
  {
    name: 'multi-sample source',
    mutate: (artifact) => { artifact.metadata.samples = 2; },
  },
  {
    name: 'failed verification',
    mutate: (artifact) => {
      artifact.performance.verification = {
        attempted: 1, successful: 0, failed: 1, success_rate: 0,
      };
    },
  },
  {
    name: 'missing required metric',
    mutate: (artifact) => { delete artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows; },
  },
  {
    name: 'wrong candidate',
    mutate: (artifact) => { artifact.metadata.candidate = 'UNKNOWN_BULK_WRITE'; },
  },
  {
    name: 'invalid JDBC contract',
    mutate: (artifact) => { artifact.database_observation.jdbc.submitted_rows = 24; },
  },
  {
    name: 'multi-sample aggregate trend',
    mutate: (artifact) => {
      artifact.performance.hibernate_statements_by_type.SELECT.count = 2;
    },
  },
  {
    name: 'one-sample aggregate trend with a mismatched maximum',
    mutate: (artifact) => {
      artifact.performance.server_operation_ms.max += 1;
    },
  },
  {
    name: 'one-sample performance Hibernate trend with a mismatched minimum',
    mutate: (artifact) => {
      artifact.performance.hibernate_statements_by_type.SELECT.min += 1;
    },
  },
  {
    name: 'one-sample database Hibernate trend with a mismatched percentile',
    mutate: (artifact) => {
      artifact.database_observation.hibernate_statements_by_type.SELECT.p95 += 1;
    },
  },
  {
    name: 'mismatched raw metric value',
    mutate: (artifact) => {
      artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows.values.med = 24;
    },
  },
  {
    name: 'one-sample raw metric with a mismatched maximum',
    mutate: (artifact) => {
      artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows.values.max += 1;
    },
  },
  {
    name: 'one-sample raw metric with a mismatched percentile',
    mutate: (artifact) => {
      artifact.k6_summary.metrics.bulk_write_jdbc_submitted_rows.values['p(95)'] += 1;
    },
  },
  {
    name: 'present malformed optional zero-count affected-row metric',
    mutate: (artifact) => {
      artifact.database_observation.jdbc.affected_rows = null;
      artifact.database_observation.jdbc.affected_rows_known_samples = 0;
      artifact.database_observation.jdbc.affected_rows_unknown_samples = 1;
      artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows = {
        values: { count: 0, avg: 0 },
      };
    },
  },
  {
    name: 'present nonzero optional metric when database value is null',
    mutate: (artifact) => {
      artifact.database_observation.jdbc.affected_rows = null;
      artifact.database_observation.jdbc.affected_rows_known_samples = 0;
      artifact.database_observation.jdbc.affected_rows_unknown_samples = 1;
      artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows = metric(25);
    },
  },
];

for (const invalidCase of invalidCases) {
  test(`rejects ${invalidCase.name} without emitting a companion`, () => {
    const directory = createCase('invalid');
    try {
      const parentLabel = 'reservation-after-invalid-r1';
      const artifact = sourceArtifact({ index: 1, parentLabel });
      invalidCase.mutate(artifact);
      const source = writeSource(directory, parentLabel, 1, artifact);
      assertRejected(runAggregator(directory, parentLabel, [source]), directory);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('rejects malformed or nonzero configured-batch metrics when the database value is null', () => {
  const representations = [
    { name: 'malformed-zero', metric: { values: { count: 0, med: 0 } } },
    { name: 'nonzero', metric: metric(25) },
  ];
  for (const representation of representations) {
    const directory = createCase(`configured-${representation.name}`);
    try {
      const parentLabel = `reservation-before-configured-${representation.name}-r1`;
      const artifact = sourceArtifact({ index: 1, parentLabel, variant: 'BEFORE' });
      artifact.k6_summary.metrics.bulk_write_jdbc_affected_rows = {
        values: { count: 0 },
      };
      artifact.k6_summary.metrics.bulk_write_jdbc_configured_batch_size = (
        representation.metric
      );
      const source = writeSource(directory, parentLabel, 1, artifact);
      assertRejected(runAggregator(directory, parentLabel, [source]), directory);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('rejects mismatched public experiment metadata', () => {
  const directory = createCase('mismatch');
  try {
    const parentLabel = 'reservation-after-mismatch-r1';
    const first = sourceArtifact({ index: 1, parentLabel });
    const second = sourceArtifact({ index: 2, parentLabel });
    second.metadata.schema_label = 'different-public-schema-label';
    const sources = [
      writeSource(directory, parentLabel, 1, first),
      writeSource(directory, parentLabel, 2, second),
    ];
    assertRejected(runAggregator(directory, parentLabel, sources), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects sources from different block run orders', () => {
  const directory = createCase('mixed-run-order');
  try {
    const parentLabel = 'reservation-after-mixed-run-order-r1';
    const sources = [
      writeSource(
        directory,
        parentLabel,
        1,
        sourceArtifact({ index: 1, parentLabel, runOrder: 1 }),
      ),
      writeSource(
        directory,
        parentLabel,
        2,
        sourceArtifact({ index: 2, parentLabel, runOrder: 2 }),
      ),
    ];
    assertRejected(runAggregator(directory, parentLabel, sources), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects duplicate source paths', () => {
  const directory = createCase('duplicates');
  try {
    const parentLabel = 'reservation-after-duplicates-r1';
    const firstPath = writeSource(
      directory,
      parentLabel,
      1,
      sourceArtifact({ index: 1, parentLabel }),
    );
    assertRejected(runAggregator(directory, parentLabel, [firstPath, firstPath]), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects non-finite raw values encoded as overflowing JSON numbers', () => {
  const directory = createCase('non-finite');
  try {
    const parentLabel = 'reservation-after-non-finite-r1';
    const artifact = sourceArtifact({ index: 1, parentLabel, serverOperationMs: 10 });
    const source = sourcePath(parentLabel, 1);
    const serialized = JSON.stringify(artifact, null, 2).replace(
      '"median": 10',
      '"median": 1e999',
    );
    writeFileSync(resolve(directory, source), serialized);
    assertRejected(runAggregator(directory, parentLabel, [source]), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects secret-bearing source input without echoing the secret sentinel', () => {
  const directory = createCase('secret');
  try {
    const parentLabel = 'reservation-after-sensitive-r1';
    const secret = 'credential-sentinel-do-not-disclose';
    const artifact = sourceArtifact({ index: 1, parentLabel });
    artifact.k6_summary.root_group.benchmark_token = secret;
    const source = writeSource(directory, parentLabel, 1, artifact);
    assertRejected(runAggregator(directory, parentLabel, [source]), directory, secret);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects a known environment credential hidden in public labels and paths', () => {
  const directory = createCase('environment-secret');
  try {
    const secret = '0123456789abcdef0123456789abcdef';
    const parentLabel = secret;
    const source = writeSource(
      directory,
      parentLabel,
      1,
      sourceArtifact({ index: 1, parentLabel }),
    );
    const result = runAggregator(
      directory,
      parentLabel,
      [source],
      `${parentLabel}-observations.json`,
      { ...process.env, BENCHMARK_BULK_WRITE_TOKEN: secret },
    );
    assertRejected(result, directory, secret);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects a canonical source filename that is a symbolic link', () => {
  const directory = createCase('source-symlink');
  try {
    const parentLabel = 'reservation-after-source-symlink-r1';
    const target = resolve(directory, 'real-source.json');
    writeFileSync(target, JSON.stringify(sourceArtifact({ index: 1, parentLabel })));
    const source = sourcePath(parentLabel, 1);
    symlinkSync(target, resolve(directory, source));
    assertRejected(runAggregator(directory, parentLabel, [source]), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects a symbolic-link component in the fixed artifact root', () => {
  const directory = createCase('component-symlink');
  try {
    const parentLabel = 'reservation-after-component-symlink-r1';
    const artifactRoot = resolve(directory, 'build/k6/bulk-write');
    const linkedRoot = resolve(directory, 'linked-artifact-root');
    rmSync(artifactRoot, { recursive: true, force: true });
    mkdirSync(linkedRoot, { recursive: true });
    symlinkSync(linkedRoot, artifactRoot);
    const source = writeSource(
      directory,
      parentLabel,
      1,
      sourceArtifact({ index: 1, parentLabel }),
    );
    assertRejected(runAggregator(directory, parentLabel, [source]), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects a dangling symbolic-link companion destination', () => {
  const directory = createCase('output-symlink');
  try {
    const parentLabel = 'reservation-after-output-symlink-r1';
    const source = writeSource(
      directory,
      parentLabel,
      1,
      sourceArtifact({ index: 1, parentLabel }),
    );
    const outputName = `${parentLabel}-observations.json`;
    symlinkSync(
      resolve(directory, 'missing-output-target.json'),
      resolve(directory, 'build/k6/bulk-write', outputName),
    );
    assertRejected(runAggregator(directory, parentLabel, [source], outputName), directory);
    assert.equal(
      lstatSync(resolve(directory, 'build/k6/bulk-write', outputName)).isSymbolicLink(),
      true,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects sources whose cumulative encoded size exceeds the workflow limit', () => {
  const directory = createCase('cumulative-size');
  try {
    const parentLabel = 'reservation-after-cumulative-size-r1';
    const sources = [];
    for (let index = 1; index <= 17; index += 1) {
      const source = sourcePath(parentLabel, index);
      const serialized = JSON.stringify(sourceArtifact({ index, parentLabel }));
      writeFileSync(resolve(directory, source), `${serialized}${' '.repeat(250 * 1024)}`);
      sources.push(source);
    }
    assertRejected(runAggregator(directory, parentLabel, sources), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('rejects source JSON that exceeds the bounded secret-scan depth', () => {
  const directory = createCase('depth');
  try {
    const parentLabel = 'reservation-after-depth-r1';
    const artifact = sourceArtifact({ index: 1, parentLabel });
    let nested = { safe: true };
    for (let depth = 0; depth < 70; depth += 1) {
      nested = { nested };
    }
    artifact.k6_summary.root_group.deep_public_data = nested;
    const source = writeSource(directory, parentLabel, 1, artifact);
    assertRejected(runAggregator(directory, parentLabel, [source]), directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

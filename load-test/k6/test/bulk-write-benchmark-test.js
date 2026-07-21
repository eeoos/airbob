import { check } from 'k6';

import {
  ARTIFACT_SCHEMA_VERSION,
  BULK_WRITE_ENDPOINT,
  bulkWriteOperationName,
  buildBulkWriteArtifact,
  buildBulkWriteHeaders,
  buildBulkWriteOptions,
  buildBulkWriteRequestParams,
  buildBulkWriteRequestBody,
  matchesBulkWriteOperationContract,
  matchesBulkWriteResponseContract,
  parseBulkWriteRunConfig,
  parseBulkWriteResultPath,
  parseBulkWriteToken,
  parseBulkWriteVariant,
  parseDatasetSize,
  parsePhase,
  parseSafeBaseUrl,
  parseSamples,
  summarizeBulkWriteMetrics,
} from '../lib/bulk-write-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const TOKEN = '0123456789abcdef0123456789abcdef';
const SQL_COUNTS = {
  SELECT: 2,
  INSERT: 0,
  UPDATE: 1,
  DELETE: 25,
  OTHER: 0,
  TOTAL: 28,
};

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function operation(overrides = {}, variant = 'BEFORE') {
  return {
    operation_name: `wishlist-delete-${variant.toLowerCase()}`,
    outcome: 'SUCCESS',
    server_operation_nanos: 12_500_000,
    server_operation_ms: 12.5,
    hibernate_statements_by_type: SQL_COUNTS,
    jdbc_batch_calls: 0,
    jdbc_submitted_rows: 0,
    jdbc_configured_batch_size: null,
    jdbc_affected_rows: null,
    ...overrides,
  };
}

function payload(overrides = {}, variant = 'BEFORE') {
  return {
    success: true,
    data: {
      candidate: 'WISHLIST_DELETE',
      variant,
      dataset_size: 25,
      expected_rows: 25,
      verified_rows: 25,
      verification_succeeded: true,
      target_wishlist_deleted: true,
      target_memberships_deleted: true,
      target_denormalized_state_preserved: true,
      control_wishlist_preserved: true,
      control_membership_preserved: true,
      accommodations_preserved: true,
      operation: operation({}, variant),
      ...overrides,
    },
  };
}

function k6Summary() {
  return {
    metrics: {
      http_reqs: { values: { count: 3, rate: 1.5 } },
      bulk_write_sample_success: { values: { passes: 3, fails: 0, rate: 1 } },
      bulk_write_verification_success: { values: { passes: 3, fails: 0, rate: 1 } },
      bulk_write_server_operation_ms: {
        values: {
          count: 3,
          avg: 12,
          min: 10,
          med: 11,
          'p(90)': 14,
          'p(95)': 15,
          'p(99)': 16,
          max: 17,
        },
      },
      bulk_write_http_orchestration_ms: {
        values: {
          count: 3,
          avg: 30,
          min: 25,
          med: 29,
          'p(90)': 35,
          'p(95)': 36,
          'p(99)': 37,
          max: 38,
        },
      },
      bulk_write_verified_rows: {
        values: { count: 3, avg: 25, min: 25, med: 25, max: 25 },
      },
      bulk_write_hibernate_select_statements: {
        values: { count: 3, avg: 2, min: 2, med: 2, max: 2 },
      },
      bulk_write_hibernate_insert_statements: {
        values: { count: 3, avg: 0, min: 0, med: 0, max: 0 },
      },
      bulk_write_hibernate_update_statements: {
        values: { count: 3, avg: 1, min: 1, med: 1, max: 1 },
      },
      bulk_write_hibernate_delete_statements: {
        values: { count: 3, avg: 25, min: 25, med: 25, max: 25 },
      },
      bulk_write_hibernate_other_statements: {
        values: { count: 3, avg: 0, min: 0, med: 0, max: 0 },
      },
      bulk_write_hibernate_total_statements: {
        values: { count: 3, avg: 28, min: 28, med: 28, max: 28 },
      },
      bulk_write_jdbc_batch_calls: {
        values: { count: 3, avg: 0, min: 0, med: 0, max: 0 },
      },
      bulk_write_jdbc_submitted_rows: {
        values: { count: 3, avg: 0, min: 0, med: 0, max: 0 },
      },
    },
    root_group: {
      name: 'all non-secret checks',
      checks: [{ name: 'response contract', passes: 3, fails: 0 }],
      groups: [],
    },
    state: { isStdOutTTY: false, testRunDurationMs: 2000 },
  };
}

export default function () {
  const config = parseBulkWriteRunConfig({
    BASE_URL: 'http://localhost:8080/',
    VARIANT: 'BEFORE',
    PHASE: 'measure',
    DATASET_SIZE: '25',
    SAMPLES: '3',
    BENCHMARK_BULK_WRITE_TOKEN: TOKEN,
    RUN_LABEL: 'wishlist-before-n25-r1',
    ROUND: '1',
    RUN_ORDER: '2',
    APP_COMMIT: 'abc123',
    APP_INSTANCE_COUNT: '1',
    SCHEMA_LABEL: 'airbob-bulk-write-v1',
    JVM_VERSION: '21.0.7',
    MYSQL_VERSION: '8.0.42',
    REWRITE_BATCHED_STATEMENTS: 'false',
  });
  const afterConfig = parseBulkWriteRunConfig({
    BASE_URL: 'http://localhost:8080/',
    VARIANT: 'AFTER',
    PHASE: 'measure',
    DATASET_SIZE: '25',
    SAMPLES: '3',
    BENCHMARK_BULK_WRITE_TOKEN: TOKEN,
    ROUND: '1',
    RUN_ORDER: '2',
    APP_COMMIT: 'abc123',
    APP_INSTANCE_COUNT: '1',
    SCHEMA_LABEL: 'airbob-bulk-write-v1',
    JVM_VERSION: '21.0.7',
    MYSQL_VERSION: '8.0.42',
    REWRITE_BATCHED_STATEMENTS: 'false',
  });
  const warmupOptions = buildBulkWriteOptions({
    variant: 'BEFORE',
    phase: 'warmup',
    samples: 1,
  });
  const measureOptions = buildBulkWriteOptions({
    variant: 'AFTER',
    phase: 'measure',
    samples: 3,
  });
  const validPayload = payload();
  const validAfterPayload = payload({
    operation: operation({
      hibernate_statements_by_type: {
        ...SQL_COUNTS,
        DELETE: 1,
        TOTAL: 4,
      },
    }, 'AFTER'),
  }, 'AFTER');
  const zeroPayload = payload({
    dataset_size: 0,
    expected_rows: 0,
    verified_rows: 0,
  });
  const summary = k6Summary();
  const performance = summarizeBulkWriteMetrics(summary);
  const artifactSummary = {
    ...summary,
    root_group: {
      ...summary.root_group,
      token: TOKEN,
      session_id: 'session-cookie-sentinel',
      user_id: 'actual-user-9001',
      name: `request ${TOKEN} session-cookie-sentinel actual-user-9001`,
    },
  };
  const artifact = buildBulkWriteArtifact({
    config: {
      ...config,
      benchmarkEmail: 'benchmark-admin@example.com',
      password: 'password-sentinel',
      sessionId: 'session-cookie-sentinel',
      userId: 'actual-user-9001',
    },
    k6Summary: artifactSummary,
    generatedAt: '2026-07-21T00:00:00.000Z',
    sensitiveValues: [
      TOKEN,
      'session-cookie-sentinel',
      'actual-user-9001',
      'benchmark-admin@example.com',
      'password-sentinel',
    ],
  });
  const afterArtifact = buildBulkWriteArtifact({
    config: afterConfig,
    k6Summary: summary,
    generatedAt: '2026-07-21T00:00:00.000Z',
  });
  const metadataRedactionArtifact = buildBulkWriteArtifact({
    config: {
      ...config,
      runLabel: `public-prefix-${TOKEN}-public-suffix`,
    },
    k6Summary: summary,
    generatedAt: '2026-07-21T00:00:00.000Z',
    sensitiveValues: [TOKEN],
  });
  const jdbcArtifact = buildBulkWriteArtifact({
    config,
    k6Summary: {
      ...summary,
      metrics: {
        ...summary.metrics,
        bulk_write_jdbc_batch_calls: {
          values: { count: 3, avg: 2, min: 2, med: 2, max: 2 },
        },
        bulk_write_jdbc_submitted_rows: {
          values: { count: 3, avg: 25, min: 25, med: 25, max: 25 },
        },
      },
    },
  });
  const serializedArtifact = JSON.stringify(artifact);
  const metadataKeys = Object.keys(artifact.metadata).sort();

  check(null, {
    'HTTP localhost origin is allowed': () => (
      parseSafeBaseUrl('http://localhost:8080/') === 'http://localhost:8080'
    ),
    'HTTP IPv4 loopback origin is allowed': () => (
      parseSafeBaseUrl('http://127.0.0.1:8080') === 'http://127.0.0.1:8080'
    ),
    'HTTP IPv6 loopback origin is allowed': () => (
      parseSafeBaseUrl('http://[::1]:8080') === 'http://[::1]:8080'
    ),
    'HTTPS remote origin is allowed': () => (
      parseSafeBaseUrl('https://benchmark.example.com:8443/')
        === 'https://benchmark.example.com:8443'
    ),
    'HTTP protocol names are parsed case-insensitively': () => (
      parseSafeBaseUrl('HTTPS://BENCHMARK.EXAMPLE.COM:443/')
        === 'https://benchmark.example.com'
    ),
    'remote HTTP origin is rejected': () => rejects(() => (
      parseSafeBaseUrl('http://benchmark.example.com')
    )),
    'non-exact IPv4 loopback is rejected for HTTP': () => rejects(() => (
      parseSafeBaseUrl('http://127.0.0.2:8080')
    )),
    'lookalike localhost is rejected for HTTP': () => rejects(() => (
      parseSafeBaseUrl('http://localhost.example.com:8080')
    )),
    'origin path is rejected': () => rejects(() => (
      parseSafeBaseUrl('https://benchmark.example.com/api')
    )),
    'origin query is rejected': () => rejects(() => (
      parseSafeBaseUrl('https://benchmark.example.com?token=secret')
    )),
    'origin credentials are rejected': () => rejects(() => (
      parseSafeBaseUrl('https://user:password@benchmark.example.com')
    )),
    'non-HTTP protocol is rejected': () => rejects(() => (
      parseSafeBaseUrl('ftp://localhost:8080')
    )),
    'zero dataset is accepted': () => parseDatasetSize('0') === 0,
    'maximum dataset is accepted': () => parseDatasetSize('1000') === 1000,
    'negative dataset is rejected': () => rejects(() => parseDatasetSize('-1')),
    'oversized dataset is rejected': () => rejects(() => parseDatasetSize('1001')),
    'fractional dataset is rejected': () => rejects(() => parseDatasetSize('1.5')),
    'exponent dataset is rejected': () => rejects(() => parseDatasetSize('1e3')),
    'whitespace dataset is rejected': () => rejects(() => parseDatasetSize(' 25')),
    'leading-zero dataset is rejected': () => rejects(() => parseDatasetSize('025')),
    'blank dataset is rejected': () => rejects(() => parseDatasetSize('')),
    'BEFORE variant is accepted': () => parseBulkWriteVariant('BEFORE') === 'BEFORE',
    'AFTER variant is accepted': () => parseBulkWriteVariant('AFTER') === 'AFTER',
    'lowercase variant is rejected': () => rejects(() => parseBulkWriteVariant('before')),
    'unknown variant is rejected': () => rejects(() => parseBulkWriteVariant('UNKNOWN')),
    'operation name is derived from the exact variant': () => (
      bulkWriteOperationName('BEFORE') === 'wishlist-delete-before'
        && bulkWriteOperationName('AFTER') === 'wishlist-delete-after'
    ),
    'warmup phase is accepted': () => parsePhase('warmup') === 'warmup',
    'measure phase is accepted': () => parsePhase('measure') === 'measure',
    'unknown phase is rejected': () => rejects(() => parsePhase('mixed')),
    'samples default to ten': () => parseSamples(undefined) === 10,
    'minimum samples are accepted': () => parseSamples('1') === 1,
    'maximum samples are accepted': () => parseSamples('100') === 100,
    'zero samples are rejected': () => rejects(() => parseSamples('0')),
    'oversized samples are rejected': () => rejects(() => parseSamples('101')),
    'bulk-write token is preserved exactly': () => parseBulkWriteToken(TOKEN) === TOKEN,
    'whitespace-padded bulk-write token is rejected': () => rejects(() => (
      parseBulkWriteToken(` ${TOKEN} `)
    )),
    '31-character bulk-write token is rejected': () => rejects(() => (
      parseBulkWriteToken('x'.repeat(31))
    )),
    'blank bulk-write token is rejected': () => rejects(() => (
      parseBulkWriteToken(' '.repeat(32))
    )),
    'run config applies safe normalized values': () => (
      config.baseUrl === 'http://localhost:8080'
        && config.variant === 'BEFORE'
        && config.phase === 'measure'
        && config.datasetSize === 25
        && config.samples === 3
        && config.benchmarkToken === TOKEN
        && config.schemaLabel === 'airbob-bulk-write-v1'
        && config.resultPath
          === 'build/k6/bulk-write/wishlist-delete-before-n25-measure-r1-o2.json'
    ),
    'AFTER run config derives an isolated path and label': () => (
      afterConfig.variant === 'AFTER'
        && afterConfig.resultPath
          === 'build/k6/bulk-write/wishlist-delete-after-n25-measure-r1-o2.json'
        && afterConfig.runLabel === 'wishlist-delete-after-n25-measure-r1'
    ),
    'run config requires explicit reproducibility metadata': () => rejects(() => (
      parseBulkWriteRunConfig({
        DATASET_SIZE: '25',
        BENCHMARK_BULK_WRITE_TOKEN: TOKEN,
      })
    )),
    'result path accepts a JSON artifact below the bulk-write directory': () => (
      parseBulkWriteResultPath('build/k6/bulk-write/custom-run.json')
        === 'build/k6/bulk-write/custom-run.json'
    ),
    'result path rejects parent traversal': () => rejects(() => (
      parseBulkWriteResultPath('build/k6/bulk-write/../secret.json')
    )),
    'result path rejects absolute paths': () => rejects(() => (
      parseBulkWriteResultPath('/tmp/bulk-write.json')
    )),
    'result path rejects paths outside the artifact directory': () => rejects(() => (
      parseBulkWriteResultPath('build/k6/other.json')
    )),
    'result path rejects backslashes and control characters': () => [
      'build/k6/bulk-write\\result.json',
      'build/k6/bulk-write/result\n.json',
    ].every((value) => rejects(() => parseBulkWriteResultPath(value))),
    'result path rejects reserved streams and non-JSON names': () => [
      'stdout',
      'stderr',
      'build/k6/bulk-write/stdout.json',
      'build/k6/bulk-write/stderr.json',
      'build/k6/bulk-write/result.txt',
    ].every((value) => rejects(() => parseBulkWriteResultPath(value))),
    'request body contains only variant and dataset size': () => {
      const body = JSON.parse(buildBulkWriteRequestBody({
        variant: 'BEFORE',
        datasetSize: 25,
      }));
      return JSON.stringify(Object.keys(body).sort())
          === JSON.stringify(['dataset_size', 'variant'])
        && body.variant === 'BEFORE'
        && body.dataset_size === 25;
    },
    'request body preserves the AFTER variant': () => (
      JSON.parse(buildBulkWriteRequestBody({ variant: 'AFTER', datasetSize: 25 })).variant
        === 'AFTER'
    ),
    'request headers use only JSON and the dedicated bulk-write token': () => {
      const headers = buildBulkWriteHeaders(TOKEN);
      return JSON.stringify(Object.keys(headers).sort()) === JSON.stringify([
        'Content-Type',
        'X-Bulk-Write-Benchmark-Token',
      ])
        && headers['Content-Type'] === 'application/json'
        && headers['X-Bulk-Write-Benchmark-Token'] === TOKEN
        && headers['X-Benchmark-Token'] === undefined;
    },
    'secret-bearing benchmark request cannot follow redirects': () => {
      const params = buildBulkWriteRequestParams({
        benchmarkToken: TOKEN,
        sessionId: 'session-cookie-sentinel',
        timeout: '30s',
        tags: { phase: 'measure' },
      });
      return params.redirects === 0
        && params.cookies.SESSION_ID === 'session-cookie-sentinel'
        && params.headers['X-Bulk-Write-Benchmark-Token'] === TOKEN
        && params.timeout === '30s'
        && params.tags.phase === 'measure';
    },
    'warmup uses one shared VU': () => {
      const scenario = warmupOptions.scenarios.bulk_write_warmup;
      return Object.keys(warmupOptions.scenarios).length === 1
        && scenario.executor === 'shared-iterations'
        && scenario.vus === 1
        && scenario.iterations === 1
        && scenario.tags.phase === 'warmup'
        && scenario.tags.variant === 'BEFORE';
    },
    'measure uses one shared VU': () => {
      const scenario = measureOptions.scenarios.bulk_write_measure;
      return Object.keys(measureOptions.scenarios).length === 1
        && scenario.executor === 'shared-iterations'
        && scenario.vus === 1
        && scenario.iterations === 3
        && scenario.tags.phase === 'measure'
        && scenario.tags.variant === 'AFTER'
        && measureOptions.maxRedirects === 0;
    },
    'valid snake-case response contract is accepted': () => (
      matchesBulkWriteResponseContract(validPayload, 25, 'BEFORE')
    ),
    'valid AFTER response and operation contracts are accepted': () => (
      matchesBulkWriteResponseContract(validAfterPayload, 25, 'AFTER')
        && matchesBulkWriteOperationContract(validAfterPayload.data.operation, 'AFTER')
    ),
    'variant and operation name cannot be mixed': () => !matchesBulkWriteResponseContract(
      payload({ variant: 'AFTER' }),
      25,
      'AFTER',
    ),
    'zero-row response contract is accepted': () => (
      matchesBulkWriteResponseContract(zeroPayload, 0, 'BEFORE')
    ),
    'camel-case response data is rejected': () => !matchesBulkWriteResponseContract({
      success: true,
      data: { ...validPayload.data, dataset_size: undefined, datasetSize: 25 },
    }, 25),
    'wrong expected row count is rejected': () => !matchesBulkWriteResponseContract(
      payload({ expected_rows: 24 }),
      25,
    ),
    'wrong verified row count is rejected': () => !matchesBulkWriteResponseContract(
      payload({ verified_rows: 24 }),
      25,
    ),
    'failed verification is rejected': () => !matchesBulkWriteResponseContract(
      payload({ verification_succeeded: false }),
      25,
    ),
    'failed control isolation is rejected': () => !matchesBulkWriteResponseContract(
      payload({ control_membership_preserved: false }),
      25,
    ),
    'operation requires every SQL count including zeros': () => !matchesBulkWriteOperationContract(
      operation({
        hibernate_statements_by_type: {
          SELECT: 2,
          UPDATE: 1,
          DELETE: 25,
          TOTAL: 28,
        },
      }),
    ),
    'operation requires SQL total to match typed counts': () => !matchesBulkWriteOperationContract(
      operation({ hibernate_statements_by_type: { ...SQL_COUNTS, TOTAL: 29 } }),
    ),
    'operation rejects unexpected JDBC activity': () => !matchesBulkWriteOperationContract(
      operation({ jdbc_batch_calls: 1, jdbc_submitted_rows: 25 }),
    ),
    'operation rejects unexpected JDBC batch size': () => !matchesBulkWriteOperationContract(
      operation({ jdbc_configured_batch_size: 100 }),
    ),
    'operation rejects unexpected JDBC affected rows': () => !matchesBulkWriteOperationContract(
      operation({ jdbc_affected_rows: 25 }),
    ),
    'operation rejects inconsistent server duration units': () => !matchesBulkWriteOperationContract(
      operation({ server_operation_ms: 13 }),
    ),
    'summary keeps server and orchestration latency separate': () => (
      performance.server_operation_ms.p95 === 15
        && performance.http_orchestration_ms.p95 === 36
        && performance.server_operation_ms.p95
          !== performance.http_orchestration_ms.p95
    ),
    'summary keeps verified rows and statement types': () => (
      performance.verified_rows.median === 25
        && performance.hibernate_statements_by_type.SELECT.median === 2
        && performance.hibernate_statements_by_type.DELETE.median === 25
        && performance.hibernate_statements_by_type.TOTAL.median === 28
    ),
    'artifact uses the versioned schema': () => (
      artifact.schema_version === ARTIFACT_SCHEMA_VERSION
        && artifact.schema_version === 'bulk-write-benchmark-v1'
    ),
    'artifact metadata uses only the public allowlist': () => (
      JSON.stringify(metadataKeys) === JSON.stringify([
        'app_commit',
        'app_instance_count',
        'candidate',
        'dataset_size',
        'endpoint',
        'generated_at',
        'jvm_version',
        'mysql_version',
        'operation_name',
        'phase',
        'request_timeout',
        'rewrite_batched_statements',
        'round',
        'run_label',
        'run_order',
        'samples',
        'schema_label',
        'variant',
      ])
        && artifact.metadata.endpoint === BULK_WRITE_ENDPOINT
        && artifact.metadata.schema_label === 'airbob-bulk-write-v1'
    ),
    'AFTER artifact records the matching operation name': () => (
      afterArtifact.metadata.variant === 'AFTER'
        && afterArtifact.metadata.operation_name === 'wishlist-delete-after'
    ),
    'artifact keeps the complete non-sensitive k6 summary': () => (
      artifact.k6_summary.metrics.http_reqs.values.count === 3
        && artifact.k6_summary.root_group.checks[0].name === 'response contract'
        && artifact.k6_summary.state.testRunDurationMs === 2000
    ),
    'artifact records expected and verified row evidence': () => (
      artifact.verification.expected_rows === 25
        && artifact.verification.verified_rows.median === 25
        && artifact.verification.succeeded.successful === 3
    ),
    'artifact redacts sensitive values from public metadata values': () => (
      metadataRedactionArtifact.metadata.run_label
        === 'public-prefix-[REDACTED]-public-suffix'
    ),
    'artifact redacts secret, session, password, email, and user identifiers': () => (
      !serializedArtifact.includes(TOKEN)
        && !serializedArtifact.includes('session-cookie-sentinel')
        && !serializedArtifact.includes('actual-user-9001')
        && !serializedArtifact.includes('benchmark-admin@example.com')
        && !serializedArtifact.includes('password-sentinel')
        && !serializedArtifact.includes('user_id')
        && !serializedArtifact.includes('session_id')
    ),
    'artifact reports JDBC values without pretending null sizes are zero': () => (
      artifact.database_observation.jdbc.batch_calls === 0
        && artifact.database_observation.jdbc.submitted_rows === 0
        && artifact.database_observation.jdbc.configured_batch_size === null
        && artifact.database_observation.jdbc.affected_rows === null
    ),
    'artifact derives JDBC calls and submitted rows from k6 trends': () => (
      jdbcArtifact.database_observation.jdbc.batch_calls === 2
        && jdbcArtifact.database_observation.jdbc.submitted_rows === 25
    ),
  });
}

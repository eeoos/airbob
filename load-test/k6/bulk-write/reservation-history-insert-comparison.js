import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import { loginBenchmarkAccount } from '../lib/benchmark-fixture.js';
import {
  BULK_WRITE_HIBERNATE_METRICS,
  RESERVATION_HISTORY_INSERT_BENCHMARK,
  buildBulkWriteArtifact,
  buildBulkWriteOptions,
  buildBulkWriteRequestBody,
  buildBulkWriteRequestParams,
  matchesBulkWriteResponseContract,
  parseBulkWriteRunConfig,
} from '../lib/bulk-write-benchmark.js';

function requiredCredential(raw, name, trim = false) {
  if (typeof raw !== 'string' || raw.trim().length === 0) {
    throw new Error(`${name} is required`);
  }
  return trim ? raw.trim() : raw;
}

const BENCHMARK = RESERVATION_HISTORY_INSERT_BENCHMARK;
const RUN = parseBulkWriteRunConfig(__ENV, BENCHMARK);
const BENCHMARK_EMAIL = requiredCredential(__ENV.BENCHMARK_EMAIL, 'BENCHMARK_EMAIL', true);
const TEST_PASSWORD = requiredCredential(__ENV.TEST_PASSWORD, 'TEST_PASSWORD');
const TAGS = {
  candidate: BENCHMARK.candidate,
  dataset_size: String(RUN.datasetSize),
  phase: RUN.phase,
  variant: RUN.variant,
};

const sampleSuccess = new Rate('bulk_write_sample_success');
const verificationSuccess = new Rate('bulk_write_verification_success');
const serverOperationMs = new Trend('bulk_write_server_operation_ms', true);
const httpOrchestrationMs = new Trend('bulk_write_http_orchestration_ms', true);
const verifiedRows = new Trend('bulk_write_verified_rows');
const jdbcBatchCalls = new Trend('bulk_write_jdbc_batch_calls');
const jdbcSubmittedRows = new Trend('bulk_write_jdbc_submitted_rows');
const jdbcConfiguredBatchSize = new Trend('bulk_write_jdbc_configured_batch_size');
const jdbcAffectedRows = new Trend('bulk_write_jdbc_affected_rows');
const holdRemovalCalls = new Trend('bulk_write_hold_removal_calls');
const hibernateStatements = {};
Object.entries(BULK_WRITE_HIBERNATE_METRICS).forEach(([type, metricName]) => {
  hibernateStatements[type] = new Trend(metricName);
});

export const options = buildBulkWriteOptions(RUN, BENCHMARK);

http.setResponseCallback(http.expectedStatuses(200));

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function recordOperation(data) {
  const { operation } = data;
  serverOperationMs.add(operation.server_operation_ms, TAGS);
  verifiedRows.add(data.verified_rows, TAGS);
  holdRemovalCalls.add(data.hold_removal_calls, TAGS);
  Object.entries(hibernateStatements).forEach(([type, metric]) => {
    metric.add(operation.hibernate_statements_by_type[type], TAGS);
  });
  jdbcBatchCalls.add(operation.jdbc_batch_calls, TAGS);
  jdbcSubmittedRows.add(operation.jdbc_submitted_rows, TAGS);
  if (operation.jdbc_configured_batch_size !== null) {
    jdbcConfiguredBatchSize.add(operation.jdbc_configured_batch_size, TAGS);
  }
  if (operation.jdbc_affected_rows !== null) {
    jdbcAffectedRows.add(operation.jdbc_affected_rows, TAGS);
  }
}

export function setup() {
  return {
    sessionId: loginBenchmarkAccount({
      baseUrl: RUN.baseUrl,
      email: BENCHMARK_EMAIL,
      password: TEST_PASSWORD,
      redirects: 0,
    }),
  };
}

export default function (setupData) {
  const response = http.post(
    `${RUN.baseUrl}${BENCHMARK.endpoint}`,
    buildBulkWriteRequestBody({
      variant: RUN.variant,
      datasetSize: RUN.datasetSize,
    }, BENCHMARK),
    buildBulkWriteRequestParams({
      benchmarkToken: RUN.benchmarkToken,
      sessionId: setupData.sessionId,
      timeout: RUN.requestTimeout,
      tags: {
        ...TAGS,
        name: BENCHMARK.requestName,
      },
    }),
  );
  const payload = parsePayload(response);
  const contractMatches = matchesBulkWriteResponseContract(
    payload,
    RUN.datasetSize,
    RUN.variant,
    BENCHMARK,
  );
  const succeeded = response.status === 200 && contractMatches;

  httpOrchestrationMs.add(response.timings.duration, TAGS);
  sampleSuccess.add(succeeded, TAGS);
  verificationSuccess.add(
    contractMatches && payload.data.verification_succeeded === true,
    TAGS,
  );

  check(response, {
    'reservation history insert benchmark returns HTTP 200': (result) => result.status === 200,
    'reservation history insert benchmark matches the response contract': () => contractMatches,
    'reservation history insert benchmark verifies snapshots and controls': () => (
      contractMatches && payload.data.verification_succeeded === true
    ),
    'reservation history insert reports contract-supported JDBC measurements': () => contractMatches,
    'reservation history insert excludes Redis network and records logical hold removals': () => (
      contractMatches
        && payload.data.redis_network_excluded === true
        && payload.data.hold_removal_calls === RUN.datasetSize
    ),
  }, TAGS);

  if (succeeded) {
    recordOperation(payload.data);
  }
}

function format(value, digits = 2) {
  return Number.isFinite(value) ? value.toFixed(digits) : 'n/a';
}

export function handleSummary(data) {
  const artifact = buildBulkWriteArtifact({
    config: RUN,
    k6Summary: data,
    sensitiveValues: [RUN.benchmarkToken, BENCHMARK_EMAIL, TEST_PASSWORD],
  }, BENCHMARK);
  const { performance } = artifact;
  const output = [
    `bulk write: ${BENCHMARK.candidate}/${RUN.variant} phase=${RUN.phase} dataset=${RUN.datasetSize} run=${RUN.runLabel}`,
    [
      `samples=${performance.samples.attempted}`,
      `successful=${performance.samples.successful}`,
      `verified=${performance.verification.successful}`,
    ].join(' '),
    [
      `server_operation_ms p50=${format(performance.server_operation_ms.median)}`,
      `p95=${format(performance.server_operation_ms.p95)}`,
      `max=${format(performance.server_operation_ms.max)}`,
    ].join(' '),
    [
      `http_orchestration_ms p50=${format(performance.http_orchestration_ms.median)}`,
      `p95=${format(performance.http_orchestration_ms.p95)}`,
      `max=${format(performance.http_orchestration_ms.max)}`,
    ].join(' '),
    `result=${RUN.resultPath}`,
    '',
  ].join('\n');

  return {
    stdout: output,
    [RUN.resultPath]: JSON.stringify(artifact, null, 2),
  };
}

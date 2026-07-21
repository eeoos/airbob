import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import { loginBenchmarkAccount } from '../lib/benchmark-fixture.js';
import {
  BULK_WRITE_CANDIDATE,
  BULK_WRITE_ENDPOINT,
  BULK_WRITE_HIBERNATE_METRICS,
  BULK_WRITE_REQUEST_NAME,
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

const RUN = parseBulkWriteRunConfig(__ENV);
const BENCHMARK_EMAIL = requiredCredential(__ENV.BENCHMARK_EMAIL, 'BENCHMARK_EMAIL', true);
const TEST_PASSWORD = requiredCredential(__ENV.TEST_PASSWORD, 'TEST_PASSWORD');
const TAGS = {
  candidate: BULK_WRITE_CANDIDATE,
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
const hibernateStatements = {};
Object.entries(BULK_WRITE_HIBERNATE_METRICS).forEach(([type, metricName]) => {
  hibernateStatements[type] = new Trend(metricName);
});

export const options = buildBulkWriteOptions(RUN);

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
  Object.entries(hibernateStatements).forEach(([type, metric]) => {
    metric.add(operation.hibernate_statements_by_type[type], TAGS);
  });
  jdbcBatchCalls.add(operation.jdbc_batch_calls, TAGS);
  jdbcSubmittedRows.add(operation.jdbc_submitted_rows, TAGS);
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
    `${RUN.baseUrl}${BULK_WRITE_ENDPOINT}`,
    buildBulkWriteRequestBody({
      variant: RUN.variant,
      datasetSize: RUN.datasetSize,
    }),
    buildBulkWriteRequestParams({
      benchmarkToken: RUN.benchmarkToken,
      sessionId: setupData.sessionId,
      timeout: RUN.requestTimeout,
      tags: {
        ...TAGS,
        name: BULK_WRITE_REQUEST_NAME,
      },
    }),
  );
  const payload = parsePayload(response);
  const contractMatches = matchesBulkWriteResponseContract(payload, RUN.datasetSize);
  const succeeded = response.status === 200 && contractMatches;

  httpOrchestrationMs.add(response.timings.duration, TAGS);
  sampleSuccess.add(succeeded, TAGS);
  verificationSuccess.add(
    contractMatches && payload.data.verification_succeeded === true,
    TAGS,
  );

  check(response, {
    'wishlist delete benchmark returns HTTP 200': (result) => result.status === 200,
    'wishlist delete benchmark matches the snake-case response contract': () => contractMatches,
    'wishlist delete benchmark verifies exact rows and control fixtures': () => (
      contractMatches && payload.data.verification_succeeded === true
    ),
    'wishlist delete U2 baseline reports no JDBC batch activity': () => (
      contractMatches
        && payload.data.operation.jdbc_batch_calls === 0
        && payload.data.operation.jdbc_submitted_rows === 0
        && payload.data.operation.jdbc_configured_batch_size === null
        && payload.data.operation.jdbc_affected_rows === null
    ),
  }, TAGS);

  if (contractMatches) {
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
  });
  const { performance } = artifact;
  const output = [
    `bulk write: WISHLIST_DELETE/BEFORE phase=${RUN.phase} dataset=${RUN.datasetSize} run=${RUN.runLabel}`,
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

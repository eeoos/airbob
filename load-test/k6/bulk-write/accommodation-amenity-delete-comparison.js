import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import { loginBenchmarkAccount } from '../lib/benchmark-fixture.js';
import {
  ACCOMMODATION_AMENITY_DELETE_BENCHMARK as BENCHMARK,
  BULK_WRITE_HIBERNATE_METRICS,
  accommodationAmenityServerOperationMetricName,
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

const RUN = parseBulkWriteRunConfig(__ENV, BENCHMARK);
const BENCHMARK_EMAIL = requiredCredential(__ENV.BENCHMARK_EMAIL, 'BENCHMARK_EMAIL', true);
const TEST_PASSWORD = requiredCredential(__ENV.TEST_PASSWORD, 'TEST_PASSWORD');
const BASE_TAGS = {
  candidate: BENCHMARK.candidate,
  dataset_size: String(RUN.datasetSize),
  measurement: RUN.measurement,
  phase: RUN.phase,
  variant: RUN.variant,
};

const sampleSuccess = new Rate('bulk_write_sample_success');
const verificationSuccess = new Rate('bulk_write_verification_success');
const serverOperationMs = new Trend('bulk_write_server_operation_ms', true);
const fullReplacementServerOperationMs = new Trend(
  accommodationAmenityServerOperationMetricName('FULL_REPLACEMENT'),
  true,
);
const deleteOnlyServerOperationMs = new Trend(
  accommodationAmenityServerOperationMetricName('DELETE_ONLY'),
  true,
);
const httpOrchestrationMs = new Trend('bulk_write_http_orchestration_ms', true);
const verifiedRows = new Trend('bulk_write_verified_rows');
const activeAmenityCodeCount = new Trend('bulk_write_active_amenity_code_count');
const jdbcBatchCalls = new Trend('bulk_write_jdbc_batch_calls');
const jdbcSubmittedRows = new Trend('bulk_write_jdbc_submitted_rows');
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

function responseTags(data) {
  return {
    ...BASE_TAGS,
    active_amenity_code_count: String(data.active_amenity_code_count),
    workload_class: data.workload_class,
  };
}

function recordOperation(data) {
  const tags = responseTags(data);
  const { operation } = data;
  serverOperationMs.add(operation.server_operation_ms, tags);
  if (RUN.measurement === 'FULL_REPLACEMENT') {
    fullReplacementServerOperationMs.add(operation.server_operation_ms, tags);
  } else {
    deleteOnlyServerOperationMs.add(operation.server_operation_ms, tags);
  }
  verifiedRows.add(data.old_target_rows_verified, tags);
  activeAmenityCodeCount.add(data.active_amenity_code_count, tags);
  Object.entries(hibernateStatements).forEach(([type, metric]) => {
    metric.add(operation.hibernate_statements_by_type[type], tags);
  });
  jdbcBatchCalls.add(operation.jdbc_batch_calls, tags);
  jdbcSubmittedRows.add(operation.jdbc_submitted_rows, tags);
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
    buildBulkWriteRequestBody(RUN, BENCHMARK),
    buildBulkWriteRequestParams({
      benchmarkToken: RUN.benchmarkToken,
      sessionId: setupData.sessionId,
      timeout: RUN.requestTimeout,
      tags: {
        ...BASE_TAGS,
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
    RUN.measurement,
  );
  const succeeded = response.status === 200 && contractMatches;
  const tags = contractMatches ? responseTags(payload.data) : BASE_TAGS;

  httpOrchestrationMs.add(response.timings.duration, tags);
  sampleSuccess.add(succeeded, tags);
  verificationSuccess.add(
    contractMatches && payload.data.verification_succeeded === true,
    tags,
  );

  check(response, {
    'accommodation amenity benchmark returns HTTP 200': (result) => result.status === 200,
    'accommodation amenity benchmark matches its measurement contract': () => contractMatches,
    'accommodation amenity benchmark verifies target history and controls': () => (
      contractMatches
      && payload.data.target_parent_preserved === true
      && payload.data.history_effect_matched === true
      && payload.data.control_accommodation_preserved === true
      && payload.data.control_amenities_preserved === true
    ),
    'accommodation amenity candidate reports no JDBC batch activity': () => (
      contractMatches
      && payload.data.operation.jdbc_batch_calls === 0
      && payload.data.operation.jdbc_submitted_rows === 0
      && payload.data.operation.jdbc_configured_batch_size === null
      && payload.data.operation.jdbc_affected_rows === null
    ),
  }, tags);

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
  }, BENCHMARK);
  const { performance } = artifact;
  const output = [
    `bulk write: ${BENCHMARK.candidate}/${RUN.variant}/${RUN.measurement} phase=${RUN.phase} dataset=${RUN.datasetSize} run=${RUN.runLabel}`,
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

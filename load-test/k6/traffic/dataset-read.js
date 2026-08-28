import { check } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import crypto from 'k6/crypto';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  applyCacheWarmCoverageValidity,
  buildCacheWarmCoverage,
  CACHE_WARM_PREFETCH_METRIC,
  DATASET_READ_TARGETS,
  buildDatasetReadTarget,
  matchesDatasetReadContract,
  parseCacheResetReceipt,
  selectCacheResourceId,
} from '../lib/dataset-read-target.js';
import {
  buildTrafficOptions,
  parseDatasetTrafficRunConfig,
  summarizeTrafficMetrics,
} from '../lib/traffic-benchmark.js';

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function parseHttpOrigin(raw) {
  requireCondition(typeof raw === 'string' && raw === raw.trim(), 'BASE_URL is required');
  const value = raw.replace(/\/+$/, '');
  requireCondition(/^https?:\/\/[^/]+$/.test(value), 'BASE_URL must be one HTTP origin');
  return value;
}

const manifestPath = __ENV.BENCHMARK_DATASET_MANIFEST;
requireCondition(
  typeof manifestPath === 'string' && manifestPath.length > 0,
  'BENCHMARK_DATASET_MANIFEST is required',
);
const manifestRaw = open(manifestPath);
const run = parseDatasetTrafficRunConfig(__ENV, manifestRaw, DATASET_READ_TARGETS);
const target = buildDatasetReadTarget(
  run.manifest,
  run.target,
  __ENV.CAPSULE_TARGET,
  __ENV,
);
const baseUrl = parseHttpOrigin(__ENV.BASE_URL);

let resetReceiptSha256 = null;
if (target.name === 'cache-detail') {
  const receiptPath = __ENV.CACHE_RESET_RECEIPT;
  requireCondition(
    typeof receiptPath === 'string' && receiptPath.length > 0,
    'CACHE_RESET_RECEIPT is required for cache-detail',
  );
  const receiptRaw = open(receiptPath);
  parseCacheResetReceipt(receiptRaw, {
    manifestSha256: run.manifestSha256,
    cacheEnabled: target.cacheEnabled,
    variant: target.cacheVariant,
    runLabel: run.runLabel,
  });
  resetReceiptSha256 = crypto.sha256(receiptRaw, 'hex');
}

const clientDuration = new Trend('traffic_client_duration', true);
const requestSuccess = new Rate('traffic_request_success');
const completedSamples = new Counter('traffic_completed_samples');
const completedWarmPrefetchKeys = new Counter(CACHE_WARM_PREFETCH_METRIC);

export const options = buildTrafficOptions(run);

export function inspect() {
  // All dataset, capsule, target, and reset-receipt validation happens during init.
}

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function setup() {
  if (run.mode !== 'measure'
      || target.name !== 'cache-detail'
      || target.cacheVariant !== 'warm') {
    return;
  }
  const tags = {
    phase: 'cache-prefetch',
    role: run.role,
    target: run.target,
    capsule: target.capsuleId,
    profile: target.profileId,
    name: target.requestName,
  };
  for (const resourceId of target.resourceIds) {
    const response = http.get(`${baseUrl}/api/v1/accommodations/${resourceId}`, { tags });
    requireCondition(
      response.status === 200
        && matchesDatasetReadContract(target, parsePayload(response), resourceId),
      `warm cache prefetch failed for accommodation ${resourceId}`,
    );
    completedWarmPrefetchKeys.add(1, tags);
  }
}

function requestTarget(phase) {
  const iteration = exec.scenario.iterationInTest;
  const expectedResourceId = target.name === 'cache-detail'
    ? selectCacheResourceId(target, iteration)
    : undefined;
  const path = target.name === 'cache-detail'
    ? `/api/v1/accommodations/${expectedResourceId}`
    : target.path;
  const tags = {
    phase,
    role: run.role,
    target: run.target,
    capsule: target.capsuleId,
    profile: target.profileId,
    name: target.requestName,
  };
  const response = http.get(`${baseUrl}${path}`, { tags });
  const contractMatches = matchesDatasetReadContract(
    target,
    parsePayload(response),
    expectedResourceId,
  );
  const succeeded = response.status === 200 && contractMatches;

  check(response, {
    [`${run.target} returns HTTP 200`]: (value) => value.status === 200,
    [`${run.target} matches its manifest contract`]: () => contractMatches,
  }, tags);
  requestSuccess.add(succeeded, tags);
  if (phase === 'measure') {
    clientDuration.add(response.timings.duration, tags);
    completedSamples.add(1, tags);
  }
}

export function warmup() {
  requestTarget('warmup');
}

export function measure() {
  requestTarget('measure');
}

export function handleSummary(data) {
  if (run.mode !== 'measure') {
    return { stdout: `dataset read ${run.mode} completed: ${target.capsuleId}/${target.profileId}\n` };
  }
  const artifact = summarizeTrafficMetrics(data, run);
  artifact.metadata.worldVersion = run.manifest.world.version;
  artifact.metadata.benchmarkDatasetManifestSha256 = run.manifestSha256;
  artifact.metadata.capsuleId = target.capsuleId;
  artifact.metadata.capsuleTarget = target.profileId;
  artifact.metadata.endpointTemplate = target.requestName;
  if (target.name === 'cache-detail') {
    const warmKeyCoverage = buildCacheWarmCoverage(target, run.mode, data);
    artifact.metadata.cache = {
      variant: target.cacheVariant,
      enabled: target.cacheEnabled,
      distribution: target.distribution,
      resetReceiptSha256,
      warmKeyCoverage,
    };
    applyCacheWarmCoverageValidity(artifact, warmKeyCoverage);
  } else {
    artifact.metadata.search = {
      expectedRows: target.expectedRows,
      expectedApiReportedTotal: target.expectedApiReportedTotal,
      query: target.query,
    };
  }
  artifact.manifestGaps = target.name === 'cache-detail'
    ? ['cache-micrometer-series-not-yet-captured']
    : [];
  const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
  return {
    stdout: serialized,
    [run.resultPath]: serialized,
  };
}

import crypto from 'k6/crypto';

import { parseBenchmarkDatasetManifest } from './benchmark-dataset-manifest.js';
import { parseBenchmarkManifest } from './benchmark-manifest.js';
import {
  parseDurationSeconds,
  parsePositiveInteger,
  parseRequiredText,
} from './read-model-benchmark.js';

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function metricValues(data, name) {
  return data.metrics?.[name]?.values || {};
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

function canonicalRunLabel(raw) {
  const value = parseRequiredText(raw, 'RUN_LABEL');
  requireCondition(
    /^[a-z0-9][a-z0-9-]{2,79}$/.test(value),
    'RUN_LABEL must be a canonical lowercase label',
  );
  return value;
}

function canonicalTarget(raw, targetNames) {
  requireCondition(
    Array.isArray(targetNames)
      && targetNames.length > 0
      && targetNames.every((name) => /^[a-z0-9][a-z0-9-]*$/.test(name))
      && new Set(targetNames).size === targetNames.length,
    'traffic target allowlist is invalid',
  );
  const value = parseRequiredText(raw, 'TARGET');
  requireCondition(value !== 'mixed' && targetNames.includes(value), 'TARGET is not allowlisted');
  return value;
}

function parseLegacyResultPath(raw, canonicalPath) {
  const resultPath = raw || canonicalPath;
  requireCondition(
    resultPath === canonicalPath,
    'K6_RESULT_PATH must match the canonical traffic artifact path',
  );
  return resultPath;
}

function parseDatasetResultPath(raw, canonicalPath) {
  requireCondition(
    raw === undefined || raw === canonicalPath,
    'K6_RESULT_PATH must match the canonical traffic artifact path',
  );
  return canonicalPath;
}

function parseTrafficRunConfigCore(environment, manifestRaw, targetNames, contract) {
  requireCondition(
    manifestRaw instanceof ArrayBuffer || typeof manifestRaw === 'string',
    contract.manifestContentError,
  );
  const manifestText = typeof manifestRaw === 'string'
    ? manifestRaw
    : String.fromCharCode(...new Uint8Array(manifestRaw));
  const manifest = contract.parseManifest(manifestText);
  const mode = parseRequiredText(environment.MODE, 'MODE');
  requireCondition(['inspect', 'warmup', 'measure'].includes(mode), 'MODE is invalid');
  const role = parseRequiredText(environment.ROLE, 'ROLE');
  requireCondition(role === 'guest', contract.roleError);
  const target = canonicalTarget(environment.TARGET, targetNames);
  const rate = parsePositiveInteger(environment.RATE, 'RATE');
  const duration = parseRequiredText(environment.DURATION, 'DURATION');
  const durationSeconds = parseDurationSeconds(duration, 'DURATION');
  requireCondition(durationSeconds >= 1, 'DURATION must be at least 1s');
  const minimumCompletedSamples = parsePositiveInteger(
    environment.MIN_COMPLETED_SAMPLES,
    'MIN_COMPLETED_SAMPLES',
  );
  const preAllocatedVUs = parsePositiveInteger(
    environment.PRE_ALLOCATED_VUS || String(Math.max(20, rate * 4)),
    'PRE_ALLOCATED_VUS',
  );
  const maxVUs = parsePositiveInteger(
    environment.MAX_VUS || String(Math.max(preAllocatedVUs, rate * 10)),
    'MAX_VUS',
  );
  requireCondition(maxVUs >= preAllocatedVUs, 'MAX_VUS must be at least PRE_ALLOCATED_VUS');
  const round = parsePositiveInteger(environment.ROUND, 'ROUND');
  const runOrder = parsePositiveInteger(environment.RUN_ORDER, 'RUN_ORDER');
  const runLabel = canonicalRunLabel(environment.RUN_LABEL);
  const appCommit = parseRequiredText(environment.APP_COMMIT, 'APP_COMMIT');
  requireCondition(/^[0-9a-f]{40}$/.test(appCommit), 'APP_COMMIT must be one full Git commit');
  const appInstanceCount = parsePositiveInteger(
    environment.APP_INSTANCE_COUNT,
    'APP_INSTANCE_COUNT',
  );
  const resultPath = contract.parseResultPath(
    environment.K6_RESULT_PATH,
    `build/k6/traffic/${runLabel}.json`,
  );

  return {
    mode,
    role,
    target,
    rate,
    duration,
    durationSeconds,
    minimumCompletedSamples,
    preAllocatedVUs,
    maxVUs,
    round,
    runOrder,
    runLabel,
    appCommit,
    appInstanceCount,
    resultPath,
    manifest,
    manifestSha256: crypto.sha256(manifestText, 'hex'),
    releaseKind: contract.releaseKind,
    claimScope: contract.claimScope(manifest),
  };
}

export function parseTrafficRunConfig(environment, manifestRaw, targetNames) {
  return parseTrafficRunConfigCore(environment, manifestRaw, targetNames, {
    manifestContentError: 'BENCHMARK_MANIFEST content is required',
    parseManifest: parseBenchmarkManifest,
    roleError: 'ROLE must be guest for the first vertical slice',
    parseResultPath: parseLegacyResultPath,
    releaseKind: 'pipeline-rehearsal',
    claimScope: () => 'pipeline-only',
  });
}

export function parseDatasetTrafficRunConfig(environment, manifestRaw, targetNames) {
  return parseTrafficRunConfigCore(environment, manifestRaw, targetNames, {
    manifestContentError: 'BENCHMARK_DATASET_MANIFEST content is required',
    parseManifest: parseBenchmarkDatasetManifest,
    roleError: 'ROLE must be guest for dataset read experiments',
    parseResultPath: parseDatasetResultPath,
    releaseKind: 'pipeline-rehearsal',
    claimScope: () => 'pipeline-only',
  });
}

function arrivalRateScenario(config, exec) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate: config.rate,
    timeUnit: '1s',
    duration: config.duration,
    preAllocatedVUs: config.preAllocatedVUs,
    maxVUs: config.maxVUs,
    gracefulStop: '5s',
  };
}

export function buildTrafficOptions(config) {
  if (config.mode === 'inspect') {
    return {
      scenarios: {
        inspect: {
          executor: 'shared-iterations',
          exec: 'inspect',
          vus: 1,
          iterations: 1,
          maxDuration: '30s',
        },
      },
    };
  }
  if (config.mode === 'warmup') {
    return {
      scenarios: { warmup: arrivalRateScenario(config, 'warmup') },
      thresholds: {
        traffic_request_success: ['rate==1'],
        http_req_failed: ['rate==0'],
        dropped_iterations: ['count==0'],
      },
    };
  }
  requireCondition(config.mode === 'measure', 'traffic options mode is invalid');
  return {
    scenarios: { measure: arrivalRateScenario(config, 'measure') },
    thresholds: {
      traffic_request_success: ['rate==1'],
      http_req_failed: ['rate==0'],
      dropped_iterations: ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  };
}

export function summarizeTrafficMetrics(data, config) {
  requireCondition(config.mode === 'measure', 'only measure can produce a traffic artifact');
  const success = metricValues(data, 'traffic_request_success');
  const latency = metricValues(data, 'traffic_client_duration');
  const completedMetric = metricValues(data, 'traffic_completed_samples');
  const iterations = metricValues(data, 'iterations');
  const failedRequests = metricValues(data, 'http_req_failed');
  const checks = metricValues(data, 'checks');
  const dropped = metricValues(data, 'dropped_iterations');
  const completed = Number(completedMetric.count || 0);
  const successful = Number(success.passes || 0);
  const requestFailures = Number(success.fails || 0);
  const droppedIterations = Number(dropped.count || 0);
  const reasons = [];

  if (requestFailures > 0 || Number(failedRequests.rate || 0) > 0) {
    reasons.push('request-errors');
  }
  if (Number(checks.fails || 0) > 0 || (checks.rate !== undefined && checks.rate < 1)) {
    reasons.push('check-failures');
  }
  if (droppedIterations > 0) {
    reasons.push('dropped-iterations');
  }
  if (completed < config.minimumCompletedSamples) {
    reasons.push('minimum-samples-not-met');
  }

  return {
    schemaVersion: 1,
    metadata: {
      generatedAt: new Date().toISOString(),
      releaseKind: config.releaseKind,
      claimScope: config.claimScope,
      role: config.role,
      target: config.target,
      datasetVersion: config.manifest.datasetVersion,
      manifestSha256: config.manifestSha256,
      appCommit: config.appCommit,
      appInstanceCount: config.appInstanceCount,
      round: config.round,
      runOrder: config.runOrder,
      runLabel: config.runLabel,
    },
    validity: {
      status: reasons.length === 0 ? 'valid' : 'invalid',
      reasons,
    },
    load: {
      configuredRatePerSecond: config.rate,
      duration: config.duration,
      durationSeconds: config.durationSeconds,
      preAllocatedVUs: config.preAllocatedVUs,
      maxVUs: config.maxVUs,
      iterations: {
        started: Number(iterations.count || 0),
        completed,
        successful,
        minimumRequired: config.minimumCompletedSamples,
        dropped: droppedIterations,
      },
      achievedRps: completed / config.durationSeconds,
    },
    performance: {
      errorRate: completed === 0 ? 1 : requestFailures / completed,
      latencyMs: {
        avg: finiteOrNull(latency.avg),
        min: finiteOrNull(latency.min),
        median: finiteOrNull(latency.med),
        p90: finiteOrNull(latency['p(90)']),
        p95: finiteOrNull(latency['p(95)']),
        p99: finiteOrNull(latency['p(99)']),
        max: finiteOrNull(latency.max),
      },
    },
  };
}

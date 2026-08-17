#!/usr/bin/env node

import {
  closeSync,
  constants,
  fsyncSync,
  linkSync,
  lstatSync,
  openSync,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(dirname(SCRIPT_PATH), '../../..');
const ARTIFACT_ROOT = resolve(REPO_ROOT, 'build/k6/traffic');
const MAX_INPUT_BYTES = 1024 * 1024;
const COUNTER_KEYS = ['count', 'timerWait', 'rowsExamined', 'rowsSent'];
const CURRENT_FLYWAY_VERSION = '17';

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value, expected) {
  if (!isObject(value)) {
    return false;
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length
    && actual.every((key, index) => key === wanted[index]);
}

function positiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function canonicalSha256(value) {
  return typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
}

function canonicalFlywayVersion(value) {
  return typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
}

function parseCounter(value) {
  requireCondition(typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value), 'SQL snapshot row contract is invalid');
  return BigInt(value);
}

function parseSnapshot(raw) {
  requireCondition(typeof raw === 'string' && Buffer.byteLength(raw) <= MAX_INPUT_BYTES, 'SQL snapshot is missing or too large');
  const rows = raw.split('\n').filter((line) => line.length > 0);
  const parsed = new Map();
  for (const line of rows) {
    let row;
    try {
      row = JSON.parse(line);
    } catch (_) {
      throw new Error('SQL snapshot row contract is invalid');
    }
    requireCondition(
      hasExactKeys(row, ['schemaName', 'digest', 'digestText', ...COUNTER_KEYS])
        && row.schemaName === 'airbobdb'
        && typeof row.digest === 'string'
        && /^[0-9a-f]{64}$/.test(row.digest)
        && typeof row.digestText === 'string'
        && row.digestText.length > 0
        && row.digestText.length <= 4096
        && !parsed.has(row.digest),
      'SQL snapshot row contract is invalid',
    );
    parsed.set(row.digest, {
      digest: row.digest,
      digestText: row.digestText,
      count: parseCounter(row.count),
      timerWait: parseCounter(row.timerWait),
      rowsExamined: parseCounter(row.rowsExamined),
      rowsSent: parseCounter(row.rowsSent),
    });
  }
  return parsed;
}

function safeNumber(value) {
  requireCondition(value <= BigInt(Number.MAX_SAFE_INTEGER), 'SQL counter delta exceeds safe aggregation range');
  return Number(value);
}

function snapshotDelta(before, after) {
  const reasons = new Set();
  const deltas = [];

  for (const digest of before.keys()) {
    if (!after.has(digest)) {
      reasons.add('sql-digest-eviction');
    }
  }
  for (const [digest, afterRow] of after) {
    const beforeRow = before.get(digest);
    if (beforeRow && beforeRow.digestText !== afterRow.digestText) {
      reasons.add('sql-digest-text-drift');
      continue;
    }
    const baseline = beforeRow || {
      count: 0n,
      timerWait: 0n,
      rowsExamined: 0n,
      rowsSent: 0n,
    };
    if (COUNTER_KEYS.some((key) => afterRow[key] < baseline[key])) {
      reasons.add('sql-counter-reset');
      continue;
    }
    const count = safeNumber(afterRow.count - baseline.count);
    const timerWait = safeNumber(afterRow.timerWait - baseline.timerWait);
    const rowsExamined = safeNumber(afterRow.rowsExamined - baseline.rowsExamined);
    const rowsSent = safeNumber(afterRow.rowsSent - baseline.rowsSent);
    if (count === 0 && timerWait === 0 && rowsExamined === 0 && rowsSent === 0) {
      continue;
    }
    deltas.push({
      digest,
      digestText: afterRow.digestText,
      calls: count,
      totalTimeMs: timerWait / 1_000_000_000,
      timePerCallMs: count === 0 ? null : (timerWait / 1_000_000_000) / count,
      rowsExamined,
      rowsExaminedPerCall: count === 0 ? null : rowsExamined / count,
      rowsSent,
      rowsSentPerCall: count === 0 ? null : rowsSent / count,
    });
  }
  deltas.sort((left, right) => left.digest.localeCompare(right.digest));
  return { deltas, reasons: [...reasons] };
}

function ranking(deltas, key) {
  return [...deltas].sort((left, right) => {
    const leftValue = left[key] ?? -1;
    const rightValue = right[key] ?? -1;
    return rightValue - leftValue || left.digest.localeCompare(right.digest);
  });
}

function metadataContract(metadata) {
  return hasExactKeys(metadata, [
    'schemaVersion',
    'releaseKind',
    'claimScope',
    'runId',
    'datasetRelease',
    'datasetManifestSha256',
    'benchmarkManifestSha256',
    'appCommit',
    'imageDigest',
    'harnessCommit',
    'flywayVersion',
    'appInstanceCount',
    'target',
    'expectedSqlCallsPerRequest',
    'window',
    'postRun',
  ])
    && metadata.schemaVersion === 1
    && metadata.releaseKind === 'pipeline-rehearsal'
    && metadata.claimScope === 'pipeline-only'
    && typeof metadata.runId === 'string'
    && /^[a-z0-9][a-z0-9-]{2,31}$/.test(metadata.runId)
    && typeof metadata.datasetRelease === 'string'
    && /^[a-z0-9][a-z0-9._-]{2,63}$/.test(metadata.datasetRelease)
    && canonicalSha256(metadata.datasetManifestSha256)
    && canonicalSha256(metadata.benchmarkManifestSha256)
    && typeof metadata.appCommit === 'string'
    && /^[0-9a-f]{40}$/.test(metadata.appCommit)
    && typeof metadata.imageDigest === 'string'
    && /^sha256:[0-9a-f]{64}$/.test(metadata.imageDigest)
    && typeof metadata.harnessCommit === 'string'
    && /^[0-9a-f]{40}$/.test(metadata.harnessCommit)
    && canonicalFlywayVersion(metadata.flywayVersion)
    && metadata.flywayVersion === CURRENT_FLYWAY_VERSION
    && positiveInteger(metadata.appInstanceCount)
    && metadata.target === 'accommodation-detail'
    && positiveInteger(metadata.expectedSqlCallsPerRequest)
    && hasExactKeys(metadata.window, ['startEpochMs', 'endEpochMs'])
    && Number.isSafeInteger(metadata.window.startEpochMs)
    && Number.isSafeInteger(metadata.window.endEpochMs)
    && metadata.window.startEpochMs > 0
    && metadata.window.endEpochMs > metadata.window.startEpochMs
    && hasExactKeys(metadata.postRun, [
      'datasetManifestSha256',
      'benchmarkManifestSha256',
      'imageDigest',
      'flywayVersion',
      'appInstanceCount',
    ])
    && canonicalSha256(metadata.postRun.datasetManifestSha256)
    && canonicalSha256(metadata.postRun.benchmarkManifestSha256)
    && /^sha256:[0-9a-f]{64}$/.test(metadata.postRun.imageDigest)
    && canonicalFlywayVersion(metadata.postRun.flywayVersion)
    && positiveInteger(metadata.postRun.appInstanceCount);
}

function prometheusContract(prometheus) {
  return hasExactKeys(prometheus, ['schemaVersion', 'startEpochMs', 'endEpochMs', 'queries'])
    && prometheus.schemaVersion === 1
    && Number.isSafeInteger(prometheus.startEpochMs)
    && Number.isSafeInteger(prometheus.endEpochMs)
    && hasExactKeys(prometheus.queries, ['requestCount', 'queryCount', 'hikariPending'])
    && Object.values(prometheus.queries).every((query) => (
      isObject(query)
        && query.status === 'success'
        && isObject(query.data)
        && query.data.resultType === 'matrix'
        && Array.isArray(query.data.result)
        && query.data.result.every((series) => (
          hasExactKeys(series, ['metric', 'values'])
            && isObject(series.metric)
            && Array.isArray(series.values)
            && series.values.every((sample) => (
              Array.isArray(sample)
                && sample.length === 2
                && Number.isFinite(sample[0])
                && typeof sample[1] === 'string'
            ))
        ))
    ));
}

function boundedPrometheus(prometheus, startEpochMs, endEpochMs) {
  return {
    schemaVersion: prometheus.schemaVersion,
    startEpochMs: prometheus.startEpochMs,
    endEpochMs: prometheus.endEpochMs,
    queries: Object.fromEntries(Object.entries(prometheus.queries).map(([name, query]) => [
      name,
      {
        status: query.status,
        data: {
          resultType: query.data.resultType,
          result: query.data.result.map((series) => ({
            metric: series.metric,
            values: series.values.filter(([timestamp]) => (
              timestamp * 1000 >= startEpochMs && timestamp * 1000 <= endEpochMs
            )),
          })),
        },
      },
    ])),
  };
}

export function verifyIdleControl(beforeRaw, afterRaw) {
  const idle = snapshotDelta(parseSnapshot(beforeRaw), parseSnapshot(afterRaw));
  const reasons = new Set(idle.reasons);
  if (idle.deltas.some((delta) => delta.calls > 0)) {
    reasons.add('ambient-sql-delta');
  }
  return {
    status: reasons.size === 0 ? 'valid' : 'invalid',
    reasons: [...reasons].sort(),
    deltas: idle.deltas,
  };
}

export function aggregateTrafficResult(input) {
  requireCondition(isObject(input), 'traffic aggregation input is invalid');
  const { metadata, k6, prometheus } = input;
  requireCondition(metadataContract(metadata), 'traffic aggregation metadata is invalid');
  requireCondition(
    hasExactKeys(k6, ['schemaVersion', 'metadata', 'validity', 'load', 'performance', 'manifestGaps'])
      && k6.schemaVersion === 1
      && isObject(k6.metadata)
      && isObject(k6.validity)
      && isObject(k6.load?.iterations)
      && isObject(k6.performance?.latencyMs)
      && Array.isArray(k6.manifestGaps),
    'k6 result contract is invalid',
  );
  requireCondition(prometheusContract(prometheus), 'Prometheus result contract is invalid');

  const idle = verifyIdleControl(input.idleBefore, input.idleAfter);
  const measurement = snapshotDelta(parseSnapshot(input.before), parseSnapshot(input.after));
  const reasons = new Set([...idle.reasons, ...measurement.reasons]);
  if (k6.validity.status !== 'valid' || !Array.isArray(k6.validity.reasons) || k6.validity.reasons.length > 0) {
    reasons.add('k6-invalid');
  }
  const provenanceMatches = k6.metadata.releaseKind === metadata.releaseKind
    && k6.metadata.claimScope === metadata.claimScope
    && k6.metadata.role === 'guest'
    && k6.metadata.datasetVersion === 'nplus1-v1'
    && k6.metadata.target === metadata.target
    && k6.metadata.manifestSha256 === metadata.benchmarkManifestSha256
    && k6.metadata.appCommit === metadata.appCommit
    && k6.metadata.appInstanceCount === metadata.appInstanceCount
    && metadata.postRun.datasetManifestSha256 === metadata.datasetManifestSha256
    && metadata.postRun.benchmarkManifestSha256 === metadata.benchmarkManifestSha256
    && metadata.postRun.imageDigest === metadata.imageDigest
    && metadata.postRun.flywayVersion === metadata.flywayVersion
    && metadata.postRun.appInstanceCount === metadata.appInstanceCount;
  if (!provenanceMatches) {
    reasons.add('run-provenance-drift');
  }
  if (prometheus.startEpochMs !== metadata.window.startEpochMs
    || prometheus.endEpochMs !== metadata.window.endEpochMs) {
    reasons.add('measurement-window-drift');
  }
  const measurementPrometheus = boundedPrometheus(
    prometheus,
    metadata.window.startEpochMs,
    metadata.window.endEpochMs,
  );

  const completedRequests = k6.load.iterations.completed;
  requireCondition(Number.isSafeInteger(completedRequests) && completedRequests >= 0, 'k6 completed sample count is invalid');
  const observedCalls = measurement.deltas.reduce((total, delta) => total + delta.calls, 0);
  const expectedCalls = completedRequests * metadata.expectedSqlCallsPerRequest;
  if (observedCalls !== expectedCalls) {
    reasons.add('sql-call-attribution-mismatch');
  }

  return {
    schemaVersion: 1,
    metadata,
    validity: {
      status: reasons.size === 0 ? 'valid' : 'invalid',
      reasons: [...reasons].sort(),
    },
    k6: {
      metadata: k6.metadata,
      load: k6.load,
      performance: k6.performance,
      manifestGaps: k6.manifestGaps,
    },
    sql: {
      attribution: {
        completedRequests,
        expectedSqlCallsPerRequest: metadata.expectedSqlCallsPerRequest,
        expectedCalls,
        observedCalls,
      },
      idleDeltas: idle.deltas,
      measurementDeltas: measurement.deltas,
      rankings: {
        totalTimeMs: ranking(measurement.deltas, 'totalTimeMs'),
        timePerCallMs: ranking(measurement.deltas, 'timePerCallMs'),
        rowsExaminedPerCall: ranking(measurement.deltas, 'rowsExaminedPerCall'),
        rowsSentPerCall: ranking(measurement.deltas, 'rowsSentPerCall'),
      },
    },
    prometheus: measurementPrometheus,
  };
}

function readBoundedFile(path) {
  const stat = lstatSync(path);
  requireCondition(stat.isFile() && !stat.isSymbolicLink() && stat.size <= MAX_INPUT_BYTES, 'aggregation input file is missing or unsafe');
  return readFileSync(path, 'utf8');
}

function parseArguments(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    requireCondition(flag?.startsWith('--') && value !== undefined, 'aggregation arguments are invalid');
    requireCondition(options[flag] === undefined, 'aggregation argument is duplicated');
    options[flag] = value;
  }
  const expected = ['--metadata', '--k6', '--prometheus', '--idle-before', '--idle-after', '--before', '--after', '--output'];
  requireCondition(hasExactKeys(options, expected), 'aggregation arguments are invalid');
  return options;
}

function parseIdleArguments(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    requireCondition(flag?.startsWith('--') && value !== undefined, 'idle-control arguments are invalid');
    requireCondition(options[flag] === undefined, 'idle-control argument is duplicated');
    options[flag] = value;
  }
  requireCondition(hasExactKeys(options, ['--before', '--after']), 'idle-control arguments are invalid');
  return options;
}

function writeArtifact(outputPath, artifact) {
  const absolute = resolve(REPO_ROOT, outputPath);
  requireCondition(
    absolute.startsWith(`${ARTIFACT_ROOT}/`)
      && /^build\/k6\/traffic\/[a-z0-9][a-z0-9-]{2,79}-aggregate\.json$/.test(outputPath),
    'aggregation output path is outside the canonical artifact boundary',
  );
  const parent = realpathSync(dirname(absolute));
  requireCondition(parent === ARTIFACT_ROOT, 'aggregation output parent is unsafe');
  const temporary = `${absolute}.partial`;
  let descriptor;
  try {
    descriptor = openSync(temporary, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
    writeFileSync(descriptor, `${JSON.stringify(artifact, null, 2)}\n`, 'utf8');
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
    linkSync(temporary, absolute);
  } finally {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch (_) {
        // Preserve the original publication failure.
      }
    }
    try {
      unlinkSync(temporary);
    } catch (_) {
      // The temporary file may not have been created or may already be absent.
    }
  }
}

function main(argv) {
  if (argv[0] === 'verify-idle') {
    const options = parseIdleArguments(argv.slice(1));
    const result = verifyIdleControl(
      readBoundedFile(options['--before']),
      readBoundedFile(options['--after']),
    );
    requireCondition(result.status === 'valid', 'idle SQL control is not quiescent');
    process.stdout.write('idle_sql=verified\n');
    return;
  }
  const options = parseArguments(argv);
  const input = {
    metadata: JSON.parse(readBoundedFile(options['--metadata'])),
    k6: JSON.parse(readBoundedFile(options['--k6'])),
    prometheus: JSON.parse(readBoundedFile(options['--prometheus'])),
    idleBefore: readBoundedFile(options['--idle-before']),
    idleAfter: readBoundedFile(options['--idle-after']),
    before: readBoundedFile(options['--before']),
    after: readBoundedFile(options['--after']),
  };
  writeArtifact(options['--output'], aggregateTrafficResult(input));
}

if (process.argv[1] && resolve(process.argv[1]) === SCRIPT_PATH) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'traffic aggregation failed');
    process.exitCode = 1;
  }
}

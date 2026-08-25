#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  aggregateTrafficResult,
  verifyIdleControl,
} from '../traffic/aggregate-traffic-results.mjs';

const FLYWAY_VERSION = '27';

function snapshot(rows) {
  return rows.map((row) => JSON.stringify({
    schemaName: 'airbobdb',
    digest: row.digest,
    digestText: row.text,
    count: String(row.count),
    timerWait: String(row.timerWait),
    rowsExamined: String(row.rowsExamined),
    rowsSent: String(row.rowsSent),
  })).join('\n') + '\n';
}

function fixture(overrides = {}) {
  const firstDigest = '1'.repeat(64);
  const secondDigest = '2'.repeat(64);
  const manifestSha = 'a'.repeat(64);
  const appCommit = 'b'.repeat(40);
  const metadata = {
    schemaVersion: 1,
    releaseKind: 'pipeline-rehearsal',
    claimScope: 'pipeline-only',
    runId: 'rehearsal-run',
    datasetRelease: 'rehearsal-v20',
    datasetManifestSha256: 'c'.repeat(64),
    benchmarkManifestSha256: manifestSha,
    appCommit,
    imageDigest: `sha256:${'d'.repeat(64)}`,
    harnessCommit: 'e'.repeat(40),
    flywayVersion: FLYWAY_VERSION,
    appInstanceCount: 1,
    target: 'accommodation-detail',
    expectedSqlCallsPerRequest: 4,
    window: { startEpochMs: 1000, endEpochMs: 11000 },
    postRun: {
      datasetManifestSha256: 'c'.repeat(64),
      benchmarkManifestSha256: manifestSha,
      imageDigest: `sha256:${'d'.repeat(64)}`,
      flywayVersion: FLYWAY_VERSION,
      appInstanceCount: 1,
    },
  };
  const k6 = {
    schemaVersion: 1,
    metadata: {
      releaseKind: 'pipeline-rehearsal',
      claimScope: 'pipeline-only',
      role: 'guest',
      target: 'accommodation-detail',
      datasetVersion: 'nplus1-v1',
      manifestSha256: manifestSha,
      appCommit,
      appInstanceCount: 1,
      round: 1,
      runOrder: 1,
      runLabel: 'guest-detail-measure',
    },
    validity: { status: 'valid', reasons: [] },
    load: {
      configuredRatePerSecond: 1,
      duration: '10s',
      durationSeconds: 10,
      preAllocatedVUs: 20,
      maxVUs: 20,
      iterations: {
        started: 3,
        completed: 3,
        successful: 3,
        minimumRequired: 3,
        dropped: 0,
      },
    },
    performance: {
      errorRate: 0,
      latencyMs: { avg: 15, min: 10, median: 14, p90: 18, p95: 20, p99: 30, max: 31 },
    },
    manifestGaps: ['pipeline-rehearsal'],
  };
  const prometheus = {
    schemaVersion: 1,
    startEpochMs: 1000,
    endEpochMs: 11000,
    queries: {
      requestCount: {
        status: 'success',
        data: {
          resultType: 'matrix',
          result: [{
            metric: { job: 'airbob' },
            values: [[0.5, '9'], [1, '10'], [11, '11'], [12, '12']],
          }],
        },
      },
      queryCount: { status: 'success', data: { resultType: 'matrix', result: [] } },
      hikariPending: { status: 'success', data: { resultType: 'matrix', result: [] } },
    },
  };
  const beforeRows = [
    { digest: firstDigest, text: 'SELECT * FROM accommodation WHERE id = ?', count: 10, timerWait: 1_000_000_000, rowsExamined: 10, rowsSent: 10 },
    { digest: secondDigest, text: 'SELECT * FROM image WHERE accommodation_id = ?', count: 20, timerWait: 2_000_000_000, rowsExamined: 40, rowsSent: 20 },
  ];
  const afterRows = [
    { digest: firstDigest, text: 'SELECT * FROM accommodation WHERE id = ?', count: 13, timerWait: 2_500_000_000, rowsExamined: 13, rowsSent: 13 },
    { digest: secondDigest, text: 'SELECT * FROM image WHERE accommodation_id = ?', count: 29, timerWait: 6_500_000_000, rowsExamined: 85, rowsSent: 29 },
  ];

  return {
    metadata,
    k6,
    prometheus,
    idleBefore: snapshot(beforeRows),
    idleAfter: snapshot(beforeRows),
    before: snapshot(beforeRows),
    after: snapshot(afterRows),
    ...overrides,
  };
}

const valid = aggregateTrafficResult(fixture());
assert.equal(valid.validity.status, 'valid');
assert.deepEqual(valid.validity.reasons, []);
assert.equal(valid.sql.attribution.completedRequests, 3);
assert.equal(valid.sql.attribution.observedCalls, 12);
assert.equal(valid.sql.attribution.expectedCalls, 12);
assert.equal(valid.sql.measurementDeltas.length, 2);
assert.equal(valid.sql.rankings.totalTimeMs[0].digest, '2'.repeat(64));
assert.equal(valid.sql.rankings.timePerCallMs[0].digest, '1'.repeat(64));
assert.equal(valid.sql.rankings.rowsExaminedPerCall[0].digest, '2'.repeat(64));
assert.equal(valid.sql.rankings.rowsSentPerCall[0].digest, '1'.repeat(64));
assert.equal(valid.metadata.releaseKind, 'pipeline-rehearsal');
assert.equal(valid.metadata.claimScope, 'pipeline-only');
assert.deepEqual(
  valid.prometheus.queries.requestCount.data.result[0].values,
  [[1, '10'], [11, '11']],
);
assert.equal(JSON.stringify(valid).includes('password'), false);

const ambientAfter = snapshot([
  { digest: '1'.repeat(64), text: 'SELECT * FROM accommodation WHERE id = ?', count: 11, timerWait: 1_500_000_000, rowsExamined: 11, rowsSent: 11 },
  { digest: '2'.repeat(64), text: 'SELECT * FROM image WHERE accommodation_id = ?', count: 20, timerWait: 2_000_000_000, rowsExamined: 40, rowsSent: 20 },
]);
const idleChanged = aggregateTrafficResult(fixture({ idleAfter: ambientAfter }));
assert.equal(idleChanged.validity.status, 'invalid');
assert(idleChanged.validity.reasons.includes('ambient-sql-delta'));
assert.equal(
  verifyIdleControl(fixture().idleBefore, fixture().idleAfter).status,
  'valid',
);
assert.equal(
  verifyIdleControl(fixture().idleBefore, ambientAfter).status,
  'invalid',
);

const reset = aggregateTrafficResult(fixture({
  after: snapshot([
    { digest: '1'.repeat(64), text: 'SELECT * FROM accommodation WHERE id = ?', count: 9, timerWait: 900_000_000, rowsExamined: 9, rowsSent: 9 },
    { digest: '2'.repeat(64), text: 'SELECT * FROM image WHERE accommodation_id = ?', count: 29, timerWait: 6_500_000_000, rowsExamined: 85, rowsSent: 29 },
  ]),
}));
assert.equal(reset.validity.status, 'invalid');
assert(reset.validity.reasons.includes('sql-counter-reset'));

const evicted = aggregateTrafficResult(fixture({
  after: snapshot([
    { digest: '2'.repeat(64), text: 'SELECT * FROM image WHERE accommodation_id = ?', count: 29, timerWait: 6_500_000_000, rowsExamined: 85, rowsSent: 29 },
  ]),
}));
assert.equal(evicted.validity.status, 'invalid');
assert(evicted.validity.reasons.includes('sql-digest-eviction'));

const drifted = fixture();
drifted.k6.metadata.appInstanceCount = 2;
drifted.prometheus.endEpochMs = 12000;
const drift = aggregateTrafficResult(drifted);
assert.equal(drift.validity.status, 'invalid');
assert(drift.validity.reasons.includes('run-provenance-drift'));
assert(drift.validity.reasons.includes('measurement-window-drift'));

const flywayDrifted = fixture();
flywayDrifted.metadata.postRun.flywayVersion = '16';
const flywayDrift = aggregateTrafficResult(flywayDrifted);
assert.equal(flywayDrift.validity.status, 'invalid');
assert(flywayDrift.validity.reasons.includes('run-provenance-drift'));

assert.throws(
  () => aggregateTrafficResult(fixture({
    metadata: { ...fixture().metadata, flywayVersion: '017' },
  })),
  /traffic aggregation metadata is invalid/,
);
assert.throws(
  () => aggregateTrafficResult(fixture({
    metadata: { ...fixture().metadata, flywayVersion: '16' },
  })),
  /traffic aggregation metadata is invalid/,
);

const unattributable = aggregateTrafficResult(fixture({
  metadata: { ...fixture().metadata, expectedSqlCallsPerRequest: 5 },
}));
assert.equal(unattributable.validity.status, 'invalid');
assert(unattributable.validity.reasons.includes('sql-call-attribution-mismatch'));

assert.throws(
  () => aggregateTrafficResult(fixture({ before: '{"digest":"d1"}\n' })),
  /SQL snapshot row contract is invalid/,
);

console.log('traffic result aggregation tests passed');

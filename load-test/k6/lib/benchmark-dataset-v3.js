// Explicit small growth contract. No legacy v2 label conversion or fixed production budgets.
function requireThat(value, message) { if (!value) throw new Error(`growth v3: ${message}`); }
function exact(value, keys) {
  requireThat(value !== null && typeof value === 'object' && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort()), 'unexpected keys');
}
const SHA = /^[0-9a-f]{64}$/;
export const growthArtifactFiles = {
  dump: 'airbob-growth.sql.gz', migrations: 'migration-files.json', fingerprint: 'before-fingerprint.json',
  reads: 'read-scenarios.json', runtime: 'runtime-plan.json', runtimeEvidence: 'runtime-scenarios.json', scenarioQualification: 'scenario-qualification.json',
};
export function parseGrowthManifest(raw, expectedId, expectedMysql) {
  const m = JSON.parse(raw);
  exact(m, ['schemaVersion', 'datasetVersion', 'datasetId', 'qualification', 'mysql', 'time', 'artifacts', 'capabilities']);
  requireThat(m.schemaVersion === 3 && m.datasetVersion === 'benchmark-dataset-v3', 'unsupported version');
  requireThat(/^korea-growth-v3-[0-9a-f]{16}$/.test(expectedId) && m.datasetId === expectedId, 'dataset identity mismatch');
  requireThat(m.qualification === 'SMALL_LOCAL_SCENARIOS', 'unsupported qualification');
  exact(m.mysql, ['version', 'flywayVersion']);
  requireThat(/^8\.4\.\d+$/.test(expectedMysql) && m.mysql.version === expectedMysql && m.mysql.flywayVersion === 27, 'engine/migration mismatch');
  exact(m.time, ['snapshotAsOf', 'activityCutoffExclusive', 'timezone']);
  requireThat(m.time.timezone === 'Asia/Seoul' && m.time.snapshotAsOf.endsWith('+09:00')
    && m.time.activityCutoffExclusive.endsWith('+09:00')
    && Date.parse(m.time.activityCutoffExclusive) < Date.parse(m.time.snapshotAsOf), 'invalid historical time');
  exact(m.capabilities, ['immutableReads', 'runtimePreparation', 'searchSnapshot', 'awsExecutionValidated']);
  requireThat(m.capabilities.immutableReads === true && m.capabilities.runtimePreparation === true
    && m.capabilities.searchSnapshot === false && m.capabilities.awsExecutionValidated === false, 'unsupported capabilities');
  exact(m.artifacts, Object.keys(growthArtifactFiles));
  for (const [key, file] of Object.entries(growthArtifactFiles)) {
    exact(m.artifacts[key], ['file', 'sha256']);
    requireThat(m.artifacts[key].file === file && SHA.test(m.artifacts[key].sha256), 'invalid artifact reference');
  }
  requireThat(m.datasetId === `korea-growth-v3-${m.artifacts.dump.sha256.slice(0, 16)}`, 'dump identity mismatch');
  return m;
}
export function parseGrowthReadTargets(raw) {
  const bundle = JSON.parse(raw); exact(bundle, ['schemaVersion', 'targets']);
  requireThat(bundle.schemaVersion === 1 && Array.isArray(bundle.targets) && bundle.targets.length > 0, 'no targets');
  const seen = new Set();
  for (const t of bundle.targets) {
    exact(t, ['id', 'method', 'path', 'memberId', 'account', 'expectedStatus', 'expectedResponseSha256',
      'arrayField', 'idField', 'expectedIds', 'immutable', 'preparation']);
    requireThat(/^[a-z0-9_-]+$/.test(t.id) && !seen.has(t.id), 'invalid/duplicate target ID'); seen.add(t.id);
    requireThat(t.method === 'GET' && t.expectedStatus === 200 && t.immutable === true && t.preparation === 'none', 'unsupported read');
    requireThat(/^\/api\/v1\/[A-Za-z0-9/_?=&%+.-]+$/.test(t.path) && !t.path.includes('..')
      && !t.path.toLowerCase().includes('%2e'), 'unsafe target path');
    requireThat(SHA.test(t.expectedResponseSha256), 'missing response hash');
    if (t.memberId === null) requireThat(t.account === null, 'anonymous account mismatch');
    else {
      exact(t.account, ['memberId', 'email', 'role']);
      requireThat(Number.isSafeInteger(t.memberId) && t.memberId > 0 && t.account.memberId === t.memberId, 'account mismatch');
      requireThat(['MEMBER', 'ADMIN'].includes(t.account.role)
        && /^growth-(?:\d+|boundary-(?:host|admin|guest-\d+))@example\.test$/.test(t.account.email), 'non-synthetic account');
    }
    requireThat(Array.isArray(t.expectedIds) && t.expectedIds.length <= 50, 'invalid result size');
    if (t.arrayField === null) requireThat(t.idField === null && t.expectedIds.length === 0, 'invalid singleton');
    else requireThat(['reservations', 'reviews', 'accommodations', 'wishlists', 'wishlist_accommodations'].includes(t.arrayField)
      && ['id', 'reservation_id', 'reservation_uid', 'wishlist_accommodation_id'].includes(t.idField), 'invalid result mapping');
  }
  return bundle.targets;
}
export function canonicalGrowthResponse(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalGrowthResponse).join(',')}]`;
  if (value !== null && typeof value === 'object') return `{${Object.keys(value).sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalGrowthResponse(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}

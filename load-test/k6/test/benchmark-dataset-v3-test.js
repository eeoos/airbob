import { check } from 'k6';
import { parseGrowthManifest, parseGrowthReadTargets } from '../lib/benchmark-dataset-v3.js';
const manifest = JSON.parse(open(`${__ENV.GROWTH_RELEASE_DIR}/consumer-manifest.json`));
const targets = JSON.parse(open(`${__ENV.GROWTH_RELEASE_DIR}/read-scenarios.json`));
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
function rejects(fn) { try { fn(); return false; } catch (_) { return true; } }
function changed(value, mutate) { const copy = JSON.parse(JSON.stringify(value)); mutate(copy); return JSON.stringify(copy); }
export default function () {
  const parse = (raw) => parseGrowthManifest(raw, manifest.datasetId, '8.4.11');
  check(null, {
    'accepts actual v3 producer manifest': () => parse(JSON.stringify(manifest)).schemaVersion === 3,
    'accepts actual producer read targets': () => parseGrowthReadTargets(JSON.stringify(targets)).length > 0,
    'rejects v2 relabelling': () => rejects(() => parse(changed(manifest, (x) => { x.datasetVersion = 'benchmark-dataset-v2'; }))),
    'rejects wrong schema version': () => rejects(() => parse(changed(manifest, (x) => { x.schemaVersion = 4; }))),
    'rejects MySQL 8.0': () => rejects(() => parse(changed(manifest, (x) => { x.mysql.version = '8.0.33'; }))),
    'rejects migration drift': () => rejects(() => parse(changed(manifest, (x) => { x.mysql.flywayVersion = 28; }))),
    'rejects identity drift': () => rejects(() => parseGrowthManifest(JSON.stringify(manifest), 'korea-growth-v3-0000000000000000', '8.4.11')),
    'rejects artifact path traversal': () => rejects(() => parse(changed(manifest, (x) => { x.artifacts.dump.file = '../dump.gz'; }))),
    'rejects false AWS validation claim': () => rejects(() => parse(changed(manifest, (x) => { x.capabilities.awsExecutionValidated = true; }))),
    'rejects duplicate target IDs': () => rejects(() => parseGrowthReadTargets(changed(targets, (x) => { x.targets.push(x.targets[0]); }))),
    'rejects external target URLs': () => rejects(() => parseGrowthReadTargets(changed(targets, (x) => { x.targets[0].path = 'https://example.test'; }))),
    'rejects account mismatch': () => rejects(() => parseGrowthReadTargets(changed(targets, (x) => { const t = x.targets.find((v) => v.account); t.account.memberId += 1; }))),
  });
}

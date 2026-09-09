import http from 'k6/http';
import crypto from 'k6/crypto';
import execution from 'k6/execution';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { parseGrowthManifest, parseGrowthReadTargets, canonicalGrowthResponse } from '../lib/benchmark-dataset-v3.js';

const dir = __ENV.GROWTH_RELEASE_DIR;
if (!dir || !__ENV.BASE_URL || !__ENV.EXPECTED_DATASET_ID || !__ENV.EXPECTED_MYSQL_VERSION) fail('Growth release, URL and expected identity/engine are required');
const manifest = parseGrowthManifest(open(`${dir}/consumer-manifest.json`), __ENV.EXPECTED_DATASET_ID, __ENV.EXPECTED_MYSQL_VERSION);
const targetRaw = open(`${dir}/${manifest.artifacts.reads.file}`);
if (crypto.sha256(targetRaw, 'hex') !== manifest.artifacts.reads.sha256) fail('Growth read target checksum mismatch');
const targets = parseGrowthReadTargets(targetRaw);
const completed = new Counter('growth_targets_completed');
export const options = {
  scenarios: { qualification: { executor: 'shared-iterations', vus: 1, iterations: targets.length, maxDuration: '2m' } },
  thresholds: { checks: ['rate==1'], growth_targets_completed: [`count==${targets.length}`] },
};
export function setup() {
  const sessions = {};
  for (const target of targets) {
    if (!target.account || sessions[target.memberId]) continue;
    if (!__ENV.AIRBOB_ETL_BENCHMARK_PASSWORD) fail('Runtime benchmark password is required');
    const response = http.post(`${__ENV.BASE_URL}/api/v1/auth/login`, JSON.stringify({
      email: target.account.email, password: __ENV.AIRBOB_ETL_BENCHMARK_PASSWORD,
    }), { headers: { 'Content-Type': 'application/json' }, tags: { operation: 'prepare-session' } });
    if (response.status !== 200 || !response.cookies.SESSION_ID?.length) fail('Growth session preparation failed');
    sessions[target.memberId] = `SESSION_ID=${response.cookies.SESSION_ID[0].value}`;
  }
  return sessions;
}
export default function (sessions) {
  const target = targets[execution.scenario.iterationInTest];
  const headers = target.memberId === null ? {} : { Cookie: sessions[target.memberId] };
  // Cookie state is supplied per target; an anonymous request must not inherit the preceding member.
  http.cookieJar().clear(__ENV.BASE_URL);
  const response = http.get(`${__ENV.BASE_URL}${target.path}`, { headers, tags: { target: target.id } });
  let body;
  try { body = response.json(); } catch (_) { fail(`Non-JSON response for ${target.id}`); }
  const valid = check(response, {
    'expected HTTP status': (r) => r.status === target.expectedStatus,
    'expected semantic response': () => crypto.sha256(canonicalGrowthResponse(body), 'hex') === target.expectedResponseSha256,
    'expected ordered IDs': () => target.arrayField === null || JSON.stringify(body.data[target.arrayField].map((v) => v[target.idField])) === JSON.stringify(target.expectedIds),
  });
  if (!valid) fail(`Growth read target failed: ${target.id}`);
  completed.add(1);
}

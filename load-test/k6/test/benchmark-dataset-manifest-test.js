import { check } from 'k6';
import { Counter } from 'k6/metrics';

import {
  findCapsuleTarget,
  findExperimentCapsule,
  parseBenchmarkDatasetManifest,
  requireAccountCapacity,
} from '../lib/benchmark-dataset-manifest.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    contract_test_completed: ['count==1'],
  },
};

const contractTestCompleted = new Counter('contract_test_completed');

const canonicalRaw = open('../../../infra/aws/tests/fixtures/benchmark-dataset-v2.json');
const malformedCases = JSON.parse(open('./fixtures/benchmark-dataset-v2-malformed.json'));

function rejects(fn) {
  try {
    fn();
    return false;
  } catch (_) {
    return true;
  }
}

function applyMalformedCase(source, malformedCase) {
  const copy = JSON.parse(JSON.stringify(source));
  let parent = copy;
  const path = malformedCase.path;
  for (let index = 0; index < path.length - 1; index += 1) {
    parent = parent[path[index]];
  }
  const key = path[path.length - 1];
  if (malformedCase.op === 'delete') {
    delete parent[key];
  } else if (malformedCase.op === 'set') {
    parent[key] = malformedCase.value;
  } else {
    throw new Error(`unsupported malformed fixture operation ${malformedCase.op}`);
  }
  return copy;
}

function asUnverifiedNormalWorld(source) {
  const copy = JSON.parse(JSON.stringify(source));
  copy.world.provenance.verificationPassed = false;
  copy.world.provenance.sourceInventorySha256 = null;
  copy.world.provenance.calibrationVersion = null;
  copy.world.provenance.calibrationSha256 = null;
  copy.world.provenance.assertionSha256 = null;
  copy.world.scopedObservedDistributions = {};
  copy.world.scopeRanges = {};
  copy.world.fingerprints = {};
  return copy;
}

export default function () {
  const canonicalSource = JSON.parse(canonicalRaw);
  const manifest = parseBenchmarkDatasetManifest(canonicalRaw);
  const coupon = findExperimentCapsule(manifest, 'coupon-accounts-v1');
  const readModel = findExperimentCapsule(manifest, 'read-model-v2');

  check(manifest, {
    'canonical v2 manifest is accepted': (value) => (
      value.schemaVersion === 2
        && value.datasetVersion === 'benchmark-dataset-v2'
        && value.world.version === 'world-v2'
    ),
    'closed unverified normal world is accepted': () => (
      parseBenchmarkDatasetManifest(JSON.stringify(asUnverifiedNormalWorld(canonicalSource)))
        .world.provenance.verificationPassed === false
    ),
    'coupon capacity can be required without changing legacy targets': () => (
      requireAccountCapacity(coupon, 2) === 2 && coupon.targets.length === 0
    ),
    'manifest-bound wishlist deep target preserves cursor and direct account': () => {
      const target = findCapsuleTarget(readModel, 'wishlist-hot-deep');
      return target.query.lastId === 999
        && target.query.memberId === target.account.memberId
        && target.account.role === 'MEMBER';
    },
    'manifest-bound revenue target preserves UTC range and admin account': () => {
      const target = findCapsuleTarget(readModel, 'revenue-recent-7d');
      return target.query.dayBoundary === 'UTC' && target.account.role === 'ADMIN';
    },
    'all shared malformed corpus entries are rejected': () => malformedCases.every((entry) => (
      rejects(() => parseBenchmarkDatasetManifest(JSON.stringify(
        applyMalformedCase(canonicalSource, entry),
      )))
    )),
    'insufficient account capacity is rejected': () => rejects(() => requireAccountCapacity(coupon, 3)),
    'unknown capsule is rejected': () => rejects(() => findExperimentCapsule(manifest, 'missing-v1')),
    'malformed JSON is sanitized': () => rejects(() => parseBenchmarkDatasetManifest('{')),
  });
  contractTestCompleted.add(1);
}

#!/usr/bin/env node

import {
  closeSync,
  constants,
  fstatSync,
  fsyncSync,
  linkSync,
  lstatSync,
  openSync,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOURCE_SCHEMA_VERSION = 'read-model-evidence-v1';
const OBSERVATION_SCHEMA_VERSION = 'read-model-observations-v1';
const SCRIPT_PATH = fileURLToPath(import.meta.url);
const REPO_ROOT = resolve(dirname(SCRIPT_PATH), '../../..');
const ARTIFACT_ROOT = resolve(REPO_ROOT, 'build/k6/read-model');
const MAX_SOURCE_BYTES = 1024 * 1024;
const MAX_TOTAL_SOURCE_BYTES = 8 * 1024 * 1024;
const MAX_SOURCE_COUNT = 64;
const MAX_SCAN_DEPTH = 64;
const MAX_SCAN_NODES = 100_000;
const MAX_RAW_EXPLAIN_BYTES = 512 * 1024;
const SHA256 = /^[0-9a-f]{64}$/;
const SLUG = /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/;
const MYSQL_IDENTIFIER = /^[a-zA-Z_][a-zA-Z0-9_$]{0,63}$/;

const SOURCE_KEYS = [
  'schema_version',
  'metadata',
  'validity',
  'parity',
  'performance',
  'measurement_fencing_token_sha256',
  'runtime_assertion',
  'mysql_evidence',
];
const METADATA_KEYS = [
  'generated_at',
  'run_id',
  'design',
  'experiment_id',
  'block_id',
  'window_id',
  'statement_event_id',
  'domain',
  'target_class',
  'variant',
  'pair_role',
  'phase',
  'release_tuple',
  'manifest_target',
  'app_build',
  'database',
  'treatment',
];
const RELEASE_TUPLE_KEYS = [
  'release_id',
  'dataset_version',
  'world_version',
  'source_calibration_sha256',
  'production_skew_spec_sha256',
  'dataset_manifest_sha256',
  'dump_sha256',
  'schema_migration_sha256',
  'target_fingerprint_sha256',
];
const MANIFEST_TARGET_KEYS = [
  'capsule_id',
  'target_id',
  'query_kind',
  'parameter_hash_sha256',
  'expected_rows',
  'expected_result_hash',
  'account_ref',
];
const APP_BUILD_KEYS = [
  'commit_sha', 'image_digest', 'build_id', 'instance_count',
  'runtime_revision', 'app_instance_id', 'resource_fencing_token_sha256',
];
const DATABASE_KEYS = [
  'clone_id',
  'pre_fingerprint_sha256',
  'post_fingerprint_sha256',
  'optimizer_snapshot_sha256',
  'statistics_snapshot_sha256',
  'histogram_snapshot_sha256',
  'analyze_receipt_sha256',
  'mysql_version',
  'auto_statistics_recalculation_detected',
];
const TREATMENT_KEYS = [
  'kind',
  'candidate_index',
  'candidate_visible',
  'optimizer_switch_use_invisible_indexes',
];
const DESIGN_ROLES = Object.freeze({
  READ_MODEL_AB: Object.freeze({ BEFORE: 'before', AFTER: 'after' }),
  AA_NOISE: Object.freeze({ AA_A: null, AA_B: null }),
  INVISIBLE_INDEX_AB: Object.freeze({ INDEX_BASELINE: null, INDEX_CANDIDATE: null }),
});
const ROLE_ORDER = Object.freeze({
  BEFORE: 0,
  AFTER: 1,
  AA_A: 0,
  AA_B: 1,
  INDEX_BASELINE: 0,
  INDEX_CANDIDATE: 1,
});
const EXCLUDED_PHASES = ['setup', 'login', 'analyze', 'explain'];
const METRICS = ['p50', 'p95', 'p99'];

function sensitiveEnvironmentKey(key) {
  const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  return [
    'token',
    'secret',
    'password',
    'authorization',
    'cookie',
    'sessionid',
    'credential',
    'apikey',
    'accesskey',
    'privatekey',
    'email',
  ].some((fragment) => normalized.includes(fragment));
}

const KNOWN_SECRET_VALUES = [...new Set(Object.entries(process.env)
  .filter(([key, value]) => (
    sensitiveEnvironmentKey(key)
      && typeof value === 'string'
      && value.length >= 6
  ))
  .map(([, value]) => value))]
  .sort((left, right) => right.length - left.length);

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  if (!isObject(value)) {
    return false;
  }
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  return actual.length === expected.length
    && actual.every((key, index) => key === expected[index]);
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function isFiniteNonNegative(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function isSlug(value, maximumLength = 128) {
  return typeof value === 'string'
    && value.length <= maximumLength
    && SLUG.test(value);
}

function isSha256(value) {
  return typeof value === 'string' && SHA256.test(value);
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  if (isObject(value)) {
    return `{${Object.keys(value).sort().map(
      (key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`,
    ).join(',')}}`;
  }
  return JSON.stringify(value);
}

function boundedContains(value, { keyPredicate = () => false, stringPredicate = () => false }) {
  const stack = [{ value, depth: 0 }];
  let visited = 0;
  while (stack.length > 0) {
    const current = stack.pop();
    visited += 1;
    requireCondition(
      visited <= MAX_SCAN_NODES && current.depth <= MAX_SCAN_DEPTH,
      'source artifact exceeds security scan limits',
    );
    if (Array.isArray(current.value)) {
      requireCondition(
        visited + stack.length + current.value.length <= MAX_SCAN_NODES
          && (current.value.length === 0 || current.depth < MAX_SCAN_DEPTH),
        'source artifact exceeds security scan limits',
      );
      for (let index = current.value.length - 1; index >= 0; index -= 1) {
        stack.push({ value: current.value[index], depth: current.depth + 1 });
      }
      continue;
    }
    if (isObject(current.value)) {
      const entries = Object.entries(current.value);
      requireCondition(
        visited + stack.length + entries.length <= MAX_SCAN_NODES
          && (entries.length === 0 || current.depth < MAX_SCAN_DEPTH),
        'source artifact exceeds security scan limits',
      );
      for (let index = entries.length - 1; index >= 0; index -= 1) {
        const [key, item] = entries[index];
        if (keyPredicate(key)) {
          return true;
        }
        stack.push({ value: item, depth: current.depth + 1 });
      }
      continue;
    }
    if (typeof current.value === 'string' && stringPredicate(current.value)) {
      return true;
    }
  }
  return false;
}

function sensitiveDataKey(key) {
  const normalized = key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
  if (normalized.endsWith('sha256')) {
    return false;
  }
  return [
    'token',
    'secret',
    'password',
    'authorization',
    'cookie',
    'sessionid',
    'email',
    'credential',
    'firstname',
    'lastname',
  ].some((fragment) => normalized.includes(fragment));
}

function containsSensitiveData(value) {
  return boundedContains(value, {
    keyPredicate: sensitiveDataKey,
    stringPredicate: (candidate) => (
      /[^\s@]+@[^\s@]+\.[^\s@]+/.test(candidate)
        || /(?:bearer\s+|\bpassword\b\s*[:=]|\bsecret\b\s*[:=]|authorization\s*[:=]|cookie\s*[:=]|session[_ -]?id\s*[:=])/i
          .test(candidate)
        || /\b(?:payment_key|order_id|virtual_account_number|virtual_customer_name|client_ip)\b\s*[:=]/i
          .test(candidate)
    ),
  });
}

function containsKnownSecret(value) {
  return boundedContains(value, {
    stringPredicate: (candidate) => KNOWN_SECRET_VALUES.some(
      (secret) => candidate.includes(secret),
    ),
  });
}

export function parseEngineeringNumber(raw) {
  requireCondition(typeof raw === 'string', 'engineering number is invalid');
  const match = raw.trim().match(
    /^\+?(?:(?:\d+(?:\.\d*)?)|(?:\.\d+))(?:[eE][+-]?\d+)?([kKMGT])?$/,
  );
  requireCondition(match !== null, 'engineering number is invalid');
  const suffix = match[1] || '';
  const numeric = Number(suffix ? raw.trim().slice(0, -1) : raw.trim());
  const multiplier = {
    '': 1,
    k: 1_000,
    K: 1_000,
    M: 1_000_000,
    G: 1_000_000_000,
    T: 1_000_000_000_000,
  }[suffix];
  const value = numeric * multiplier;
  requireCondition(Number.isFinite(value) && value >= 0, 'engineering number is invalid');
  return value;
}

const ENGINEERING_TOKEN = String.raw`\+?(?:(?:\d+(?:\.\d*)?)|(?:\.\d+))(?:[eE][+-]?\d+)?[kKMGT]?`;

export function parseExplainAnalyzeTree(rawTree) {
  requireCondition(
    typeof rawTree === 'string'
      && rawTree.length > 0
      && Buffer.byteLength(rawTree, 'utf8') <= MAX_RAW_EXPLAIN_BYTES
      && !rawTree.includes('\0'),
    'EXPLAIN ANALYZE TREE is invalid',
  );
  const expression = new RegExp(
    `actual\\s+time\\s*=\\s*(${ENGINEERING_TOKEN})\\s*(?:ms)?\\s*\\.\\.\\s*(${ENGINEERING_TOKEN})\\s*(?:ms)?\\s+rows\\s*=\\s*(${ENGINEERING_TOKEN})\\s+loops\\s*=\\s*(${ENGINEERING_TOKEN})`,
    'gi',
  );
  const iterators = [];
  let match;
  while ((match = expression.exec(rawTree)) !== null) {
    iterators.push({
      first_row_ms: parseEngineeringNumber(match[1]),
      last_row_ms: parseEngineeringNumber(match[2]),
      actual_rows: parseEngineeringNumber(match[3]),
      loops: parseEngineeringNumber(match[4]),
    });
  }
  requireCondition(iterators.length > 0, 'EXPLAIN ANALYZE TREE has no actual iterator metrics');
  iterators.forEach((iterator) => {
    requireCondition(
      iterator.last_row_ms >= iterator.first_row_ms
        && Number.isSafeInteger(iterator.loops)
        && iterator.loops >= 0,
      'EXPLAIN ANALYZE TREE iterator metrics are invalid',
    );
  });
  return { root: iterators[0], iterators };
}

function parseExplainJson(rawJson) {
  requireCondition(
    typeof rawJson === 'string'
      && rawJson.length > 0
      && Buffer.byteLength(rawJson, 'utf8') <= MAX_RAW_EXPLAIN_BYTES
      && !rawJson.includes('\0'),
    'EXPLAIN JSON is invalid',
  );
  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (_) {
    throw new Error('EXPLAIN JSON is invalid');
  }
  requireCondition(isObject(parsed), 'EXPLAIN JSON is invalid');
  return parsed;
}

export function chosenPlanUsesCandidate(rawJson, candidateIndex) {
  requireCondition(MYSQL_IDENTIFIER.test(candidateIndex || ''), 'candidate index is invalid');
  const parsed = parseExplainJson(rawJson);
  const chosenIndexKeys = new Set([
    'key',
    'index',
    'index_name',
    'indexName',
    'chosen_index',
    'used_index',
  ]);
  const stack = [{ value: parsed, depth: 0 }];
  let visited = 0;
  while (stack.length > 0) {
    const current = stack.pop();
    visited += 1;
    requireCondition(
      visited <= MAX_SCAN_NODES && current.depth <= MAX_SCAN_DEPTH,
      'EXPLAIN JSON exceeds parser limits',
    );
    if (Array.isArray(current.value)) {
      current.value.forEach((item) => stack.push({ value: item, depth: current.depth + 1 }));
      continue;
    }
    if (!isObject(current.value)) {
      continue;
    }
    for (const [key, value] of Object.entries(current.value)) {
      if (chosenIndexKeys.has(key) && value === candidateIndex) {
        return true;
      }
      if (Array.isArray(value) || isObject(value)) {
        stack.push({ value, depth: current.depth + 1 });
      }
    }
  }
  return false;
}

function validateReleaseTuple(releaseTuple) {
  requireCondition(
    hasExactKeys(releaseTuple, RELEASE_TUPLE_KEYS),
    'release tuple contract is invalid',
  );
  requireCondition(
    isSlug(releaseTuple.release_id, 128)
      && releaseTuple.dataset_version === 'benchmark-dataset-v2'
      && releaseTuple.world_version === 'world-v2'
      && RELEASE_TUPLE_KEYS.filter((key) => key.endsWith('_sha256'))
        .every((key) => isSha256(releaseTuple[key])),
    'release tuple contract is invalid',
  );
}

function validateManifestTarget(target, domain) {
  requireCondition(
    hasExactKeys(target, MANIFEST_TARGET_KEYS),
    'manifest target contract is invalid',
  );
  requireCondition(
    isSlug(target.capsule_id)
      && isSlug(target.target_id)
      && typeof target.query_kind === 'string'
      && /^[A-Z][A-Z0-9_]{2,63}$/.test(target.query_kind)
      && isSha256(target.parameter_hash_sha256)
      && isNonNegativeInteger(target.expected_rows)
      && isSha256(target.expected_result_hash)
      && (target.account_ref === null || isSlug(target.account_ref)),
    'manifest target contract is invalid',
  );
  if (domain === 'wishlist') {
    requireCondition(target.account_ref !== null, 'wishlist manifest target account reference is required');
  }
}

function validateAppBuild(appBuild) {
  requireCondition(hasExactKeys(appBuild, APP_BUILD_KEYS), 'app build contract is invalid');
  requireCondition(
    typeof appBuild.commit_sha === 'string'
      && /^[0-9a-f]{40}$/.test(appBuild.commit_sha)
      && typeof appBuild.image_digest === 'string'
      && /^sha256:[0-9a-f]{64}$/.test(appBuild.image_digest)
      && isSlug(appBuild.build_id)
      && appBuild.instance_count === 1
      && isSha256(appBuild.runtime_revision)
      && isSha256(appBuild.resource_fencing_token_sha256)
      && /^i-[0-9a-f]{8,17}$/.test(appBuild.app_instance_id || ''),
    'app build contract is invalid',
  );
}

function validateDatabase(database) {
  requireCondition(hasExactKeys(database, DATABASE_KEYS), 'database evidence contract is invalid');
  requireCondition(
    isSlug(database.clone_id)
      && DATABASE_KEYS.filter((key) => key.endsWith('_sha256'))
        .every((key) => isSha256(database[key]))
      && typeof database.mysql_version === 'string'
      && /^8\.0\.[0-9]+(?:[-+][a-zA-Z0-9._-]+)?$/.test(database.mysql_version)
      && database.auto_statistics_recalculation_detected === false,
    'database evidence contract is invalid',
  );
  requireCondition(
    database.pre_fingerprint_sha256 === database.post_fingerprint_sha256,
    'database fingerprint drift invalidates the source',
  );
}

function validateTreatment(treatment, design, pairRole) {
  requireCondition(hasExactKeys(treatment, TREATMENT_KEYS), 'treatment contract is invalid');
  if (design !== 'INVISIBLE_INDEX_AB') {
    requireCondition(
      treatment.kind === 'READ_MODEL'
        && treatment.candidate_index === null
        && treatment.candidate_visible === null
        && treatment.optimizer_switch_use_invisible_indexes === false,
      'read-model and index treatments must remain separate',
    );
    return;
  }
  requireCondition(
    treatment.kind === 'INVISIBLE_INDEX'
      && MYSQL_IDENTIFIER.test(treatment.candidate_index || '')
      && treatment.candidate_visible === false,
    'invisible candidate treatment is invalid',
  );
  const expectedSwitch = pairRole === 'INDEX_CANDIDATE';
  requireCondition(
    treatment.optimizer_switch_use_invisible_indexes === expectedSwitch,
    'invisible candidate optimizer switch is invalid',
  );
}

function validateValidity(validity) {
  requireCondition(
    hasExactKeys(validity, ['status', 'reasons', 'errors', 'dropped_iterations'])
      && validity.status === 'valid'
      && Array.isArray(validity.reasons)
      && validity.reasons.length === 0,
    'source validity is not valid',
  );
  requireCondition(
    validity.errors === 0,
    'source must be error-free',
  );
  requireCondition(
    validity.dropped_iterations === 0,
    'source contains dropped iterations',
  );
}

function validateParity(parity, target) {
  requireCondition(
    hasExactKeys(parity, [
      'verified',
      'expected_rows',
      'observed_rows',
      'expected_result_hash',
      'before_result_hash',
      'after_result_hash',
    ]),
    'parity contract is invalid',
  );
  requireCondition(
    parity.verified === true
      && parity.expected_rows === target.expected_rows
      && parity.observed_rows === target.expected_rows
      && parity.expected_result_hash === target.expected_result_hash
      && parity.before_result_hash === target.expected_result_hash
      && parity.after_result_hash === target.expected_result_hash,
    'business response parity is invalid',
  );
}

function validatePerformance(performance, validity) {
  requireCondition(
    hasExactKeys(performance, [
      'headline_scope',
      'excluded_phases',
      'requests',
      'latency_ms',
    ]),
    'performance contract is invalid',
  );
  requireCondition(
    performance.headline_scope === 'measure-only'
      && Array.isArray(performance.excluded_phases)
      && canonicalJson(performance.excluded_phases) === canonicalJson(EXCLUDED_PHASES),
    'setup, login, ANALYZE, and EXPLAIN must remain outside headline latency',
  );
  const requests = performance.requests;
  requireCondition(
    hasExactKeys(requests, ['attempted', 'successful', 'failed', 'dropped_iterations'])
      && isPositiveInteger(requests.attempted)
      && requests.successful === requests.attempted
      && requests.failed === 0
      && requests.dropped_iterations === 0
      && validity.errors === requests.failed
      && validity.dropped_iterations === requests.dropped_iterations,
    requests.dropped_iterations === 0
      ? 'measurement must be error-free'
      : 'measurement contains dropped iterations',
  );
  const latency = performance.latency_ms;
  requireCondition(
    hasExactKeys(latency, ['count', 'min', 'p50', 'p95', 'p99', 'max'])
      && latency.count === requests.successful
      && [latency.min, latency.p50, latency.p95, latency.p99, latency.max]
        .every(isFiniteNonNegative)
      && latency.p50 > 0
      && latency.min <= latency.p50
      && latency.p50 <= latency.p95
      && latency.p95 <= latency.p99
      && latency.p99 <= latency.max,
    'latency contract is invalid',
  );
}

function validateDecimalCounter(value) {
  return typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value);
}

function validateMysqlEvidence(evidence, metadata) {
  requireCondition(
    hasExactKeys(evidence, ['statement_event', 'optimizer_state', 'explain']),
    'MySQL evidence contract is invalid',
  );
  const statement = evidence.statement_event;
  requireCondition(
    hasExactKeys(statement, [
      'window_id',
      'event_id',
      'digest',
      'digest_text',
      'delta',
    ])
      && statement.window_id === metadata.window_id
      && statement.event_id === metadata.statement_event_id
      && isSha256(statement.digest)
      && typeof statement.digest_text === 'string'
      && statement.digest_text.length > 0
      && statement.digest_text.length <= 8192,
    'statement event contract is invalid',
  );
  requireCondition(
    hasExactKeys(statement.delta, [
      'calls',
      'timer_wait_ps',
      'rows_examined',
      'rows_sent',
      'errors',
    ])
      && isPositiveInteger(statement.delta.calls)
      && validateDecimalCounter(statement.delta.timer_wait_ps)
      && validateDecimalCounter(statement.delta.rows_examined)
      && validateDecimalCounter(statement.delta.rows_sent)
      && statement.delta.errors === 0,
    'statement digest delta contract is invalid',
  );
  const optimizer = evidence.optimizer_state;
  requireCondition(
    hasExactKeys(optimizer, [
      'snapshot_sha256',
      'statistics_snapshot_sha256',
      'histogram_snapshot_sha256',
      'analyze_receipt_sha256',
    ])
      && optimizer.snapshot_sha256 === metadata.database.optimizer_snapshot_sha256
      && optimizer.statistics_snapshot_sha256 === metadata.database.statistics_snapshot_sha256
      && optimizer.histogram_snapshot_sha256 === metadata.database.histogram_snapshot_sha256
      && optimizer.analyze_receipt_sha256 === metadata.database.analyze_receipt_sha256,
    'optimizer evidence does not match the database snapshot',
  );
  const explain = evidence.explain;
  requireCondition(
    hasExactKeys(explain, ['json_raw', 'tree_raw', 'candidate_in_chosen_plan'])
      && typeof explain.candidate_in_chosen_plan === 'boolean',
    'EXPLAIN evidence contract is invalid',
  );
  parseExplainJson(explain.json_raw);
  parseExplainAnalyzeTree(explain.tree_raw);
  const candidate = metadata.treatment.candidate_index;
  if (candidate === null) {
    requireCondition(
      explain.candidate_in_chosen_plan === false,
      'read-model evidence cannot claim an index candidate',
    );
  } else {
    requireCondition(
      chosenPlanUsesCandidate(explain.json_raw, candidate)
        === explain.candidate_in_chosen_plan,
      'candidate chosen-plan claim does not match structured EXPLAIN JSON',
    );
  }
}

function validateMetadata(metadata) {
  requireCondition(hasExactKeys(metadata, METADATA_KEYS), 'source metadata contract is invalid');
  requireCondition(
    typeof metadata.generated_at === 'string'
      && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(metadata.generated_at)
      && !Number.isNaN(Date.parse(metadata.generated_at))
      && Object.hasOwn(DESIGN_ROLES, metadata.design)
      && isSlug(metadata.run_id)
      && isSlug(metadata.experiment_id)
      && isSlug(metadata.block_id)
      && isSlug(metadata.window_id)
      && isSlug(metadata.statement_event_id)
      && ['review', 'wishlist', 'revenue'].includes(metadata.domain)
      && isSlug(metadata.target_class)
      && ['before', 'after'].includes(metadata.variant)
      && Object.hasOwn(DESIGN_ROLES[metadata.design], metadata.pair_role)
      && metadata.phase === 'measure',
    'source metadata contract is invalid',
  );
  const expectedVariant = DESIGN_ROLES[metadata.design][metadata.pair_role];
  if (expectedVariant !== null) {
    requireCondition(metadata.variant === expectedVariant, 'source pair role and variant do not match');
  }
  validateReleaseTuple(metadata.release_tuple);
  validateManifestTarget(metadata.manifest_target, metadata.domain);
  validateAppBuild(metadata.app_build);
  validateDatabase(metadata.database);
  validateTreatment(metadata.treatment, metadata.design, metadata.pair_role);
}

function validateRuntimeAssertion(assertion, appBuild, runId) {
  requireCondition(hasExactKeys(assertion, [
    'runtime_assertion_pre_sha256',
    'runtime_assertion_post_sha256',
    'pre',
    'post',
  ]), 'runtime assertion contract is invalid');
  requireCondition(
    isSha256(assertion.runtime_assertion_pre_sha256)
      && isSha256(assertion.runtime_assertion_post_sha256),
    'runtime assertion digest contract is invalid',
  );
  for (const receipt of [assertion.pre, assertion.post]) {
    requireCondition(hasExactKeys(receipt, [
      'schema_version', 'run_id', 'resource_fencing_token_sha256', 'challenge_sha256',
      'runtime_revision', 'app_instance_id', 'active_profiles',
      'scheduler_enabled', 'kafka_listener_enabled', 'inventory_lifecycle_enabled',
      'external_side_effects_enabled',
    ]), 'runtime assertion receipt contract is invalid');
    requireCondition(
      receipt.schema_version === 1
        && receipt.run_id === runId
        && isSha256(receipt.resource_fencing_token_sha256)
        && receipt.resource_fencing_token_sha256
          === appBuild.resource_fencing_token_sha256
        && isSha256(receipt.challenge_sha256)
        && receipt.runtime_revision === appBuild.runtime_revision
        && receipt.app_instance_id === appBuild.app_instance_id
        && canonicalJson(receipt.active_profiles)
          === canonicalJson(['aws', 'read-model-benchmark', 'traffic-benchmark'])
        && receipt.scheduler_enabled === false
        && receipt.kafka_listener_enabled === false
        && receipt.inventory_lifecycle_enabled === false
        && receipt.external_side_effects_enabled === false,
      'runtime assertion receipt is invalid',
    );
  }
  requireCondition(
    assertion.pre.run_id === assertion.post.run_id
      && assertion.pre.resource_fencing_token_sha256
        === assertion.post.resource_fencing_token_sha256
      && assertion.pre.runtime_revision === assertion.post.runtime_revision
      && assertion.pre.app_instance_id === assertion.post.app_instance_id
      && assertion.pre.challenge_sha256 !== assertion.post.challenge_sha256
      && assertion.runtime_assertion_pre_sha256
        !== assertion.runtime_assertion_post_sha256,
    'runtime assertion identity drift invalidates the source',
  );
}

function validateSource(source) {
  requireCondition(isObject(source), 'source artifact must be an object');
  requireCondition(!containsKnownSecret(source), 'source artifact contains a known credential');
  requireCondition(!containsSensitiveData(source), 'source artifact contains sensitive or PII data');
  requireCondition(hasExactKeys(source, SOURCE_KEYS), 'source artifact contract is invalid');
  requireCondition(source.schema_version === SOURCE_SCHEMA_VERSION, 'source schema version is invalid');
  validateMetadata(source.metadata);
  validateValidity(source.validity);
  validateParity(source.parity, source.metadata.manifest_target);
  validatePerformance(source.performance, source.validity);
  requireCondition(
    isSha256(source.measurement_fencing_token_sha256),
    'measurement fencing token digest is invalid',
  );
  validateRuntimeAssertion(
    source.runtime_assertion,
    source.metadata.app_build,
    source.metadata.run_id,
  );
  validateMysqlEvidence(source.mysql_evidence, source.metadata);
}

function sharedMetadata(metadata) {
  return {
    run_id: metadata.run_id,
    design: metadata.design,
    experiment_id: metadata.experiment_id,
    domain: metadata.domain,
    target_class: metadata.target_class,
    release_tuple: metadata.release_tuple,
    manifest_target: metadata.manifest_target,
    app_build: metadata.app_build,
    database: metadata.database,
    treatment: {
      kind: metadata.treatment.kind,
      candidate_index: metadata.treatment.candidate_index,
      candidate_visible: metadata.treatment.candidate_visible,
    },
  };
}

function validateSharedMetadata(reference, candidate) {
  requireCondition(
    canonicalJson(sharedMetadata(reference)) === canonicalJson(sharedMetadata(candidate)),
    'source experiment metadata does not match',
  );
}

function validateCompletePairs(sources, design) {
  const requiredRoles = Object.keys(DESIGN_ROLES[design]).sort();
  const blocks = new Map();
  const windows = new Set();
  const events = new Set();
  for (const source of sources) {
    const { block_id: blockId, pair_role: pairRole, window_id: windowId } = source.metadata;
    const eventId = source.metadata.statement_event_id;
    requireCondition(!windows.has(windowId), 'statement windows must be unique');
    requireCondition(!events.has(eventId), 'statement events must be unique');
    windows.add(windowId);
    events.add(eventId);
    const roles = blocks.get(blockId) || [];
    roles.push(pairRole);
    blocks.set(blockId, roles);
  }
  for (const roles of blocks.values()) {
    requireCondition(
      canonicalJson([...roles].sort()) === canonicalJson(requiredRoles),
      'source block pair contract is incomplete or duplicated',
    );
  }
  if (design !== 'READ_MODEL_AB') {
    for (const blockId of blocks.keys()) {
      const variants = new Set(sources
        .filter((source) => source.metadata.block_id === blockId)
        .map((source) => source.metadata.variant));
      requireCondition(variants.size === 1, 'non-read-model pair variants must match');
    }
  }
  return blocks.size;
}

function normalizeSource(source) {
  return {
    generated_at: source.metadata.generated_at,
    block_id: source.metadata.block_id,
    window_id: source.metadata.window_id,
    statement_event_id: source.metadata.statement_event_id,
    pair_role: source.metadata.pair_role,
    variant: source.metadata.variant,
    validity: source.validity,
    parity: source.parity,
    performance: source.performance,
    measurement_fencing_token_sha256: source.measurement_fencing_token_sha256,
    runtime_assertion: source.runtime_assertion,
    mysql_evidence: {
      statement_event: source.mysql_evidence.statement_event,
      optimizer_state: source.mysql_evidence.optimizer_state,
      explain: {
        json_raw: source.mysql_evidence.explain.json_raw,
        tree_raw: source.mysql_evidence.explain.tree_raw,
        parsed_tree: parseExplainAnalyzeTree(source.mysql_evidence.explain.tree_raw),
        candidate_in_chosen_plan: source.mysql_evidence.explain.candidate_in_chosen_plan,
      },
    },
  };
}

function relativeImprovement(baseline, treatment) {
  requireCondition(baseline > 0, 'paired baseline latency must be positive');
  return (baseline - treatment) / baseline;
}

function pairedEffects(sources, design) {
  const blocks = [...new Set(sources.map((source) => source.metadata.block_id))].sort();
  return blocks.map((blockId) => {
    const pair = sources
      .filter((source) => source.metadata.block_id === blockId)
      .sort((left, right) => ROLE_ORDER[left.metadata.pair_role] - ROLE_ORDER[right.metadata.pair_role]);
    const effect = { block_id: blockId };
    METRICS.forEach((metric) => {
      const left = pair[0].performance.latency_ms[metric];
      const right = pair[1].performance.latency_ms[metric];
      effect[metric] = design === 'AA_NOISE'
        ? (right - left) / left
        : relativeImprovement(left, right);
    });
    return effect;
  });
}

function nearestRank(values, percentile) {
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.max(1, Math.ceil(percentile * sorted.length));
  return sorted[rank - 1];
}

function buildHeadline(sources, design) {
  const effects = pairedEffects(sources, design);
  if (design === 'AA_NOISE') {
    return {
      kind: 'AA_NOISE_ENVELOPE',
      paired_effects: effects,
      maximum_absolute_relative_delta: Object.fromEntries(METRICS.map((metric) => [
        metric,
        Math.max(...effects.map((effect) => Math.abs(effect[metric]))),
      ])),
    };
  }
  return {
    kind: design === 'READ_MODEL_AB'
      ? 'READ_MODEL_IMPROVEMENT'
      : 'INVISIBLE_INDEX_IMPROVEMENT',
    paired_effects: effects,
    median_improvement: Object.fromEntries(METRICS.map((metric) => [
      metric,
      nearestRank(effects.map((effect) => effect[metric]), 0.5),
    ])),
  };
}

export function aggregateReadModelEvidence(sources) {
  requireCondition(
    Array.isArray(sources) && sources.length >= 2 && sources.length <= MAX_SOURCE_COUNT,
    'source count/pair contract is invalid',
  );
  sources.forEach(validateSource);
  const reference = sources[0].metadata;
  sources.slice(1).forEach((source) => validateSharedMetadata(reference, source.metadata));
  const blockCount = validateCompletePairs(sources, reference.design);
  const sortedSources = [...sources].sort((left, right) => (
    left.metadata.block_id.localeCompare(right.metadata.block_id)
      || ROLE_ORDER[left.metadata.pair_role] - ROLE_ORDER[right.metadata.pair_role]
  ));

  const reasons = [];
  if (reference.design === 'INVISIBLE_INDEX_AB') {
    const candidateSources = sortedSources.filter(
      (source) => source.metadata.pair_role === 'INDEX_CANDIDATE',
    );
    if (candidateSources.some(
      (source) => source.mysql_evidence.explain.candidate_in_chosen_plan !== true,
    )) {
      reasons.push('candidate-not-in-chosen-plan');
    }
  }
  const eligible = reasons.length === 0;
  const generatedAt = sortedSources
    .map((source) => source.metadata.generated_at)
    .sort()
    .at(-1);

  return {
    schema_version: OBSERVATION_SCHEMA_VERSION,
    metadata: {
      generated_at: generatedAt,
      ...sharedMetadata(reference),
      observation_count: sortedSources.length,
      block_count: blockCount,
    },
    eligibility: {
      status: eligible ? 'valid' : 'invalid',
      reasons,
      headline_allowed: eligible,
    },
    headline: eligible ? buildHeadline(sortedSources, reference.design) : null,
    observations: sortedSources.map(normalizeSource),
  };
}

export function validateAaObservationArtifact(artifact) {
  requireCondition(isObject(artifact), 'AA observation artifact must be an object');
  requireCondition(!containsKnownSecret(artifact), 'AA observation artifact contains a known credential');
  requireCondition(!containsSensitiveData(artifact), 'AA observation artifact contains sensitive or PII data');
  requireCondition(hasExactKeys(artifact, [
    'schema_version', 'metadata', 'eligibility', 'headline', 'observations',
  ]), 'AA observation artifact is not an exact aggregate');
  requireCondition(
    artifact.schema_version === OBSERVATION_SCHEMA_VERSION
      && isObject(artifact.metadata)
      && artifact.metadata.design === 'AA_NOISE'
      && artifact.metadata.observation_count === 6
      && artifact.metadata.block_count === 3
      && Array.isArray(artifact.observations)
      && artifact.observations.length === 6,
    'candidate AA gate requires exactly six observations in three blocks',
  );

  const sources = artifact.observations.map((observation) => {
    requireCondition(hasExactKeys(observation, [
      'generated_at', 'block_id', 'window_id', 'statement_event_id', 'pair_role',
      'variant', 'validity', 'parity', 'performance', 'measurement_fencing_token_sha256',
      'runtime_assertion', 'mysql_evidence',
    ]), 'AA normalized observation contract is invalid');
    requireCondition(hasExactKeys(observation.mysql_evidence, [
      'statement_event', 'optimizer_state', 'explain',
    ]), 'AA normalized MySQL observation contract is invalid');
    requireCondition(hasExactKeys(observation.mysql_evidence.explain, [
      'json_raw', 'tree_raw', 'parsed_tree', 'candidate_in_chosen_plan',
    ]), 'AA normalized EXPLAIN contract is invalid');
    requireCondition(
      canonicalJson(observation.mysql_evidence.explain.parsed_tree)
        === canonicalJson(parseExplainAnalyzeTree(observation.mysql_evidence.explain.tree_raw)),
      'AA parsed EXPLAIN does not match its raw evidence',
    );
    return {
      schema_version: SOURCE_SCHEMA_VERSION,
      metadata: {
        generated_at: observation.generated_at,
        run_id: artifact.metadata.run_id,
        design: artifact.metadata.design,
        experiment_id: artifact.metadata.experiment_id,
        block_id: observation.block_id,
        window_id: observation.window_id,
        statement_event_id: observation.statement_event_id,
        domain: artifact.metadata.domain,
        target_class: artifact.metadata.target_class,
        variant: observation.variant,
        pair_role: observation.pair_role,
        phase: 'measure',
        release_tuple: artifact.metadata.release_tuple,
        manifest_target: artifact.metadata.manifest_target,
        app_build: artifact.metadata.app_build,
        database: artifact.metadata.database,
        treatment: {
          ...artifact.metadata.treatment,
          optimizer_switch_use_invisible_indexes: false,
        },
      },
      validity: observation.validity,
      parity: observation.parity,
      performance: observation.performance,
      measurement_fencing_token_sha256: observation.measurement_fencing_token_sha256,
      runtime_assertion: observation.runtime_assertion,
      mysql_evidence: {
        statement_event: observation.mysql_evidence.statement_event,
        optimizer_state: observation.mysql_evidence.optimizer_state,
        explain: {
          json_raw: observation.mysql_evidence.explain.json_raw,
          tree_raw: observation.mysql_evidence.explain.tree_raw,
          candidate_in_chosen_plan:
            observation.mysql_evidence.explain.candidate_in_chosen_plan,
        },
      },
    };
  });
  const reconstructed = aggregateReadModelEvidence(sources);
  requireCondition(
    canonicalJson(reconstructed) === canonicalJson(artifact),
    'AA observation artifact is not reconstructable from its exact observations',
  );
  return artifact;
}

function artifactRootIsTrusted() {
  try {
    const canonicalRepoRoot = realpathSync(REPO_ROOT);
    const components = [
      REPO_ROOT,
      resolve(REPO_ROOT, 'build'),
      resolve(REPO_ROOT, 'build/k6'),
      ARTIFACT_ROOT,
    ];
    if (!components.every((component) => {
      const stat = lstatSync(component);
      return stat.isDirectory() && !stat.isSymbolicLink();
    })) {
      return false;
    }
    return realpathSync(ARTIFACT_ROOT)
      === resolve(canonicalRepoRoot, 'build/k6/read-model');
  } catch (_) {
    return false;
  }
}

function requireTrustedArtifactRoot() {
  requireCondition(artifactRootIsTrusted(), 'artifact root is not a trusted canonical directory');
}

function absoluteArtifactPath(relativePath) {
  const absolute = resolve(REPO_ROOT, relativePath);
  requireCondition(
    dirname(absolute) === ARTIFACT_ROOT,
    'artifact path is outside the fixed repository boundary',
  );
  return absolute;
}

function pathEntryExists(path) {
  try {
    lstatSync(path);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return false;
    }
    throw new Error('artifact path cannot be inspected safely');
  }
}

function readSource(relativePath, byteBudget) {
  requireTrustedArtifactRoot();
  const absolute = absoluteArtifactPath(relativePath);
  let entry;
  try {
    entry = lstatSync(absolute);
  } catch (_) {
    throw new Error('source artifact cannot be opened safely');
  }
  requireCondition(
    entry.isFile() && !entry.isSymbolicLink(),
    'source artifact must be a regular non-symbolic-link file',
  );
  requireCondition(Number.isInteger(constants.O_NOFOLLOW), 'runtime lacks no-follow file support');

  let descriptor;
  try {
    descriptor = openSync(absolute, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (_) {
    throw new Error('source artifact cannot be opened safely');
  }
  let contents;
  try {
    const before = fstatSync(descriptor);
    requireCondition(before.isFile(), 'source artifact must remain a regular file');
    requireCondition(before.size <= MAX_SOURCE_BYTES, 'source artifact is too large');
    contents = readFileSync(descriptor, 'utf8');
    const after = fstatSync(descriptor);
    const bytes = Buffer.byteLength(contents, 'utf8');
    requireCondition(
      before.dev === after.dev
        && before.ino === after.ino
        && before.size === after.size
        && bytes === after.size,
      'source artifact changed while it was read',
    );
    byteBudget.total += bytes;
    requireCondition(
      byteBudget.total <= MAX_TOTAL_SOURCE_BYTES,
      'source artifacts exceed the cumulative size limit',
    );
  } finally {
    closeSync(descriptor);
  }
  requireTrustedArtifactRoot();
  try {
    return JSON.parse(contents);
  } catch (_) {
    throw new Error('source artifact cannot be read as JSON');
  }
}

function readStandaloneJson(path) {
  const absolute = resolve(path);
  let entry;
  try {
    entry = lstatSync(absolute);
  } catch (_) {
    throw new Error('validation artifact cannot be opened safely');
  }
  requireCondition(
    entry.isFile() && !entry.isSymbolicLink() && entry.size <= MAX_SOURCE_BYTES,
    'validation artifact must be a bounded regular non-symbolic-link file',
  );
  requireCondition(Number.isInteger(constants.O_NOFOLLOW), 'runtime lacks no-follow file support');
  let descriptor;
  try {
    descriptor = openSync(absolute, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (_) {
    throw new Error('validation artifact cannot be opened safely');
  }
  try {
    const before = fstatSync(descriptor);
    const contents = readFileSync(descriptor, 'utf8');
    const after = fstatSync(descriptor);
    requireCondition(
      before.dev === after.dev && before.ino === after.ino && before.size === after.size
        && Buffer.byteLength(contents, 'utf8') === after.size,
      'validation artifact changed while it was read',
    );
    return JSON.parse(contents);
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error('validation artifact cannot be read as JSON');
    }
    throw error;
  } finally {
    closeSync(descriptor);
  }
}

function parseArguments(args) {
  requireCondition(
    args.length >= 4 && args[0] === '--output',
    'output and at least two source artifacts are required',
  );
  const outputPath = args[1];
  const sourcePaths = args.slice(2);
  requireCondition(
    typeof outputPath === 'string'
      && /^build\/k6\/read-model\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}-observations\.json$/.test(outputPath)
      && outputPath.length <= 255,
    'output path is invalid',
  );
  requireCondition(
    sourcePaths.length >= 2 && sourcePaths.length <= MAX_SOURCE_COUNT,
    'source count is invalid',
  );
  requireCondition(new Set(sourcePaths).size === sourcePaths.length, 'source paths must be unique');
  sourcePaths.forEach((sourcePath) => requireCondition(
    typeof sourcePath === 'string'
      && /^build\/k6\/read-model\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,220}\.json$/.test(sourcePath)
      && sourcePath.length <= 255,
    'source path is invalid',
  ));
  requireCondition(!sourcePaths.includes(outputPath), 'output must differ from every source');
  requireCondition(
    !containsKnownSecret([outputPath, ...sourcePaths]),
    'public arguments contain a known credential',
  );
  requireTrustedArtifactRoot();
  requireCondition(!pathEntryExists(absoluteArtifactPath(outputPath)), 'output path already exists');
  return { outputPath, sourcePaths };
}

function writeArtifact(outputPath, artifact) {
  requireTrustedArtifactRoot();
  const absolute = absoluteArtifactPath(outputPath);
  const temporary = resolve(
    ARTIFACT_ROOT,
    `.${basename(absolute)}.${process.pid}.${Date.now()}.partial`,
  );
  requireCondition(Number.isInteger(constants.O_NOFOLLOW), 'runtime lacks no-follow file support');
  requireCondition(!pathEntryExists(absolute), 'output path already exists');

  let descriptor;
  let temporaryCreated = false;
  try {
    try {
      descriptor = openSync(
        temporary,
        constants.O_WRONLY
          | constants.O_CREAT
          | constants.O_EXCL
          | constants.O_NOFOLLOW,
        0o600,
      );
    } catch (_) {
      throw new Error('temporary artifact cannot be opened safely');
    }
    temporaryCreated = true;
    writeFileSync(descriptor, `${JSON.stringify(artifact, null, 2)}\n`, 'utf8');
    fsyncSync(descriptor);
    const stat = fstatSync(descriptor);
    requireCondition(
      stat.isFile() && (stat.mode & 0o777) === 0o600,
      'temporary artifact permissions are invalid',
    );
    closeSync(descriptor);
    descriptor = undefined;
    requireTrustedArtifactRoot();
    linkSync(temporary, absolute);
    unlinkSync(temporary);
    temporaryCreated = false;
  } finally {
    if (descriptor !== undefined) {
      closeSync(descriptor);
    }
    if (temporaryCreated && pathEntryExists(temporary)) {
      unlinkSync(temporary);
    }
  }
}

function main(args) {
  if (args.length === 2 && args[0] === '--validate-aa') {
    validateAaObservationArtifact(readStandaloneJson(args[1]));
    return;
  }
  const { outputPath, sourcePaths } = parseArguments(args);
  const byteBudget = { total: 0 };
  const sources = sourcePaths.map((sourcePath) => readSource(sourcePath, byteBudget));
  const aggregate = aggregateReadModelEvidence(sources);
  writeArtifact(outputPath, aggregate);
}

if (process.argv[1]
  && realpathSync(resolve(process.argv[1])) === realpathSync(SCRIPT_PATH)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(
      `read-model observation aggregation failed: ${error instanceof Error ? error.message : 'unknown error'}\n`,
    );
    process.exitCode = 1;
  }
}

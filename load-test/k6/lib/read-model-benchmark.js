import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

import { benchmarkHeaders } from './benchmark-fixture.js';
import {
  findCapsuleTarget,
  findExperimentCapsule,
  parseBenchmarkDatasetManifest,
} from './benchmark-dataset-manifest.js';

const READ_MODEL_CAPSULE_ID = 'read-model-v2';
const SHA256 = /^[0-9a-f]{64}$/;
const SLUG = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/;
const LEGACY_TARGET_ENVIRONMENT = [
  'REVIEW_ACCOMMODATION_ID',
  'EXPECTED_REVIEW_COUNT',
  'BENCHMARK_EMAIL',
  'TEST_PASSWORD',
  'ADMIN_EMAIL',
  'ADMIN_PASSWORD',
  'PAGE_SIZE',
  'EXPECTED_ROWS',
  'REVENUE_FROM',
  'REVENUE_TO',
  'DATASET_LABEL',
];
const QUERY_DOMAINS = Object.freeze({
  REVIEW_SUMMARY_V1: 'review',
  WISHLIST_PAGE_V1: 'wishlist',
  REVENUE_RANGE_V1: 'revenue',
});
const DESIGN_ROLES = Object.freeze({
  READ_MODEL_AB: Object.freeze({ BEFORE: 'before', AFTER: 'after' }),
  AA_NOISE: Object.freeze({ AA_A: null, AA_B: null }),
});

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
  const sortedExpected = [...expected].sort();
  return actual.length === sortedExpected.length
    && actual.every((key, index) => key === sortedExpected[index]);
}

function requireExactKeys(value, expected, name) {
  requireCondition(hasExactKeys(value, expected), `${name} has an invalid key set`);
}

function isSha256(value) {
  return typeof value === 'string' && SHA256.test(value);
}

function isSlug(value) {
  return typeof value === 'string' && SLUG.test(value);
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
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

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null;
}

function metricValues(data, name) {
  return data.metrics?.[name]?.values || {};
}

export function parseRequiredText(raw, name) {
  requireCondition(
    typeof raw === 'string' && raw.trim().length > 0,
    `${name} is required`,
  );
  return raw.trim();
}

export function parseVariant(raw) {
  requireCondition(raw === 'before' || raw === 'after', 'VARIANT must be before or after');
  return raw;
}

export function parsePositiveInteger(raw, name) {
  const value = Number(raw);
  requireCondition(Number.isSafeInteger(value) && value > 0, `${name} must be a positive integer`);
  return value;
}

export function parseDurationSeconds(raw, name = 'DURATION') {
  const compact = typeof raw === 'string' ? raw.replace(/\s+/g, '') : '';
  const expression = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
  const unitSeconds = { ms: 0.001, s: 1, m: 60, h: 3600 };
  let consumed = '';
  let seconds = 0;
  let match;

  while ((match = expression.exec(compact)) !== null) {
    consumed += match[0];
    seconds += Number(match[1]) * unitSeconds[match[2]];
  }

  requireCondition(
    consumed === compact && Number.isFinite(seconds) && seconds > 0,
    `${name} must be a positive k6 duration`,
  );
  return seconds;
}

function buildWishlistCursor(query) {
  if (query.lastId === null) {
    return null;
  }
  const payload = JSON.stringify({
    id: query.lastId,
    last_created_at: query.lastCreatedAt,
  });
  return encoding.b64encode(payload, 'std');
}

export function buildReadModelPath({ query, variant }) {
  const version = parseVariant(variant) === 'before' ? 'v2' : 'v1';
  if (query.kind === 'REVIEW_SUMMARY_V1') {
    return `/api/${version}/accommodations/${query.accommodationId}/reviews/summary`;
  }
  if (query.kind === 'WISHLIST_PAGE_V1') {
    const parameters = [`size=${query.size}`];
    const cursor = buildWishlistCursor(query);
    if (cursor !== null) {
      parameters.push(`cursor=${encodeURIComponent(cursor)}`);
    }
    if (query.accommodationId !== null) {
      parameters.push(`accommodationId=${query.accommodationId}`);
    }
    return `/api/${version}/members/wishlists?${parameters.join('&')}`;
  }
  if (query.kind === 'REVENUE_RANGE_V1') {
    return `/api/${version}/admin/stats/revenue?from=${query.from}&to=${query.to}`;
  }
  throw new Error('read-model query kind is unsupported');
}

export function buildReadModelRequestName(domain, variant) {
  const version = parseVariant(variant) === 'before' ? 'v2' : 'v1';
  if (domain === 'review') {
    return `GET /api/${version}/accommodations/{accommodationId}/reviews/summary`;
  }
  if (domain === 'wishlist') {
    return `GET /api/${version}/members/wishlists`;
  }
  if (domain === 'revenue') {
    return `GET /api/${version}/admin/stats/revenue`;
  }
  throw new Error('domain must be review, wishlist, or revenue');
}

function accountReference(account) {
  return account === undefined
    ? null
    : `${account.role.toLowerCase()}-${account.memberId}`;
}

export function buildReadModelTarget(manifestRaw, targetId) {
  requireCondition(
    typeof manifestRaw === 'string' && manifestRaw.trim().length > 0,
    'BENCHMARK_DATASET_MANIFEST content is required',
  );
  const raw = manifestRaw;
  const parsedTargetId = parseRequiredText(targetId, 'TARGET_ID');
  const manifest = parseBenchmarkDatasetManifest(raw);
  const capsule = findExperimentCapsule(manifest, READ_MODEL_CAPSULE_ID);
  const target = findCapsuleTarget(capsule, parsedTargetId);
  const query = target.query;
  const domain = QUERY_DOMAINS[query.kind];
  requireCondition(Boolean(domain), 'TARGET_ID must select a read-model query');
  const account = target.account;
  if (domain === 'wishlist') {
    requireCondition(
      account?.memberId === query.memberId
        && account.role === 'MEMBER'
        && account.status === 'ACTIVE',
      'wishlist target must bind its ACTIVE MEMBER account',
    );
  }
  if (domain === 'revenue') {
    requireCondition(
      account?.role === 'ADMIN' && account.status === 'ACTIVE',
      'revenue target must bind its ACTIVE ADMIN account',
    );
  }
  const manifestSha256 = crypto.sha256(raw, 'hex');
  const parameterHash = crypto.sha256(canonicalJson(query), 'hex');
  return {
    manifest,
    capsule,
    target,
    query,
    domain,
    account: account || null,
    expectedCount: target.expectedRows,
    expectedResultHash: target.expectedResultHash,
    beforePath: buildReadModelPath({ query, variant: 'before' }),
    afterPath: buildReadModelPath({ query, variant: 'after' }),
    manifestSha256,
    manifestTarget: {
      capsule_id: capsule.capsuleId,
      target_id: target.id,
      query_kind: query.kind,
      parameter_hash_sha256: parameterHash,
      expected_rows: target.expectedRows,
      expected_result_hash: target.expectedResultHash,
      account_ref: accountReference(account),
    },
  };
}

function validateReleaseTuple(value, selection) {
  requireExactKeys(value, [
    'release_id',
    'dataset_version',
    'world_version',
    'source_calibration_sha256',
    'production_skew_spec_sha256',
    'dataset_manifest_sha256',
    'dump_sha256',
    'schema_migration_sha256',
    'target_fingerprint_sha256',
  ], 'release_tuple');
  requireCondition(
    isSlug(value.release_id)
      && value.dataset_version === 'benchmark-dataset-v2'
      && value.world_version === 'world-v2'
      && Object.entries(value)
        .filter(([key]) => key.endsWith('_sha256'))
        .every(([, digest]) => isSha256(digest)),
    'release_tuple is invalid',
  );
  requireCondition(
    value.dataset_manifest_sha256 === selection.manifestSha256
      && value.target_fingerprint_sha256 === selection.manifest.targetFingerprint
      && value.source_calibration_sha256
        === selection.manifest.world.provenance.calibrationSha256
      && value.production_skew_spec_sha256 === selection.manifest.world.provenance.specSha256,
    'release_tuple does not bind the selected manifest',
  );
}

function validateAppBuild(value) {
  requireExactKeys(value, [
    'commit_sha', 'image_digest', 'build_id', 'instance_count',
    'runtime_revision', 'app_instance_id', 'resource_fencing_token_sha256',
  ], 'app_build');
  requireCondition(
    /^[0-9a-f]{40}$/.test(value.commit_sha || '')
      && /^sha256:[0-9a-f]{64}$/.test(value.image_digest || '')
      && isSlug(value.build_id)
      && value.instance_count === 1
      && isSha256(value.runtime_revision)
      && isSha256(value.resource_fencing_token_sha256)
      && /^i-[0-9a-f]{8,17}$/.test(value.app_instance_id || ''),
    'app_build is invalid',
  );
}

function validateDatabase(value) {
  requireExactKeys(value, [
    'clone_id',
    'pre_fingerprint_sha256',
    'post_fingerprint_sha256',
    'optimizer_snapshot_sha256',
    'statistics_snapshot_sha256',
    'histogram_snapshot_sha256',
    'analyze_receipt_sha256',
    'mysql_version',
    'auto_statistics_recalculation_detected',
  ], 'database');
  requireCondition(
    isSlug(value.clone_id)
      && Object.entries(value)
        .filter(([key]) => key.endsWith('_sha256'))
        .every(([, digest]) => isSha256(digest))
      && /^8\.0\.[0-9]+(?:[-+][a-zA-Z0-9._-]+)?$/.test(value.mysql_version || '')
      && value.auto_statistics_recalculation_detected === false,
    'database evidence is invalid',
  );
  requireCondition(
    value.pre_fingerprint_sha256 === value.post_fingerprint_sha256,
    'database fingerprint drift invalidates the run',
  );
}

function validateTreatment(value) {
  requireExactKeys(value, [
    'kind',
    'candidate_index',
    'candidate_visible',
    'optimizer_switch_use_invisible_indexes',
  ], 'treatment');
  requireCondition(
    value.kind === 'READ_MODEL'
      && value.candidate_index === null
      && value.candidate_visible === null
      && value.optimizer_switch_use_invisible_indexes === false,
    'application k6 evidence cannot claim an invisible-index treatment',
  );
}

function validateLifecycle(value) {
  requireExactKeys(value, [
    'scheduler_enabled',
    'kafka_listener_enabled',
    'inventory_lifecycle_enabled',
    'external_side_effects_enabled',
  ], 'lifecycle');
  requireCondition(
    Object.values(value).every((enabled) => enabled === false),
    'scheduler, listener, inventory, and external side effects must be disabled',
  );
}

function validateRuntimeAssertion(value, context) {
  requireExactKeys(value, [
    'runtime_assertion_pre_sha256',
    'runtime_assertion_post_sha256',
    'pre',
    'post',
  ], 'runtime_assertion');
  requireCondition(
    isSha256(value.runtime_assertion_pre_sha256)
      && isSha256(value.runtime_assertion_post_sha256),
    'runtime assertion receipt digests are invalid',
  );
  for (const receipt of [value.pre, value.post]) {
    requireExactKeys(receipt, [
      'schema_version', 'run_id', 'resource_fencing_token_sha256', 'challenge_sha256',
      'runtime_revision', 'app_instance_id', 'active_profiles',
      'scheduler_enabled', 'kafka_listener_enabled', 'inventory_lifecycle_enabled',
      'external_side_effects_enabled',
    ], 'runtime assertion receipt');
    requireCondition(
      receipt.schema_version === 1
        && receipt.run_id === context.run_id
        && isSha256(receipt.resource_fencing_token_sha256)
        && receipt.resource_fencing_token_sha256
          === context.app_build.resource_fencing_token_sha256
        && isSha256(receipt.challenge_sha256)
        && receipt.runtime_revision === context.app_build.runtime_revision
        && receipt.app_instance_id === context.app_build.app_instance_id
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
    value.pre.run_id === value.post.run_id
      && value.pre.resource_fencing_token_sha256
        === value.post.resource_fencing_token_sha256
      && value.pre.runtime_revision === value.post.runtime_revision
      && value.pre.app_instance_id === value.post.app_instance_id,
    'runtime assertion identity drift invalidates the window',
  );
}

function validateMysqlEvidence(value, context) {
  requireExactKeys(value, ['statement_event', 'optimizer_state', 'explain'], 'mysql_evidence');
  const statement = value.statement_event;
  requireExactKeys(statement, ['window_id', 'event_id', 'digest', 'digest_text', 'delta'], 'statement_event');
  requireExactKeys(statement.delta, [
    'calls', 'timer_wait_ps', 'rows_examined', 'rows_sent', 'errors',
  ], 'statement_event.delta');
  requireCondition(
    statement.window_id === context.window_id
      && statement.event_id === context.statement_event_id
      && isSha256(statement.digest)
      && typeof statement.digest_text === 'string'
      && statement.digest_text.length > 0
      && statement.digest_text.length <= 8192
      && isPositiveInteger(statement.delta.calls)
      && ['timer_wait_ps', 'rows_examined', 'rows_sent']
        .every((key) => /^(0|[1-9][0-9]*)$/.test(statement.delta[key]))
      && statement.delta.errors === 0,
    'statement_event is invalid',
  );
  const optimizer = value.optimizer_state;
  requireExactKeys(optimizer, [
    'snapshot_sha256',
    'statistics_snapshot_sha256',
    'histogram_snapshot_sha256',
    'analyze_receipt_sha256',
  ], 'optimizer_state');
  requireCondition(
    optimizer.snapshot_sha256 === context.database.optimizer_snapshot_sha256
      && optimizer.statistics_snapshot_sha256 === context.database.statistics_snapshot_sha256
      && optimizer.histogram_snapshot_sha256 === context.database.histogram_snapshot_sha256
      && optimizer.analyze_receipt_sha256 === context.database.analyze_receipt_sha256,
    'optimizer evidence does not match the database snapshot',
  );
  requireExactKeys(value.explain, [
    'json_raw', 'tree_raw', 'candidate_in_chosen_plan',
  ], 'explain');
  requireCondition(
    typeof value.explain.json_raw === 'string'
      && value.explain.json_raw.length > 0
      && typeof value.explain.tree_raw === 'string'
      && value.explain.tree_raw.length > 0
      && typeof value.explain.candidate_in_chosen_plan === 'boolean',
    'EXPLAIN evidence is invalid',
  );
  if (context.treatment.candidate_index === null) {
    requireCondition(
      value.explain.candidate_in_chosen_plan === false,
      'read-model evidence cannot claim an index candidate',
    );
  }
}

export function parseReadModelEvidenceContext(raw, selection, variant) {
  let context;
  try {
    context = JSON.parse(raw);
  } catch (_) {
    throw new Error('READ_MODEL_EVIDENCE_CONTEXT must contain valid JSON');
  }
  requireExactKeys(context, [
    'schema_version',
    'run_id',
    'design',
    'experiment_id',
    'block_id',
    'window_id',
    'statement_event_id',
    'pair_role',
    'release_tuple',
    'app_build',
    'database',
    'treatment',
    'lifecycle',
    'measurement_fencing_token_sha256',
    'runtime_assertion',
    'mysql_evidence',
    'target_id',
  ], 'read-model evidence context');
  const parsedVariant = parseVariant(variant);
  requireCondition(
    context.schema_version === 'read-model-run-context-v1'
      && isSlug(context.run_id)
      && Object.prototype.hasOwnProperty.call(DESIGN_ROLES, context.design)
      && isSlug(context.experiment_id)
      && isSlug(context.block_id)
      && isSlug(context.window_id)
      && isSlug(context.statement_event_id)
      && Object.prototype.hasOwnProperty.call(DESIGN_ROLES[context.design], context.pair_role)
      && context.target_id === selection.target.id,
    'read-model evidence context is invalid',
  );
  const requiredVariant = DESIGN_ROLES[context.design][context.pair_role];
  requireCondition(
    requiredVariant === null || requiredVariant === parsedVariant,
    'pair role does not match VARIANT',
  );
  validateReleaseTuple(context.release_tuple, selection);
  validateAppBuild(context.app_build);
  validateDatabase(context.database);
  validateTreatment(context.treatment);
  validateLifecycle(context.lifecycle);
  requireCondition(
    isSha256(context.measurement_fencing_token_sha256),
    'measurement fencing token digest is invalid',
  );
  validateRuntimeAssertion(context.runtime_assertion, context);
  validateMysqlEvidence(context.mysql_evidence, context);
  return context;
}

export function parseReadModelRunConfig(environment, target, evidenceContext) {
  LEGACY_TARGET_ENVIRONMENT.forEach((key) => requireCondition(
    environment[key] === undefined || environment[key] === '',
    `${key} is forbidden; select TARGET_ID from read-model-v2`,
  ));
  const variant = parseVariant(parseRequiredText(environment.VARIANT, 'VARIANT'));
  const benchmarkToken = parseRequiredText(
    environment.BENCHMARK_READ_MODEL_TOKEN,
    'BENCHMARK_READ_MODEL_TOKEN',
  );
  const baseUrl = (environment.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
  requireCondition(/^https?:\/\/[^/]+$/.test(baseUrl), 'BASE_URL must be an HTTP origin');
  const rate = parsePositiveInteger(environment.RATE || '5', 'RATE');
  const preAllocatedVUs = parsePositiveInteger(
    environment.PRE_ALLOCATED_VUS || String(Math.max(20, rate * 4)),
    'PRE_ALLOCATED_VUS',
  );
  const maxVUs = parsePositiveInteger(
    environment.MAX_VUS || String(Math.max(preAllocatedVUs, rate * 10)),
    'MAX_VUS',
  );
  const requestTimeout = environment.REQUEST_TIMEOUT || '10s';
  parseDurationSeconds(requestTimeout, 'REQUEST_TIMEOUT');
  const mode = environment.READ_MODEL_MODE || 'evidence';
  requireCondition(
    ['evidence', 'measure', 'assemble'].includes(mode),
    'READ_MODEL_MODE must be evidence, measure, or assemble',
  );
  const resultStem = `${target.domain}-${target.target.id}-${variant}-${evidenceContext.block_id}`;
  requireCondition(/^[a-z0-9._-]+$/.test(resultStem), 'result artifact stem is invalid');
  const measurementPath = environment.K6_MEASUREMENT_PATH
    || `build/k6/read-model/${resultStem}-measurement.json`;
  requireCondition(
    /^build\/k6\/read-model\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}\.json$/.test(measurementPath),
    'K6_MEASUREMENT_PATH is outside the read-model artifact boundary',
  );
  const resultPath = environment.K6_RESULT_PATH
    || `build/k6/read-model/${resultStem}.json`;
  requireCondition(
    /^build\/k6\/read-model\/[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}\.json$/.test(resultPath),
    'K6_RESULT_PATH is outside the read-model artifact boundary',
  );

  return {
    mode,
    variant,
    benchmarkToken,
    baseUrl,
    rate,
    warmupDuration: environment.WARMUP_DURATION || '30s',
    measureDuration: environment.MEASURE_DURATION || '1m',
    warmupSettleSeconds: Number(environment.WARMUP_SETTLE_SECONDS || 5),
    preAllocatedVUs,
    maxVUs,
    requestTimeout,
    measurementPath,
    resultPath,
    target,
    mysqlEvidence: evidenceContext.mysql_evidence,
    runtimeAssertion: evidenceContext.runtime_assertion,
    measurementFencingTokenSha256: evidenceContext.measurement_fencing_token_sha256,
    metadata: {
      run_id: evidenceContext.run_id,
      design: evidenceContext.design,
      experiment_id: evidenceContext.experiment_id,
      block_id: evidenceContext.block_id,
      window_id: evidenceContext.window_id,
      statement_event_id: evidenceContext.statement_event_id,
      domain: target.domain,
      target_class: target.target.id.slice(target.domain.length + 1),
      variant,
      pair_role: evidenceContext.pair_role,
      phase: 'measure',
      release_tuple: evidenceContext.release_tuple,
      manifest_target: target.manifestTarget,
      app_build: evidenceContext.app_build,
      database: evidenceContext.database,
      treatment: evidenceContext.treatment,
    },
  };
}

export function buildReadModelOptions({
  rate,
  warmupDuration,
  measureDuration,
  warmupSettleSeconds,
  preAllocatedVUs,
  maxVUs,
}) {
  const parsedRate = parsePositiveInteger(String(rate), 'RATE');
  const parsedPreAllocatedVUs = parsePositiveInteger(
    String(preAllocatedVUs),
    'PRE_ALLOCATED_VUS',
  );
  const parsedMaxVUs = parsePositiveInteger(String(maxVUs), 'MAX_VUS');
  requireCondition(
    parsedMaxVUs >= parsedPreAllocatedVUs,
    'MAX_VUS must be greater than or equal to PRE_ALLOCATED_VUS',
  );
  requireCondition(
    Number.isInteger(warmupSettleSeconds) && warmupSettleSeconds >= 0,
    'WARMUP_SETTLE_SECONDS must be a non-negative integer',
  );

  const gracefulStopSeconds = 5;
  const warmupSeconds = parseDurationSeconds(warmupDuration, 'WARMUP_DURATION');
  const measureSeconds = parseDurationSeconds(measureDuration, 'MEASURE_DURATION');
  requireCondition(warmupSeconds >= 1, 'WARMUP_DURATION must be at least 1s');
  requireCondition(measureSeconds >= 1, 'MEASURE_DURATION must be at least 1s');
  const scenario = (exec, duration, startTime) => {
    const value = {
      executor: 'constant-arrival-rate',
      exec,
      rate: parsedRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: parsedPreAllocatedVUs,
      maxVUs: parsedMaxVUs,
      gracefulStop: `${gracefulStopSeconds}s`,
    };
    if (startTime) {
      value.startTime = startTime;
    }
    return value;
  };

  return {
    setupTimeout: '1m',
    scenarios: {
      warmup: scenario('warmup', warmupDuration),
      measure: scenario(
        'measure',
        measureDuration,
        `${warmupSeconds + gracefulStopSeconds + warmupSettleSeconds}s`,
      ),
    },
    thresholds: {
      'read_model_request_success{phase:measure}': ['rate==1'],
      'http_req_failed{phase:measure}': ['rate==0'],
      'dropped_iterations{scenario:measure}': ['count==0'],
    },
    summaryTrendStats: ['count', 'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  };
}

export function buildReadModelRequestParams({
  variant,
  benchmarkToken,
  sessionId,
  tags = {},
  timeout,
}) {
  parseVariant(variant);
  const params = { tags };
  if (typeof sessionId === 'string' && sessionId.trim()) {
    params.cookies = { SESSION_ID: sessionId.trim() };
  }
  if (timeout) {
    params.timeout = timeout;
  }
  params.headers = benchmarkHeaders(benchmarkToken);
  return params;
}

function isFiniteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function matchesReviewContract(data, expectedCount) {
  return data.total_count === expectedCount
    && isFiniteNumber(data.average_rating)
    && data.average_rating >= 0
    && data.average_rating <= 5;
}

export function canonicalizeReadModelData(domain, data) {
  if (!isObject(data)) {
    throw new Error('read model data must be an object');
  }
  if (domain === 'review') {
    return {
      total_count: data.total_count,
      average_rating: data.average_rating,
    };
  }
  if (domain === 'wishlist') {
    return {
      wishlists: Array.isArray(data.wishlists)
        ? data.wishlists.map((wishlist) => ({
          id: wishlist.id,
          name: wishlist.name,
          created_at: wishlist.created_at,
          wishlist_item_count: wishlist.wishlist_item_count,
          thumbnail_image_url: wishlist.thumbnail_image_url,
          is_contained: wishlist.is_contained,
          wishlist_accommodation_id: wishlist.wishlist_accommodation_id,
        }))
        : data.wishlists,
      page_info: data.page_info
        ? {
          has_next: data.page_info.has_next,
          next_cursor: data.page_info.next_cursor,
          current_size: data.page_info.current_size,
        }
        : data.page_info,
    };
  }
  if (domain === 'revenue') {
    return {
      from: data.from,
      to: data.to,
      items: Array.isArray(data.items)
        ? data.items.map((item) => ({
          date: item.date,
          gross_amount: item.gross_amount,
          refund_amount: item.refund_amount,
          net_amount: item.net_amount,
          payment_count: item.payment_count,
          refund_count: item.refund_count,
        }))
        : data.items,
    };
  }
  throw new Error('domain must be review, wishlist, or revenue');
}

export function readModelPayloadsEquivalent(domain, leftPayload, rightPayload) {
  if (!leftPayload || leftPayload.success !== true || !leftPayload.data
      || !rightPayload || rightPayload.success !== true || !rightPayload.data) {
    return false;
  }
  try {
    return JSON.stringify(canonicalizeReadModelData(domain, leftPayload.data))
      === JSON.stringify(canonicalizeReadModelData(domain, rightPayload.data));
  } catch (_) {
    return false;
  }
}

function matchesWishlistContract(data, expectedCount) {
  if (!Array.isArray(data.wishlists)
      || data.wishlists.length !== expectedCount
      || !data.page_info
      || data.page_info.current_size !== expectedCount
      || typeof data.page_info.has_next !== 'boolean') {
    return false;
  }
  return data.wishlists.every((wishlist) => (
    isPositiveInteger(wishlist.id)
      && typeof wishlist.name === 'string'
      && typeof wishlist.created_at === 'string'
      && isNonNegativeInteger(wishlist.wishlist_item_count)
      && (wishlist.thumbnail_image_url === null
        || (typeof wishlist.thumbnail_image_url === 'string'
          && wishlist.thumbnail_image_url.length > 0))
  ));
}

function matchesRevenueItem(item) {
  return /^\d{4}-\d{2}-\d{2}$/.test(item.date || '')
    && isNonNegativeInteger(item.gross_amount)
    && isNonNegativeInteger(item.refund_amount)
    && Number.isSafeInteger(item.net_amount)
    && isNonNegativeInteger(item.payment_count)
    && isNonNegativeInteger(item.refund_count);
}

function matchesRevenueContract(data, variant, expectedCount, from, to) {
  const expectedSource = variant === 'before' ? 'raw' : 'stats';
  return data.from === from
    && data.to === to
    && data.source === expectedSource
    && Array.isArray(data.items)
    && data.items.length === expectedCount
    && data.items.every(matchesRevenueItem)
    && data.items.every((item) => item.date >= from && item.date <= to)
    && data.items.every((item, index) => index === 0 || data.items[index - 1].date < item.date);
}

export function matchesReadModelContract({
  domain,
  variant,
  payload,
  expectedCount,
  expectedData,
  from,
  to,
}) {
  if (!payload || payload.success !== true || !payload.data
      || !isNonNegativeInteger(expectedCount)) {
    return false;
  }
  let matchesStructure = false;
  if (domain === 'review') {
    matchesStructure = matchesReviewContract(payload.data, expectedCount);
  }
  if (domain === 'wishlist') {
    matchesStructure = matchesWishlistContract(payload.data, expectedCount);
  }
  if (domain === 'revenue') {
    matchesStructure = matchesRevenueContract(payload.data, variant, expectedCount, from, to);
  }
  if (!matchesStructure) {
    return false;
  }
  if (expectedData === undefined) {
    return true;
  }
  try {
    return JSON.stringify(canonicalizeReadModelData(domain, payload.data))
      === JSON.stringify(expectedData);
  } catch (_) {
    return false;
  }
}

function updateLengthPrefixed(hash, value) {
  const text = value === null || value === undefined ? '' : String(value);
  const bytes = encoding.b64decode(encoding.b64encode(text, 'std'), 'std');
  const length = bytes.byteLength;
  const prefix = new Uint8Array([
    (length >>> 24) & 0xff,
    (length >>> 16) & 0xff,
    (length >>> 8) & 0xff,
    length & 0xff,
  ]);
  hash.update(prefix.buffer);
  hash.update(bytes);
}

export function canonicalWishlistCreatedAt(value) {
  requireCondition(typeof value === 'string', 'wishlist created_at must be an instant');
  const match = value.match(
    /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z?$/,
  );
  requireCondition(match !== null, 'wishlist created_at must be a UTC/local datetime with microsecond precision');
  return `${match[1]}.${(match[2] || '').padEnd(6, '0')}`;
}

export function buildCanonicalResultHash(domain, data) {
  const hash = crypto.createHash('sha256');
  if (domain === 'review') {
    requireCondition(
      isNonNegativeInteger(data.total_count)
        && isFiniteNumber(data.average_rating)
        && data.average_rating >= 0
        && data.average_rating <= 5,
      'review response cannot be hashed',
    );
    updateLengthPrefixed(hash, data.total_count);
    updateLengthPrefixed(hash, data.average_rating.toFixed(2));
  } else if (domain === 'wishlist') {
    requireCondition(Array.isArray(data.wishlists), 'wishlist response cannot be hashed');
    data.wishlists.forEach((wishlist) => {
      requireCondition(
        isPositiveInteger(wishlist.id)
          && typeof wishlist.name === 'string'
          && typeof wishlist.created_at === 'string'
          && isNonNegativeInteger(wishlist.wishlist_item_count),
        'wishlist response cannot be hashed',
      );
      updateLengthPrefixed(hash, wishlist.id);
      updateLengthPrefixed(hash, wishlist.name);
      updateLengthPrefixed(hash, canonicalWishlistCreatedAt(wishlist.created_at));
      updateLengthPrefixed(hash, wishlist.wishlist_item_count);
      updateLengthPrefixed(hash, wishlist.thumbnail_image_url);
    });
  } else if (domain === 'revenue') {
    requireCondition(Array.isArray(data.items), 'revenue response cannot be hashed');
    data.items.forEach((item) => {
      requireCondition(matchesRevenueItem(item), 'revenue response cannot be hashed');
      [
        item.date,
        item.gross_amount,
        item.refund_amount,
        item.net_amount,
        item.payment_count,
        item.refund_count,
      ].forEach((value) => updateLengthPrefixed(hash, value));
    });
  } else {
    throw new Error('domain must be review, wishlist, or revenue');
  }
  return hash.digest('hex');
}

export function summarizeReadModelMetrics(data, measureSeconds) {
  const success = metricValues(data, 'read_model_request_success');
  const latency = metricValues(data, 'read_model_client_duration');
  const dropped = metricValues(data, 'dropped_iterations{scenario:measure}');
  const successful = Number(success.passes || 0);
  const failed = Number(success.fails || 0);
  const attempted = successful + failed;

  return {
    requests: {
      attempted,
      successful,
      failed,
      dropped_iterations: Number(dropped.count || 0),
      achieved_rps: attempted / measureSeconds,
    },
    latency_ms: {
      count: Number(latency.count || successful),
      min: finiteOrNull(latency.min),
      p50: finiteOrNull(latency.med),
      p95: finiteOrNull(latency['p(95)']),
      p99: finiteOrNull(latency['p(99)']),
      max: finiteOrNull(latency.max),
    },
  };
}

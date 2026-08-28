import {
  findCapsuleTarget,
  findExperimentCapsule,
} from './benchmark-dataset-manifest.js';

export const DATASET_READ_TARGETS = Object.freeze(['cache-detail', 'index-query']);
export const CACHE_WARM_PREFETCH_METRIC = 'traffic_cache_warm_prefetch_completed_keys';
export const ELASTICSEARCH_DEFAULT_TOTAL_HITS_BOUND = 10_000;

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function exactKeys(value, keys) {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort());
}

function requiredCanonicalId(raw, name) {
  requireCondition(
    typeof raw === 'string' && /^[a-z0-9][a-z0-9-]*$/.test(raw),
    `${name} must be a canonical id`,
  );
  return raw;
}

function requiredBoolean(raw, name) {
  requireCondition(raw === 'true' || raw === 'false', `${name} must be true or false`);
  return raw === 'true';
}

function encodeQuery(query) {
  return [
    ['destination', query.destination],
    ['minPrice', query.minPrice],
    ['maxPrice', query.maxPrice],
    ['adultOccupancy', query.adultOccupancy],
    ['childOccupancy', query.childOccupancy],
    ['infantOccupancy', query.infantOccupancy],
    ['petOccupancy', query.petOccupancy],
    ['topLeftLat', query.topLeftLat],
    ['topLeftLng', query.topLeftLng],
    ['bottomRightLat', query.bottomRightLat],
    ['bottomRightLng', query.bottomRightLng],
    ['page', query.page],
  ].map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`).join('&');
}

function buildCacheTarget(manifest, profileId, environment) {
  const capsule = findExperimentCapsule(manifest, 'cache-detail-v1');
  requireCondition(
    capsule.mutability === 'READ_ONLY'
      && capsule.accountPool.capacity === 0
      && capsule.runtime.owner === 'AWS_LAB'
      && capsule.runtime.setup === 'flush-dedicated-cache-redis'
      && capsule.runtime.resetPolicy === 'restore-cache-state-per-run',
    'cache-detail-v1 runtime contract is invalid',
  );
  requireCondition(
    profileId === 'same-key' || profileId === 'detail-pool',
    'CAPSULE_TARGET is not supported by cache-detail-v1',
  );
  const target = findCapsuleTarget(capsule, profileId);
  const pool = findCapsuleTarget(capsule, 'detail-pool');
  const variant = requiredCanonicalId(environment.CACHE_VARIANT, 'CACHE_VARIANT');
  requireCondition(variant === 'disabled' || variant === 'warm', 'CACHE_VARIANT is invalid');
  const cacheEnabled = requiredBoolean(environment.CACHE_ENABLED, 'CACHE_ENABLED');
  requireCondition(cacheEnabled === (variant === 'warm'), 'CACHE_ENABLED does not match CACHE_VARIANT');
  const distribution = requiredCanonicalId(
    environment.CACHE_DISTRIBUTION || (profileId === 'same-key' ? 'same-key' : 'uniform'),
    'CACHE_DISTRIBUTION',
  );
  requireCondition(
    (profileId === 'same-key' && distribution === 'same-key')
      || (profileId === 'detail-pool' && (distribution === 'uniform' || distribution === 'hotset-80-20')),
    'CACHE_DISTRIBUTION does not match CAPSULE_TARGET',
  );
  const uniform = capsule.distributions.find((candidate) => candidate.id === 'detail-uniform');
  const hotset = capsule.distributions.find((candidate) => candidate.id === 'detail-hotset-80-20');
  requireCondition(
    uniform?.shape === 'UNIFORM'
      && uniform.parameters.totalKeys === pool.resourceIds.length
      && hotset?.shape === 'HOTSET'
      && hotset.parameters.totalKeys === pool.resourceIds.length
      && hotset.parameters.hotTrafficPercent === 80,
    'cache-detail-v1 distribution contract is invalid',
  );
  requireCondition(
    pool.expectedRows === pool.resourceIds.length
      && pool.resourceIds.length > 0
      && target.expectedRows === target.resourceIds.length
      && target.resourceIds.length > 0,
    'cache-detail-v1 target rows are invalid',
  );
  return {
    name: 'cache-detail',
    capsuleId: capsule.capsuleId,
    profileId,
    requestName: 'GET /api/v1/accommodations/{accommodationId}',
    resourceIds: target.resourceIds,
    distribution,
    hotKeys: distribution === 'hotset-80-20' ? hotset.parameters.hotKeys : 0,
    cacheVariant: variant,
    cacheEnabled,
  };
}

function buildIndexTarget(manifest, profileId) {
  const capsule = findExperimentCapsule(manifest, 'index-query-v1');
  requireCondition(
    capsule.mutability === 'READ_ONLY'
      && capsule.accountPool.capacity === 0
      && capsule.runtime.owner === 'AWS_LAB'
      && capsule.runtime.setup === 'rebuild-versioned-index'
      && capsule.runtime.resetPolicy === 'restore-index-snapshot',
    'index-query-v1 runtime contract is invalid',
  );
  requireCondition(
    ['search-broad', 'search-medium', 'search-narrow', 'search-no-hit'].includes(profileId),
    'CAPSULE_TARGET is not supported by index-query-v1',
  );
  const target = findCapsuleTarget(capsule, profileId);
  requireCondition(target.query?.kind === 'ACCOMMODATION_SEARCH_V1', 'index query is missing');
  return {
    name: 'index-query',
    capsuleId: capsule.capsuleId,
    profileId,
    requestName: 'GET /api/v1/search/accommodations',
    path: `/api/v1/search/accommodations?${encodeQuery(target.query)}`,
    expectedRows: target.expectedRows,
    expectedApiReportedTotal: Math.min(
      target.expectedRows,
      ELASTICSEARCH_DEFAULT_TOTAL_HITS_BOUND,
    ),
    query: target.query,
  };
}

export function buildDatasetReadTarget(manifest, name, profileId, environment = {}) {
  requireCondition(DATASET_READ_TARGETS.includes(name), 'TARGET is not a dataset read target');
  const parsedProfileId = requiredCanonicalId(profileId, 'CAPSULE_TARGET');
  return name === 'cache-detail'
    ? buildCacheTarget(manifest, parsedProfileId, environment)
    : buildIndexTarget(manifest, parsedProfileId);
}

export function selectCacheResourceId(target, iteration) {
  requireCondition(target.name === 'cache-detail', 'cache target is required');
  requireCondition(Number.isSafeInteger(iteration) && iteration >= 0, 'iteration must be nonnegative');
  if (target.distribution === 'same-key') {
    return target.resourceIds[0];
  }
  if (target.distribution === 'uniform') {
    return target.resourceIds[iteration % target.resourceIds.length];
  }
  const slot = iteration % 10;
  const cycle = Math.floor(iteration / 10);
  if (slot < 8) {
    return target.resourceIds[((cycle * 8) + slot) % target.hotKeys];
  }
  const coldKeys = target.resourceIds.length - target.hotKeys;
  requireCondition(coldKeys > 0, 'cache hotset requires cold keys');
  return target.resourceIds[target.hotKeys + (((cycle * 2) + slot - 8) % coldKeys)];
}

export function matchesDatasetReadContract(target, payload, expectedResourceId = undefined) {
  if (target.name === 'cache-detail') {
    return payload?.success === true && payload?.data?.id === expectedResourceId;
  }
  const rows = payload?.data?.stay_search_result_listing;
  const pageInfo = payload?.data?.page_info;
  const pageSize = 18;
  const expectedPageRows = Math.max(
    0,
    Math.min(pageSize, target.expectedApiReportedTotal - (target.query.page * pageSize)),
  );
  const ids = Array.isArray(rows) ? rows.map((row) => row?.id) : [];
  return payload?.success === true
    && Array.isArray(rows)
    && rows.length === expectedPageRows
    && ids.every((id) => Number.isSafeInteger(id) && id > 0)
    && new Set(ids).size === ids.length
    && pageInfo?.current_page === target.query.page
    && pageInfo?.total_elements === target.expectedApiReportedTotal
    && pageInfo?.total_pages === Math.ceil(target.expectedApiReportedTotal / pageSize);
}

export function buildCacheWarmCoverage(target, mode, data) {
  requireCondition(target.name === 'cache-detail', 'cache target is required');
  const required = mode === 'measure' && target.cacheVariant === 'warm';
  const rawCompleted = data?.metrics?.[CACHE_WARM_PREFETCH_METRIC]?.values?.count;
  const completedKeys = Number(rawCompleted || 0);
  const expectedKeys = required ? target.resourceIds.length : 0;
  const complete = !required || (
    Number.isSafeInteger(completedKeys)
      && completedKeys === expectedKeys
  );
  return {
    required,
    declaredTargetKeys: target.resourceIds.length,
    expectedKeys,
    completedKeys: Number.isFinite(completedKeys) ? completedKeys : 0,
    status: required ? (complete ? 'complete' : 'incomplete') : 'not-required',
  };
}

export function applyCacheWarmCoverageValidity(artifact, coverage) {
  requireCondition(
    artifact?.validity !== null
      && typeof artifact?.validity === 'object'
      && Array.isArray(artifact.validity.reasons),
    'traffic artifact validity is required',
  );
  if (coverage.status === 'incomplete') {
    artifact.validity.status = 'invalid';
    if (!artifact.validity.reasons.includes('cache-warm-coverage-incomplete')) {
      artifact.validity.reasons.push('cache-warm-coverage-incomplete');
    }
  }
  return artifact;
}

export function parseCacheResetReceipt(raw, expected) {
  let receipt;
  try {
    receipt = JSON.parse(raw);
  } catch (_) {
    throw new Error('CACHE_RESET_RECEIPT must contain JSON');
  }
  const keys = [
    'schemaVersion', 'manifestSha256', 'capsuleId', 'action', 'dbSizeAfter',
    'cacheEnabled', 'variant', 'runLabel', 'generatedAt',
  ];
  requireCondition(exactKeys(receipt, keys), 'CACHE_RESET_RECEIPT key set is invalid');
  requireCondition(
    receipt.schemaVersion === 1
      && receipt.manifestSha256 === expected.manifestSha256
      && receipt.capsuleId === 'cache-detail-v1'
      && receipt.action === 'flush-dedicated-cache-redis'
      && receipt.dbSizeAfter === 0
      && receipt.cacheEnabled === expected.cacheEnabled
      && receipt.variant === expected.variant
      && receipt.runLabel === expected.runLabel
      && typeof receipt.generatedAt === 'string'
      && Number.isFinite(Date.parse(receipt.generatedAt)),
    'CACHE_RESET_RECEIPT does not match the run',
  );
  return receipt;
}

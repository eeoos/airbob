const PREFIX = 'BENCHMARK_DATASET_MANIFEST';
const MAX_SAFE_INTEGER = 9_007_199_254_740_991;
const API_INTEGER_MAX = 2_147_483_647;
const AXES = ['FAN_OUT', 'VALUE_SKEW', 'RECENCY', 'SELECTIVITY', 'CONTENTION'];
const SHAPES = [
  'CARDINALITY_BUCKETS',
  'UNIFORM',
  'HOTSET',
  'RECENT_HEAVY',
  'SELECTIVITY_BUCKETS',
  'CONTENTION_RATIOS',
];
const REQUIRED_TABLES = [
  'accommodation',
  'accommodation_inventory_day',
  'member',
  'payment_transaction',
  'reservation',
  'review',
  'wishlist',
];
const REQUIRED_OBSERVED_DISTRIBUTIONS = [
  'accommodation-type-skew',
  'payment-recency',
  'reservation-guest-skew',
  'review-fanout',
  'wishlist-accommodation-skew',
  'wishlist-fanout',
];
const REQUIRED_SCOPE_RANGES = [
  'accommodation',
  'member',
  'payment',
  'payment-transaction',
  'reservation',
  'review',
  'wishlist',
  'wishlist-accommodation',
];
const READ_MODEL_TARGETS = {
  'review-hot': 'REVIEW_SUMMARY_V1',
  'review-median': 'REVIEW_SUMMARY_V1',
  'review-cold': 'REVIEW_SUMMARY_V1',
  'review-empty': 'REVIEW_SUMMARY_V1',
  'wishlist-hot': 'WISHLIST_PAGE_V1',
  'wishlist-median': 'WISHLIST_PAGE_V1',
  'wishlist-cold': 'WISHLIST_PAGE_V1',
  'wishlist-empty': 'WISHLIST_PAGE_V1',
  'wishlist-hot-deep': 'WISHLIST_PAGE_V1',
  'revenue-recent-1d': 'REVENUE_RANGE_V1',
  'revenue-recent-7d': 'REVENUE_RANGE_V1',
  'revenue-medium': 'REVENUE_RANGE_V1',
  'revenue-broad': 'REVENUE_RANGE_V1',
  'revenue-empty': 'REVENUE_RANGE_V1',
  'revenue-refund-boundary': 'REVENUE_RANGE_V1',
};

function fail(message) {
  throw new Error(`${PREFIX}.${message}`);
}

function requireCondition(condition, message) {
  if (!condition) {
    fail(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requireObject(value, path) {
  requireCondition(isObject(value), `${path} must be an object`);
}

function requireArray(value, path, allowEmpty = false) {
  requireCondition(
    Array.isArray(value) && (allowEmpty || value.length > 0),
    `${path} must be ${allowEmpty ? 'an array' : 'a non-empty array'}`,
  );
}

function requireExactKeys(value, keys, path) {
  requireObject(value, path);
  requireCondition(
    JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort()),
    `${path} has an invalid key set`,
  );
}

function requireCanonicalId(value, path) {
  requireCondition(
    typeof value === 'string' && /^[a-z0-9][a-z0-9-]*$/.test(value),
    `${path} must be a canonical lowercase id`,
  );
}

function requireCanonicalTable(value, path) {
  requireCondition(
    typeof value === 'string' && /^[a-z][a-z0-9_]*$/.test(value),
    `${path} must be a canonical table name`,
  );
}

function requireSafeInteger(value, path, minimum = -MAX_SAFE_INTEGER) {
  requireCondition(
    Number.isSafeInteger(value) && value >= minimum,
    `${path} must be a JS-safe integer of at least ${minimum}`,
  );
}

function requireNonNegativeInteger(value, path) {
  requireSafeInteger(value, path, 0);
}

function requirePositiveInteger(value, path) {
  requireSafeInteger(value, path, 1);
}

function requireUnique(values, path) {
  requireCondition(new Set(values).size === values.length, `${path} must be unique`);
}

function requireSha256(value, path) {
  requireCondition(
    typeof value === 'string' && /^[0-9a-f]{64}$/.test(value),
    `${path} must be lowercase SHA-256`,
  );
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function daysInMonth(year, month) {
  const values = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return values[month - 1] || 0;
}

function requireCanonicalDate(value, path) {
  requireCondition(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value), `${path} must be an ISO date`);
  const [year, month, day] = value.split('-').map(Number);
  requireCondition(month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month), `${path} is not a calendar date`);
}

function requireCanonicalDateTime(value, path) {
  requireCondition(
    typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(value),
    `${path} must be an ISO local date-time with seconds`,
  );
  requireCanonicalDate(value.slice(0, 10), `${path} date`);
  const [hour, minute, second] = value.slice(11).split(':').map(Number);
  requireCondition(hour <= 23 && minute <= 59 && second <= 59, `${path} time is invalid`);
}

function requireCanonicalInstant(value, path) {
  requireCondition(
    typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value),
    `${path} must be a canonical UTC instant`,
  );
  requireCanonicalDateTime(value.slice(0, -1), `${path} local component`);
}

function requireTimezone(value, path) {
  requireCondition(
    typeof value === 'string'
      && (value === 'UTC' || /^[A-Za-z][A-Za-z0-9._+-]*(\/[A-Za-z0-9._+-]+)+$/.test(value))
      && !value.includes('..'),
    `${path} must be a canonical IANA timezone`,
  );
}

function requireCanonicalEmail(value, path) {
  requireCondition(
    typeof value === 'string' && /^[a-z0-9][a-z0-9._+-]*@airbob\.cloud$/.test(value),
    `${path} must be a canonical airbob.cloud email`,
  );
}

function requireCanonicalIntegerMap(value, path, allowEmpty = true) {
  requireObject(value, path);
  const entries = Object.entries(value);
  requireCondition(allowEmpty || entries.length > 0, `${path} must not be empty`);
  entries.forEach(([key, count]) => {
    requireCanonicalId(key, `${path} key`);
    requireNonNegativeInteger(count, `${path}.${key}`);
  });
}

function requireObservationLabel(value, path) {
  requireCondition(
    typeof value === 'string'
      && /^[\x20-\x7e]+$/.test(value)
      && /[\x21-\x7e]/.test(value),
    `${path} must be a non-blank printable ASCII observation label`,
  );
}

function requireObservationIntegerMap(value, path, allowEmpty = true) {
  requireObject(value, path);
  const entries = Object.entries(value);
  requireCondition(allowEmpty || entries.length > 0, `${path} must not be empty`);
  entries.forEach(([key, count]) => {
    requireObservationLabel(key, `${path} key`);
    requireNonNegativeInteger(count, `${path}.${key}`);
  });
}

function sumSafe(values, path) {
  let sum = 0;
  values.forEach((value) => {
    sum += value;
    requireCondition(Number.isSafeInteger(sum), `${path} sum exceeds a JS-safe integer`);
  });
  return sum;
}

function validateObservedDistribution(distribution, index) {
  const path = `world.observedDistributions[${index}]`;
  requireExactKeys(distribution, [
    'id', 'axis', 'totalRows', 'distinctKeys', 'maxRowsPerKey', 'bucketUnit', 'buckets',
  ], path);
  requireCanonicalId(distribution.id, `${path}.id`);
  requireCondition(AXES.includes(distribution.axis), `${path}.axis is invalid`);
  requireNonNegativeInteger(distribution.totalRows, `${path}.totalRows`);
  requireNonNegativeInteger(distribution.distinctKeys, `${path}.distinctKeys`);
  requireNonNegativeInteger(distribution.maxRowsPerKey, `${path}.maxRowsPerKey`);
  requireCondition(
    (distribution.totalRows === 0 && distribution.distinctKeys === 0 && distribution.maxRowsPerKey === 0)
      || (distribution.totalRows > 0 && distribution.distinctKeys > 0 && distribution.maxRowsPerKey > 0),
    `${path} totals and key counts are inconsistent`,
  );
  requireCondition(['ROWS', 'KEYS'].includes(distribution.bucketUnit), `${path}.bucketUnit is invalid`);
  requireCanonicalIntegerMap(distribution.buckets, `${path}.buckets`, false);
  if (distribution.bucketUnit === 'ROWS') {
    requireCondition(
      sumSafe(Object.values(distribution.buckets), `${path}.buckets`) === distribution.totalRows,
      `${path} ROW buckets must partition totalRows`,
    );
  }
}

function validateProvenance(provenance, world) {
  const path = 'world.provenance';
  requireExactKeys(provenance, [
    'profileVersion', 'generatorVersion', 'prngAlgorithm', 'seedDerivation', 'globalSeed',
    'anchor', 'timezone', 'sourceInventorySha256', 'calibrationVersion',
    'calibrationSha256', 'specSha256', 'verificationPassed', 'assertionSha256',
  ], path);
  ['profileVersion', 'generatorVersion', 'prngAlgorithm', 'calibrationVersion']
    .forEach((key) => requireCanonicalId(provenance[key], `${path}.${key}`));
  requireCondition(
    typeof provenance.seedDerivation === 'string'
      && provenance.seedDerivation.trim().length > 0
      && !/[\u0000-\u001f\u007f]/.test(provenance.seedDerivation),
    `${path}.seedDerivation is invalid`,
  );
  requireSafeInteger(provenance.globalSeed, `${path}.globalSeed`);
  requireCondition(provenance.globalSeed === world.seed, `${path}.globalSeed must equal world.seed`);
  requireCanonicalInstant(provenance.anchor, `${path}.anchor`);
  requireTimezone(provenance.timezone, `${path}.timezone`);
  requireCondition(provenance.timezone === world.timezone, `${path}.timezone must equal world.timezone`);
  requireCondition(provenance.verificationPassed === true, `${path}.verificationPassed must be true`);
  ['sourceInventorySha256', 'calibrationSha256', 'specSha256', 'assertionSha256']
    .forEach((key) => requireSha256(provenance[key], `${path}.${key}`));
}

function validateScopedObservedDistribution(distribution, mapKey) {
  const path = `world.scopedObservedDistributions.${mapKey}`;
  requireExactKeys(distribution, [
    'id', 'totalRows', 'keyCount', 'zeroKeys', 'p50', 'p95', 'p99', 'maximum',
    'bucketUnit', 'buckets', 'shares', 'rankRows',
  ], path);
  requireCanonicalId(mapKey, 'world.scopedObservedDistributions key');
  requireCondition(distribution.id === mapKey, `${path}.id must equal its map key`);
  ['totalRows', 'keyCount', 'zeroKeys', 'p50', 'p95', 'p99', 'maximum']
    .forEach((key) => requireNonNegativeInteger(distribution[key], `${path}.${key}`));
  requireCondition(distribution.zeroKeys <= distribution.keyCount, `${path}.zeroKeys exceeds keyCount`);
  requireCondition(
    distribution.p50 <= distribution.p95
      && distribution.p95 <= distribution.p99
      && distribution.p99 <= distribution.maximum,
    `${path} percentiles are not monotonic`,
  );
  requireCondition(
    ['ROWS', 'KEYS', 'INTERSECTIONS'].includes(distribution.bucketUnit),
    `${path}.bucketUnit is invalid`,
  );
  requireObservationIntegerMap(distribution.buckets, `${path}.buckets`);
  requireCanonicalIntegerMap(distribution.rankRows, `${path}.rankRows`);
  requireObject(distribution.shares, `${path}.shares`);
  Object.entries(distribution.shares).forEach(([key, share]) => {
    requireObservationLabel(key, `${path}.shares key`);
    requireCondition(Number.isFinite(share) && share >= 0 && share <= 1, `${path}.shares.${key} is invalid`);
  });
}

function validateScopeRange(range, mapKey) {
  const path = `world.scopeRanges.${mapKey}`;
  requireExactKeys(range, ['id', 'minimumId', 'maximumId', 'rowCount'], path);
  requireCanonicalId(mapKey, 'world.scopeRanges key');
  requireCondition(range.id === mapKey, `${path}.id must equal its map key`);
  requirePositiveInteger(range.rowCount, `${path}.rowCount`);
  requirePositiveInteger(range.minimumId, `${path}.minimumId`);
  requirePositiveInteger(range.maximumId, `${path}.maximumId`);
  requireCondition(range.maximumId >= range.minimumId, `${path} bounds are inverted`);
  requireCondition(
    range.rowCount === range.maximumId - range.minimumId + 1,
    `${path} must be contiguous`,
  );
}

function validateWorld(world) {
  requireExactKeys(world, [
    'version', 'profile', 'seed', 'anchorTime', 'validUntil', 'timezone', 'flywayVersion',
    'claimScope', 'tableRows', 'observedDistributions', 'provenance',
    'scopedObservedDistributions', 'scopeRanges', 'fingerprints',
  ], 'world');
  requireCondition(world.version === 'world-v2', 'world.version must equal world-v2');
  requireCondition(['DEMO', 'PERF', 'LARGE'].includes(world.profile), 'world.profile is invalid');
  requireSafeInteger(world.seed, 'world.seed');
  requireCanonicalDateTime(world.anchorTime, 'world.anchorTime');
  requireCanonicalDateTime(world.validUntil, 'world.validUntil');
  requireCondition(world.validUntil > world.anchorTime, 'world.validUntil must be after anchorTime');
  requireTimezone(world.timezone, 'world.timezone');
  requireCondition(world.flywayVersion === 27, 'world.flywayVersion must equal 27');
  requireCondition(world.claimScope === 'controlled-synthetic-workload', 'world.claimScope is invalid');

  requireObject(world.tableRows, 'world.tableRows');
  Object.entries(world.tableRows).forEach(([table, count]) => {
    requireCanonicalTable(table, 'world.tableRows key');
    requireNonNegativeInteger(count, `world.tableRows.${table}`);
  });
  REQUIRED_TABLES.forEach((table) => requireCondition(
    Object.prototype.hasOwnProperty.call(world.tableRows, table),
    `world.tableRows is missing ${table}`,
  ));
  requireCondition(world.tableRows.accommodation_inventory_day === 0, 'world.tableRows.accommodation_inventory_day must equal 0');

  requireArray(world.observedDistributions, 'world.observedDistributions');
  world.observedDistributions.forEach(validateObservedDistribution);
  const observedIds = world.observedDistributions.map((distribution) => distribution.id);
  requireUnique(observedIds, 'world.observedDistributions ids');
  REQUIRED_OBSERVED_DISTRIBUTIONS.forEach((id) => requireCondition(
    observedIds.includes(id),
    `world.observedDistributions is missing ${id}`,
  ));

  validateProvenance(world.provenance, world);
  requireObject(world.scopedObservedDistributions, 'world.scopedObservedDistributions');
  requireCondition(Object.keys(world.scopedObservedDistributions).length > 0, 'world.scopedObservedDistributions must not be empty');
  Object.entries(world.scopedObservedDistributions)
    .forEach(([key, value]) => validateScopedObservedDistribution(value, key));
  requireExactKeys(world.scopeRanges, REQUIRED_SCOPE_RANGES, 'world.scopeRanges');
  Object.entries(world.scopeRanges).forEach(([key, value]) => validateScopeRange(value, key));
  requireObject(world.fingerprints, 'world.fingerprints');
  ['final-world', 'final-inventory', 'base-world'].forEach((key) => requireSha256(
    world.fingerprints[key],
    `world.fingerprints.${key}`,
  ));
  Object.entries(world.fingerprints).forEach(([key, value]) => {
    requireCanonicalId(key, 'world.fingerprints key');
    requireSha256(value, `world.fingerprints.${key}`);
  });
}

function validateBuckets(distribution, path, allowZero) {
  requireArray(distribution.buckets, `${path}.buckets`);
  let previous = -1;
  distribution.buckets.forEach((value, index) => {
    if (allowZero) {
      requireNonNegativeInteger(value, `${path}.buckets[${index}]`);
    } else {
      requirePositiveInteger(value, `${path}.buckets[${index}]`);
    }
    requireCondition(value > previous, `${path}.buckets must be strictly increasing`);
    previous = value;
  });
  requireExactKeys(distribution.parameters, [], `${path}.parameters`);
}

function validateDistribution(distribution, capsuleIndex, index) {
  const path = `capsules[${capsuleIndex}].distributions[${index}]`;
  requireExactKeys(distribution, ['id', 'axis', 'shape', 'buckets', 'parameters'], path);
  requireCanonicalId(distribution.id, `${path}.id`);
  requireCondition(AXES.includes(distribution.axis), `${path}.axis is invalid`);
  requireCondition(SHAPES.includes(distribution.shape), `${path}.shape is invalid`);
  requireArray(distribution.buckets, `${path}.buckets`, true);
  requireObject(distribution.parameters, `${path}.parameters`);
  if (distribution.shape === 'CARDINALITY_BUCKETS' || distribution.shape === 'SELECTIVITY_BUCKETS') {
    requireCondition(
      distribution.axis === (distribution.shape === 'CARDINALITY_BUCKETS' ? 'FAN_OUT' : 'SELECTIVITY'),
      `${path}.axis does not match ${distribution.shape}`,
    );
    validateBuckets(distribution, path, true);
    return;
  }
  if (distribution.shape === 'CONTENTION_RATIOS') {
    requireCondition(distribution.axis === 'CONTENTION', `${path}.axis must be CONTENTION`);
    validateBuckets(distribution, path, false);
    return;
  }
  requireCondition(distribution.buckets.length === 0, `${path}.buckets must be empty`);
  const contracts = {
    UNIFORM: ['VALUE_SKEW', ['totalKeys']],
    HOTSET: ['VALUE_SKEW', ['totalKeys', 'hotKeys', 'hotTrafficPercent']],
    RECENT_HEAVY: ['RECENCY', ['totalDays', 'recentDays', 'recentTrafficPercent']],
  };
  const [axis, keys] = contracts[distribution.shape];
  requireCondition(distribution.axis === axis, `${path}.axis must be ${axis}`);
  requireExactKeys(distribution.parameters, keys, `${path}.parameters`);
  keys.forEach((key) => requirePositiveInteger(distribution.parameters[key], `${path}.parameters.${key}`));
  if (distribution.shape === 'HOTSET') {
    requireCondition(distribution.parameters.hotKeys < distribution.parameters.totalKeys, `${path}.parameters.hotKeys must be less than totalKeys`);
    requireCondition(distribution.parameters.hotTrafficPercent <= 100, `${path}.parameters.hotTrafficPercent exceeds 100`);
  }
  if (distribution.shape === 'RECENT_HEAVY') {
    requireCondition(distribution.parameters.recentDays < distribution.parameters.totalDays, `${path}.parameters.recentDays must be less than totalDays`);
    requireCondition(distribution.parameters.recentTrafficPercent <= 100, `${path}.parameters.recentTrafficPercent exceeds 100`);
  }
}

function validateAccountPool(pool, capsuleIndex) {
  const path = `capsules[${capsuleIndex}].accountPool`;
  requireExactKeys(pool, ['capacity', 'emails'], path);
  requireNonNegativeInteger(pool.capacity, `${path}.capacity`);
  requireArray(pool.emails, `${path}.emails`, true);
  requireCondition(pool.emails.length === pool.capacity, `${path}.capacity must equal email count`);
  pool.emails.forEach((email, index) => requireCanonicalEmail(email, `${path}.emails[${index}]`));
  requireUnique(pool.emails, `${path}.emails`);
}

function validateTargetAccount(account, path) {
  requireExactKeys(account, ['memberId', 'email', 'role', 'status'], path);
  requirePositiveInteger(account.memberId, `${path}.memberId`);
  requireCanonicalEmail(account.email, `${path}.email`);
  requireCondition(['MEMBER', 'ADMIN'].includes(account.role), `${path}.role is invalid`);
  requireCondition(account.status === 'ACTIVE', `${path}.status must be ACTIVE`);
}

function validateReviewQuery(query, target, path) {
  requireExactKeys(query, ['kind', 'accommodationId'], path);
  requirePositiveInteger(query.accommodationId, `${path}.accommodationId`);
  requireCondition(
    target.resourceIds.length === 1 && target.resourceIds[0] === query.accommodationId,
    `${path} resource binding is invalid`,
  );
  requireCondition(!Object.prototype.hasOwnProperty.call(target, 'account'), `${path} must not declare an account`);
}

function validateWishlistQuery(query, target, path) {
  requireExactKeys(query, [
    'kind', 'memberId', 'size', 'lastId', 'lastCreatedAt', 'accommodationId', 'totalActiveRows',
  ], path);
  requirePositiveInteger(query.memberId, `${path}.memberId`);
  requirePositiveInteger(query.size, `${path}.size`);
  requireCondition(query.size <= 50, `${path}.size must be at most 50`);
  requireCondition((query.lastId === null) === (query.lastCreatedAt === null), `${path} cursor id/time must be paired`);
  if (query.lastId !== null) {
    requirePositiveInteger(query.lastId, `${path}.lastId`);
    requireCanonicalDateTime(query.lastCreatedAt, `${path}.lastCreatedAt`);
  }
  if (query.accommodationId !== null) {
    requirePositiveInteger(query.accommodationId, `${path}.accommodationId`);
  }
  requireNonNegativeInteger(query.totalActiveRows, `${path}.totalActiveRows`);
  requireCondition(target.expectedRows <= query.size, `${path} expectedRows exceeds page size`);
  requireCondition(
    target.resourceIds.length === 1 && target.resourceIds[0] === query.memberId,
    `${path} resource binding is invalid`,
  );
  validateTargetAccount(target.account, `${path} account`);
  requireCondition(
    target.account.memberId === query.memberId
      && target.account.role === 'MEMBER'
      && target.account.status === 'ACTIVE',
    `${path} must bind its ACTIVE MEMBER account directly`,
  );
  if (target.id === 'wishlist-hot-deep') {
    requireCondition(query.lastId !== null && query.lastCreatedAt !== null, `${path} deep cursor is required`);
  }
}

function validateRevenueQuery(query, target, path) {
  requireExactKeys(query, ['kind', 'from', 'to', 'dayBoundary'], path);
  requireCanonicalDate(query.from, `${path}.from`);
  requireCanonicalDate(query.to, `${path}.to`);
  requireCondition(query.to >= query.from, `${path} range is inverted`);
  requireCondition(query.dayBoundary === 'UTC', `${path}.dayBoundary must equal UTC`);
  requireCondition(target.resourceIds.length === 0, `${path} resourceIds must be empty`);
  validateTargetAccount(target.account, `${path} account`);
  requireCondition(
    target.account.role === 'ADMIN' && target.account.status === 'ACTIVE',
    `${path} must bind an ACTIVE ADMIN account`,
  );
}

function validateSearchQuery(query, target, path) {
  requireExactKeys(query, [
    'kind', 'destination', 'minPrice', 'maxPrice', 'adultOccupancy', 'childOccupancy',
    'infantOccupancy', 'petOccupancy', 'topLeftLat', 'topLeftLng', 'bottomRightLat',
    'bottomRightLng', 'page',
  ], path);
  requireCondition(query.destination === '', `${path}.destination must be empty`);
  ['minPrice', 'maxPrice', 'childOccupancy', 'infantOccupancy', 'petOccupancy', 'page']
    .forEach((key) => {
      requireNonNegativeInteger(query[key], `${path}.${key}`);
      requireCondition(query[key] <= API_INTEGER_MAX, `${path}.${key} exceeds an API integer`);
    });
  requirePositiveInteger(query.adultOccupancy, `${path}.adultOccupancy`);
  requireCondition(query.adultOccupancy <= API_INTEGER_MAX, `${path}.adultOccupancy exceeds an API integer`);
  requireCondition(query.adultOccupancy + query.childOccupancy <= API_INTEGER_MAX, `${path} guest occupancy overflows an API integer`);
  requireCondition(query.minPrice <= query.maxPrice, `${path} price range is invalid`);
  requireCondition(query.page <= 14, `${path}.page must be at most 14`);
  ['topLeftLat', 'topLeftLng', 'bottomRightLat', 'bottomRightLng']
    .forEach((key) => requireCondition(Number.isFinite(query[key]), `${path}.${key} must be finite`));
  requireCondition(
    query.topLeftLat >= -90 && query.topLeftLat <= 90
      && query.bottomRightLat >= -90 && query.bottomRightLat <= 90
      && query.topLeftLng >= -180 && query.topLeftLng <= 180
      && query.bottomRightLng >= -180 && query.bottomRightLng <= 180
      && query.topLeftLat > query.bottomRightLat
      && query.topLeftLng < query.bottomRightLng,
    `${path} bounds are invalid`,
  );
  requireCondition(!Object.prototype.hasOwnProperty.call(target, 'account'), `${path} must not declare an account`);
}

function queryIdentity(query, resourceIds) {
  const nullable = (value) => (value === null ? '<null>' : String(value));
  let fields;
  switch (query.kind) {
    case 'REVIEW_SUMMARY_V1':
      fields = [query.kind, query.accommodationId];
      break;
    case 'WISHLIST_PAGE_V1':
      fields = [query.kind, query.memberId, query.size, nullable(query.lastId), nullable(query.lastCreatedAt), nullable(query.accommodationId), query.totalActiveRows];
      break;
    case 'REVENUE_RANGE_V1':
      fields = [query.kind, query.from, query.to, query.dayBoundary];
      break;
    case 'ACCOMMODATION_SEARCH_V1':
      fields = Object.keys(query).sort().map((key) => `${key}=${query[key]}`);
      break;
    default:
      fields = [query.kind];
  }
  return `${fields.join('\u001f')}\u001f${resourceIds.join(',')}`;
}

function validateTarget(target, capsuleIndex, index) {
  const path = `capsules[${capsuleIndex}].targets[${index}]`;
  requireObject(target, path);
  const hasQuery = Object.prototype.hasOwnProperty.call(target, 'query');
  if (!hasQuery) {
    requireExactKeys(target, ['id', 'expectedRows', 'resourceIds'], path);
  } else {
    const accountRequired = target.query !== null
      && ['WISHLIST_PAGE_V1', 'REVENUE_RANGE_V1'].includes(target.query.kind);
    requireExactKeys(
      target,
      accountRequired
        ? ['id', 'expectedRows', 'resourceIds', 'query', 'expectedResultHash', 'account']
        : ['id', 'expectedRows', 'resourceIds', 'query', 'expectedResultHash'],
      path,
    );
  }
  requireCanonicalId(target.id, `${path}.id`);
  requireNonNegativeInteger(target.expectedRows, `${path}.expectedRows`);
  requireArray(target.resourceIds, `${path}.resourceIds`, true);
  target.resourceIds.forEach((id, resourceIndex) => requirePositiveInteger(id, `${path}.resourceIds[${resourceIndex}]`));
  requireUnique(target.resourceIds, `${path}.resourceIds`);
  if (!hasQuery) {
    return;
  }
  requireObject(target.query, `${path}.query`);
  requireSha256(target.expectedResultHash, `${path}.expectedResultHash`);
  switch (target.query.kind) {
    case 'REVIEW_SUMMARY_V1':
      validateReviewQuery(target.query, target, `${path}.query`);
      break;
    case 'WISHLIST_PAGE_V1':
      validateWishlistQuery(target.query, target, `${path}.query`);
      break;
    case 'REVENUE_RANGE_V1':
      validateRevenueQuery(target.query, target, `${path}.query`);
      break;
    case 'ACCOMMODATION_SEARCH_V1':
      validateSearchQuery(target.query, target, `${path}.query`);
      break;
    default:
      fail(`${path}.query.kind is invalid`);
  }
}

function validateReadModelCapsule(capsule, index) {
  const path = `capsules[${index}]`;
  requireCondition(capsule.accountPool.capacity === 5, `${path}.accountPool.capacity must equal 5`);
  const actualIds = capsule.targets.map((target) => target.id).sort();
  const expectedIds = Object.keys(READ_MODEL_TARGETS).sort();
  requireCondition(JSON.stringify(actualIds) === JSON.stringify(expectedIds), `${path}.targets has an invalid read-model target set`);
  capsule.targets.forEach((target) => requireCondition(
    target.query.kind === READ_MODEL_TARGETS[target.id],
    `${path}.target ${target.id} has the wrong query kind`,
  ));
  ['wishlist-hot', 'wishlist-median', 'wishlist-cold', 'wishlist-empty'].forEach((id) => {
    const query = capsule.targets.find((target) => target.id === id).query;
    requireCondition(query.lastId === null && query.lastCreatedAt === null, `${path}.${id} must be a first-page target`);
  });
  const boundEmails = capsule.targets
    .filter((target) => target.account)
    .map((target) => target.account.email);
  const uniqueBoundEmails = [...new Set(boundEmails)].sort();
  requireCondition(
    JSON.stringify(uniqueBoundEmails) === JSON.stringify([...capsule.accountPool.emails].sort()),
    `${path}.accountPool must exactly cover read-model accounts`,
  );
}

function validateCapsule(capsule, index) {
  const path = `capsules[${index}]`;
  requireExactKeys(capsule, [
    'capsuleId', 'schemaVersion', 'mutability', 'touchedTables', 'distributions',
    'accountPool', 'targets', 'runtime',
  ], path);
  requireCanonicalId(capsule.capsuleId, `${path}.capsuleId`);
  requireCondition(capsule.schemaVersion === 1, `${path}.schemaVersion must equal 1`);
  requireCondition(['READ_ONLY', 'RUN_LOCAL_WRITE'].includes(capsule.mutability), `${path}.mutability is invalid`);
  requireArray(capsule.touchedTables, `${path}.touchedTables`);
  capsule.touchedTables.forEach((table, tableIndex) => requireCanonicalTable(table, `${path}.touchedTables[${tableIndex}]`));
  requireUnique(capsule.touchedTables, `${path}.touchedTables`);
  requireArray(capsule.distributions, `${path}.distributions`);
  capsule.distributions.forEach((distribution, distributionIndex) => validateDistribution(distribution, index, distributionIndex));
  requireUnique(capsule.distributions.map((distribution) => distribution.id), `${path}.distribution ids`);
  validateAccountPool(capsule.accountPool, index);
  requireArray(capsule.targets, `${path}.targets`, true);
  capsule.targets.forEach((target, targetIndex) => validateTarget(target, index, targetIndex));
  requireUnique(capsule.targets.map((target) => target.id), `${path}.target ids`);
  const identities = capsule.targets.map((target) => (
    target.query
      ? queryIdentity(target.query, target.resourceIds)
      : `legacy\u001f${target.id}\u001f${target.resourceIds.join(',')}`
  ));
  requireUnique(identities, `${path}.target query identities`);
  requireCondition(capsule.targets.length > 0 || capsule.accountPool.capacity > 0, `${path} must declare targets or account capacity`);
  requireExactKeys(capsule.runtime, ['owner', 'setup', 'resetPolicy'], `${path}.runtime`);
  requireCondition(['AIRBOB_APPLICATION', 'K6_HARNESS', 'AWS_LAB'].includes(capsule.runtime.owner), `${path}.runtime.owner is invalid`);
  requireCanonicalId(capsule.runtime.setup, `${path}.runtime.setup`);
  requireCanonicalId(capsule.runtime.resetPolicy, `${path}.runtime.resetPolicy`);
  if (capsule.capsuleId === 'read-model-v2') {
    validateReadModelCapsule(capsule, index);
  }
}

export function parseBenchmarkDatasetManifest(raw) {
  let manifest;
  try {
    manifest = JSON.parse(raw);
  } catch (_) {
    fail('must contain valid JSON');
  }
  requireExactKeys(manifest, ['schemaVersion', 'datasetVersion', 'world', 'capsules', 'targetFingerprint'], 'root');
  requireCondition(manifest.schemaVersion === 2, 'schemaVersion must equal 2');
  requireCondition(manifest.datasetVersion === 'benchmark-dataset-v2', 'datasetVersion must equal benchmark-dataset-v2');
  validateWorld(manifest.world);
  requireArray(manifest.capsules, 'capsules');
  manifest.capsules.forEach(validateCapsule);
  requireUnique(manifest.capsules.map((capsule) => capsule.capsuleId), 'capsule ids');
  requireCondition(
    manifest.capsules.some((capsule) => capsule.capsuleId === 'read-model-v2'),
    'capsules must contain read-model-v2',
  );
  requireSha256(manifest.targetFingerprint, 'targetFingerprint');
  return manifest;
}

export function findExperimentCapsule(manifest, capsuleId) {
  const parsedId = String(capsuleId || '');
  const capsule = manifest.capsules.find((candidate) => candidate.capsuleId === parsedId);
  requireCondition(Boolean(capsule), `does not contain capsule ${parsedId}`);
  return capsule;
}

export function findCapsuleTarget(capsule, targetId) {
  const parsedId = String(targetId || '');
  const target = capsule.targets.find((candidate) => candidate.id === parsedId);
  requireCondition(Boolean(target), `capsule does not contain target ${parsedId}`);
  return target;
}

export function requireAccountCapacity(capsule, requiredCapacity) {
  requirePositiveInteger(requiredCapacity, 'required account capacity');
  requireCondition(
    capsule.accountPool.capacity >= requiredCapacity,
    `capsule ${capsule.capsuleId} requires at least ${requiredCapacity} accounts`,
  );
  return requiredCapacity;
}

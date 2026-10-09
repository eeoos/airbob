// A small, dataset-bound account list for V28 / Global B coupon experiments.
// CommonJS is supported by both Node (session preparation) and k6.
'use strict';

function parseCouponAccountManifest(raw) {
  const value = typeof raw === 'string' ? JSON.parse(raw) : raw;
  function requireValue(condition, message) {
    if (!condition) throw new Error('COUPON_ACCOUNT_MANIFEST: ' + message);
  }
  function exactKeys(object, keys) {
    return object && typeof object === 'object' && !Array.isArray(object)
      && Object.keys(object).sort().join(',') === keys.sort().join(',');
  }
  requireValue(exactKeys(value, ['schemaVersion', 'datasetVersion', 'sourceDataset', 'accountPool']), 'invalid fields');
  requireValue(value.schemaVersion === 1 && value.datasetVersion === 'coupon-accounts-v1', 'invalid version');
  const source = value.sourceDataset;
  requireValue(exactKeys(source, ['id', 'manifestSha256', 'flywayVersion']), 'invalid source dataset');
  requireValue(typeof source.id === 'string' && /^[a-z0-9][a-z0-9-]{0,127}$/.test(source.id), 'invalid dataset id');
  requireValue(typeof source.manifestSha256 === 'string' && /^[a-f0-9]{64}$/.test(source.manifestSha256), 'invalid dataset hash');
  requireValue(source.flywayVersion === 28, 'V28 dataset required');
  const pool = value.accountPool;
  requireValue(exactKeys(pool, ['capacity', 'emails']), 'invalid account pool');
  requireValue(Number.isSafeInteger(pool.capacity) && pool.capacity > 0 && pool.capacity <= 20000000,
    'invalid account capacity');
  requireValue(Array.isArray(pool.emails) && pool.emails.length === pool.capacity, 'capacity differs from account count');
  requireValue(pool.emails.every(email => typeof email === 'string'
    && /^[a-z0-9][a-z0-9._+-]*@[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$/.test(email)
    && email.length <= 254), 'invalid email');
  requireValue(new Set(pool.emails).size === pool.capacity, 'duplicate accounts');
  // The normalized shape also supports the existing session preparer.
  return {
    datasetVersion: value.datasetVersion,
    world: { version: source.id },
    sourceDataset: source,
    capsules: [{ capsuleId: 'coupon-accounts-v1', accountPool: pool }],
  };
}

module.exports = { parseCouponAccountManifest };

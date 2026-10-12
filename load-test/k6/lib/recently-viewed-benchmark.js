import { parseBenchmarkManifest } from './benchmark-manifest.js';

// Existing ETL manifests remain supported; local datasets need only the inputs used by this API.
export function parseRecentlyViewedBenchmarkManifest(raw) {
  let manifest;
  try {
    manifest = JSON.parse(raw);
  } catch (_) {
    throw new Error('BENCHMARK_MANIFEST must contain valid JSON');
  }
  if (manifest && manifest.datasetVersion === 'nplus1-v1') {
    return parseBenchmarkManifest(raw);
  }
  if (!manifest || manifest.datasetVersion !== 'recently-viewed-v1'
      || typeof manifest.datasetId !== 'string' || !manifest.datasetId.trim()
      || !manifest.account || typeof manifest.account.email !== 'string'
      || !/^[^\s@]+@[^\s@]+$/.test(manifest.account.email)) {
    throw new Error('Recently viewed manifest requires datasetVersion=recently-viewed-v1, datasetId and account.email');
  }
  const fixture = manifest.recentlyViewed;
  if (!fixture || !Number.isInteger(fixture.maxRows) || fixture.maxRows < 1 || fixture.maxRows > 100
      || !Array.isArray(fixture.accommodationIds) || fixture.accommodationIds.length !== fixture.maxRows
      || !fixture.accommodationIds.every((id) => Number.isSafeInteger(id) && id > 0)
      || new Set(fixture.accommodationIds).size !== fixture.maxRows) {
    throw new Error('Recently viewed manifest requires 1-100 unique positive accommodation IDs matching maxRows');
  }
  return manifest;
}

// The fixture PUT assigns the first ID the newest timestamp, one millisecond apart.
export function matchesRecentlyViewedFixture(payload, expectedIds) {
  const data = payload && payload.data;
  const rows = data && data.accommodations;
  return Array.isArray(rows)
    && rows.length === expectedIds.length
    && data.total_count === expectedIds.length
    && rows.every((row, index) => (
      row !== null
      && typeof row === 'object'
      && row.accommodation_id === expectedIds[index]
      && typeof row.viewed_at === 'string'
      && Number.isFinite(Date.parse(row.viewed_at))
      && (index === 0 || Date.parse(rows[index - 1].viewed_at) > Date.parse(row.viewed_at))
    ));
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(
      (key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`,
    ).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function assertRecentlyViewedPreflight(before, after, expectedIds) {
  for (const [variant, payload] of [['before', before], ['after', after]]) {
    if (!matchesRecentlyViewedFixture(payload, expectedIds)) {
      throw new Error(`${variant} response does not match the fixture IDs, order, count or viewed_at`);
    }
  }
  // Both GETs read the same fixture without resetting it, so viewed_at must also match.
  if (canonicalJson(before.data) !== canonicalJson(after.data)) {
    throw new Error('recently viewed before/after response data differ');
  }
}

import { check } from 'k6';
import {
  assertRecentlyViewedPreflight,
  matchesRecentlyViewedFixture,
  parseRecentlyViewedBenchmarkManifest,
} from '../lib/recently-viewed-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const ids = [251, 252];
const legacyManifest = open('./fixtures/nplus1-v1.json');

function localManifest() {
  return {
    datasetVersion: 'recently-viewed-v1',
    datasetId: 'local-test-dataset',
    account: { email: 'benchmark@example.test' },
    recentlyViewed: { maxRows: 2, accommodationIds: ids },
  };
}

function rejectsManifest(change) {
  const manifest = localManifest();
  change(manifest);
  try {
    parseRecentlyViewedBenchmarkManifest(JSON.stringify(manifest));
    return false;
  } catch (_) {
    return true;
  }
}

function payload() {
  return {
    data: {
      total_count: 2,
      accommodations: ids.map((id, index) => ({
        accommodation_id: id,
        accommodation_name: `숙소 ${id}`,
        thumbnail_url: null,
        viewed_at: new Date(Date.UTC(2026, 8, 6) - index).toISOString(),
        address_summary: { country: '대한민국', state: null, city: '서울', district: '마포구' },
        review_summary: index === 0 ? { total_count: 2, average_rating: 4.5 } : null,
        is_in_wishlist: index === 0,
      })),
    },
  };
}

function rejects(change) {
  const after = payload();
  change(after.data);
  try {
    assertRecentlyViewedPreflight(payload(), after, ids);
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const reorderedFields = payload();
  reorderedFields.data.accommodations = reorderedFields.data.accommodations.map(
    (row) => Object.fromEntries(Object.entries(row).reverse()),
  );
  assertRecentlyViewedPreflight(payload(), reorderedFields, ids);

  check(null, {
    'legacy ETL manifest remains supported': () => (
      parseRecentlyViewedBenchmarkManifest(legacyManifest).datasetVersion === 'nplus1-v1'
    ),
    'local manifest accepts a real test account without unrelated fixture fields': () => (
      parseRecentlyViewedBenchmarkManifest(JSON.stringify(localManifest())).recentlyViewed.maxRows === 2
    ),
    'local manifest requires dataset identity': () => rejectsManifest((manifest) => { delete manifest.datasetId; }),
    'local manifest rejects an unknown version': () => rejectsManifest((manifest) => { manifest.datasetVersion = 'unknown'; }),
    'local manifest rejects an invalid account': () => rejectsManifest((manifest) => { manifest.account.email = ''; }),
    'local manifest rejects duplicate IDs': () => rejectsManifest((manifest) => {
      manifest.recentlyViewed.accommodationIds = [251, 251];
    }),
    'local manifest rejects missing IDs': () => rejectsManifest((manifest) => { manifest.recentlyViewed.maxRows = 3; }),
    'local manifest rejects oversized fixtures': () => rejectsManifest((manifest) => {
      manifest.recentlyViewed = { maxRows: 101, accommodationIds: Array.from({ length: 101 }, (_, index) => index + 1) };
    }),
    'local manifest rejects unsafe integer IDs': () => rejectsManifest((manifest) => {
      manifest.recentlyViewed.accommodationIds = [251, Number.MAX_SAFE_INTEGER + 1];
    }),
    'valid fixture and equivalent payloads are accepted regardless of object key order': () => (
      matchesRecentlyViewedFixture(payload(), ids)
    ),
    'missing payload is rejected': () => !matchesRecentlyViewedFixture(null, ids),
    'wrong total count is rejected': () => rejects((data) => { data.total_count = 1; }),
    'missing rows are rejected': () => rejects((data) => { data.accommodations.pop(); }),
    'wrong IDs with the same row count are rejected': () => rejects((data) => {
      data.accommodations[0].accommodation_id = 999;
    }),
    'duplicate IDs are rejected': () => rejects((data) => {
      data.accommodations[1].accommodation_id = 251;
    }),
    'wrong ID order is rejected': () => rejects((data) => { data.accommodations.reverse(); }),
    'invalid timestamps are rejected': () => rejects((data) => {
      data.accommodations[0].viewed_at = 'invalid';
    }),
    'tied timestamps are rejected': () => rejects((data) => {
      data.accommodations[1].viewed_at = data.accommodations[0].viewed_at;
    }),
    'ascending timestamps are rejected': () => rejects((data) => {
      data.accommodations[1].viewed_at = '2026-09-07T00:00:00Z';
    }),
    'fixture timestamp changes between variants are rejected': () => rejects((data) => {
      data.accommodations[0].viewed_at = '2026-09-07T00:00:00Z';
    }),
    'different wishlist membership is rejected': () => rejects((data) => {
      data.accommodations[0].is_in_wishlist = false;
    }),
    'different review summaries are rejected': () => rejects((data) => {
      data.accommodations[0].review_summary.average_rating = 5;
    }),
    'different addresses are rejected': () => rejects((data) => {
      data.accommodations[0].address_summary.city = '부산';
    }),
  });
}

import { check } from 'k6';
import {
  parseBenchmarkManifest,
  parseRecentlyViewedSize,
} from '../lib/benchmark-manifest.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const validManifest = {
  datasetVersion: 'nplus1-v1',
  anchorTime: '2025-01-01T00:00:00',
  maxRequestedSize: 200,
  requiredRows: 201,
  account: { email: 'benchmark-nplus1@airbob.cloud' },
  review: {
    accommodationId: 901,
    publishedReviewCount: 201,
    reviewsWithImages: 201,
  },
  hostAccommodations: { expectedRows: 201, detailAccommodationId: 902 },
  guestReservations: { filterType: 'PAST', expectedRows: 201 },
  hostReservations: { filterType: 'PAST', expectedRows: 201 },
  wishlists: {
    expectedRows: 201,
    primaryWishlistId: 903,
    primaryWishlistAccommodationRows: 201,
  },
  recentlyViewed: {
    maxRows: 100,
    accommodationIds: Array.from({ length: 100 }, (_, index) => 1001 + index),
  },
};

function rejects(fn) {
  try {
    fn();
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const manifest = parseBenchmarkManifest(JSON.stringify(validManifest));

  check(manifest, {
    'valid manifest is accepted': (value) => value.requiredRows === 201,
    'size 20 is accepted': () => parseRecentlyViewedSize('20', manifest) === 20,
    'unsupported size is rejected': () => rejects(() => parseRecentlyViewedSize('101', manifest)),
    'duplicate recent ids are rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      recentlyViewed: {
        maxRows: 100,
        accommodationIds: Array(100).fill(1001),
      },
    }))),
    'wrong account is rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      account: { email: 'someone@example.com' },
    }))),
    'wrong guest reservation filter is rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      guestReservations: { filterType: 'UPCOMING', expectedRows: 201 },
    }))),
    'wrong host reservation filter is rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      hostReservations: { filterType: 'UPCOMING', expectedRows: 201 },
    }))),
    'malformed JSON is rejected': () => rejects(() => parseBenchmarkManifest('{')),
    'wrong dataset version is rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      datasetVersion: 'nplus1-v2',
    }))),
    'nonpositive ids are rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      review: { ...validManifest.review, accommodationId: 0 },
    }))),
    'inconsistent required rows are rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      requiredRows: 200,
    }))),
    'inconsistent expected rows are rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      wishlists: { ...validManifest.wishlists, expectedRows: 200 },
    }))),
    'wrong recent id count is rejected': () => rejects(() => parseBenchmarkManifest(JSON.stringify({
      ...validManifest,
      recentlyViewed: {
        maxRows: 100,
        accommodationIds: validManifest.recentlyViewed.accommodationIds.slice(0, 99),
      },
    }))),
  });
}

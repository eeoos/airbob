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

function manifestWithRequiredRows(requiredRows) {
  const maxRows = Math.min(100, requiredRows);

  return {
    ...validManifest,
    maxRequestedSize: requiredRows - 1,
    requiredRows,
    review: {
      ...validManifest.review,
      publishedReviewCount: requiredRows,
      reviewsWithImages: requiredRows,
    },
    hostAccommodations: {
      ...validManifest.hostAccommodations,
      expectedRows: requiredRows,
    },
    guestReservations: {
      ...validManifest.guestReservations,
      expectedRows: requiredRows,
    },
    hostReservations: {
      ...validManifest.hostReservations,
      expectedRows: requiredRows,
    },
    wishlists: {
      ...validManifest.wishlists,
      expectedRows: requiredRows,
      primaryWishlistAccommodationRows: requiredRows,
    },
    recentlyViewed: {
      maxRows,
      accommodationIds: Array.from({ length: maxRows }, (_, index) => 1001 + index),
    },
  };
}

function rejects(fn) {
  try {
    fn();
    return false;
  } catch (_) {
    return true;
  }
}

function errorMessage(fn) {
  try {
    fn();
    return undefined;
  } catch (error) {
    return error.message;
  }
}

function rejectsManifest(value) {
  return rejects(() => parseBenchmarkManifest(JSON.stringify(value)));
}

function withRecentlyViewedId(id) {
  return {
    ...validManifest,
    recentlyViewed: {
      ...validManifest.recentlyViewed,
      accommodationIds: [id, ...validManifest.recentlyViewed.accommodationIds.slice(1)],
    },
  };
}

export default function () {
  const manifest = parseBenchmarkManifest(JSON.stringify(validManifest));
  const smallManifest = parseBenchmarkManifest(JSON.stringify(manifestWithRequiredRows(10)));
  const mediumManifest = parseBenchmarkManifest(JSON.stringify(manifestWithRequiredRows(50)));

  check(manifest, {
    'valid manifest is accepted': (value) => value.requiredRows === 201,
    'parsed manifest is returned unchanged': (value) => (
      JSON.stringify(value) === JSON.stringify(validManifest)
    ),
    'size 1 is accepted': () => parseRecentlyViewedSize('1', manifest) === 1,
    'size 20 is accepted': () => parseRecentlyViewedSize('20', manifest) === 20,
    'size 50 is accepted': () => parseRecentlyViewedSize('50', manifest) === 50,
    'size 100 is accepted': () => parseRecentlyViewedSize('100', manifest) === 100,
    'blank size defaults to 20': () => parseRecentlyViewedSize('', manifest) === 20,
    'whitespace size defaults to 20': () => parseRecentlyViewedSize('   ', manifest) === 20,
    'undefined size defaults to 20': () => parseRecentlyViewedSize(undefined, manifest) === 20,
    'small manifest blank size falls back to largest supported': () => (
      parseRecentlyViewedSize('', smallManifest) === 1
    ),
    'small manifest undefined size falls back to largest supported': () => (
      parseRecentlyViewedSize(undefined, smallManifest) === 1
    ),
    'small manifest filters unsupported sizes from the error': () => (
      errorMessage(() => parseRecentlyViewedSize('20', smallManifest))
        === 'RECENTLY_VIEWED_SIZE must be one of: 1'
    ),
    'medium manifest accepts filtered size 50': () => (
      parseRecentlyViewedSize('50', mediumManifest) === 50
    ),
    'medium manifest lists only its allowed sizes': () => (
      errorMessage(() => parseRecentlyViewedSize('100', mediumManifest))
        === 'RECENTLY_VIEWED_SIZE must be one of: 1, 20, 50'
    ),
    'full manifest lists every allowed size': () => (
      errorMessage(() => parseRecentlyViewedSize('101', manifest))
        === 'RECENTLY_VIEWED_SIZE must be one of: 1, 20, 50, 100'
    ),
    'duplicate recent ids are rejected': () => rejectsManifest({
      ...validManifest,
      recentlyViewed: {
        maxRows: 100,
        accommodationIds: Array(100).fill(1001),
      },
    }),
    'wrong account is rejected': () => rejectsManifest({
      ...validManifest,
      account: { email: 'someone@example.com' },
    }),
    'wrong guest reservation filter is rejected': () => rejectsManifest({
      ...validManifest,
      guestReservations: { filterType: 'UPCOMING', expectedRows: 201 },
    }),
    'wrong host reservation filter is rejected': () => rejectsManifest({
      ...validManifest,
      hostReservations: { filterType: 'UPCOMING', expectedRows: 201 },
    }),
    'malformed JSON error is sanitized': () => (
      errorMessage(() => parseBenchmarkManifest('{'))
        === 'BENCHMARK_MANIFEST must contain valid JSON'
    ),
    'wrong dataset version is rejected': () => rejectsManifest({
      ...validManifest,
      datasetVersion: 'nplus1-v2',
    }),
    'review accommodation id must be a positive integer': () => [0, 901.5].every((id) => (
      rejectsManifest({
        ...validManifest,
        review: { ...validManifest.review, accommodationId: id },
      })
    )),
    'detail accommodation id must be a positive integer': () => [0, 902.5].every((id) => (
      rejectsManifest({
        ...validManifest,
        hostAccommodations: {
          ...validManifest.hostAccommodations,
          detailAccommodationId: id,
        },
      })
    )),
    'primary wishlist id must be a positive integer': () => [0, 903.5].every((id) => (
      rejectsManifest({
        ...validManifest,
        wishlists: { ...validManifest.wishlists, primaryWishlistId: id },
      })
    )),
    'recent accommodation ids must be positive integers': () => [0, 1001.5].every((id) => (
      rejectsManifest(withRecentlyViewedId(id))
    )),
    'max requested size must be an integer': () => rejectsManifest({
      ...validManifest,
      maxRequestedSize: 200.5,
    }),
    'required rows must be an integer': () => rejectsManifest({
      ...validManifest,
      requiredRows: 201.5,
    }),
    'published review count must be an integer': () => rejectsManifest({
      ...validManifest,
      review: { ...validManifest.review, publishedReviewCount: 201.5 },
    }),
    'reviews with images count must be an integer': () => rejectsManifest({
      ...validManifest,
      review: { ...validManifest.review, reviewsWithImages: 201.5 },
    }),
    'host accommodation rows must be an integer': () => rejectsManifest({
      ...validManifest,
      hostAccommodations: { ...validManifest.hostAccommodations, expectedRows: 201.5 },
    }),
    'guest reservation rows must be an integer': () => rejectsManifest({
      ...validManifest,
      guestReservations: { ...validManifest.guestReservations, expectedRows: 201.5 },
    }),
    'host reservation rows must be an integer': () => rejectsManifest({
      ...validManifest,
      hostReservations: { ...validManifest.hostReservations, expectedRows: 201.5 },
    }),
    'wishlist rows must be an integer': () => rejectsManifest({
      ...validManifest,
      wishlists: { ...validManifest.wishlists, expectedRows: 201.5 },
    }),
    'primary wishlist accommodation rows must be an integer': () => rejectsManifest({
      ...validManifest,
      wishlists: {
        ...validManifest.wishlists,
        primaryWishlistAccommodationRows: 201.5,
      },
    }),
    'recently viewed max rows must be an integer': () => rejectsManifest({
      ...validManifest,
      recentlyViewed: { ...validManifest.recentlyViewed, maxRows: 100.5 },
    }),
    'required rows must equal max requested size plus one in isolation': () => rejectsManifest({
      ...validManifest,
      maxRequestedSize: 199,
    }),
    'published review count may exceed required rows': () => (
      parseBenchmarkManifest(JSON.stringify({
        ...validManifest,
        review: { ...validManifest.review, publishedReviewCount: 202 },
      })).review.publishedReviewCount === 202
    ),
    'published review count cannot be below required rows': () => rejectsManifest({
      ...validManifest,
      review: { ...validManifest.review, publishedReviewCount: 200 },
    }),
    'reviews with images must match required rows': () => rejectsManifest({
      ...validManifest,
      review: { ...validManifest.review, reviewsWithImages: 202 },
    }),
    'host accommodation rows must match required rows': () => rejectsManifest({
      ...validManifest,
      hostAccommodations: { ...validManifest.hostAccommodations, expectedRows: 202 },
    }),
    'guest reservation rows must match required rows': () => rejectsManifest({
      ...validManifest,
      guestReservations: { ...validManifest.guestReservations, expectedRows: 202 },
    }),
    'host reservation rows must match required rows': () => rejectsManifest({
      ...validManifest,
      hostReservations: { ...validManifest.hostReservations, expectedRows: 202 },
    }),
    'wishlist rows must match required rows': () => rejectsManifest({
      ...validManifest,
      wishlists: { ...validManifest.wishlists, expectedRows: 202 },
    }),
    'primary wishlist accommodation rows must match required rows': () => rejectsManifest({
      ...validManifest,
      wishlists: {
        ...validManifest.wishlists,
        primaryWishlistAccommodationRows: 202,
      },
    }),
    'recent max rows must match required rows cap in isolation': () => rejectsManifest({
      ...validManifest,
      recentlyViewed: {
        maxRows: 99,
        accommodationIds: validManifest.recentlyViewed.accommodationIds.slice(0, 99),
      },
    }),
    'wrong recent id count is rejected': () => rejectsManifest({
      ...validManifest,
      recentlyViewed: {
        maxRows: 100,
        accommodationIds: validManifest.recentlyViewed.accommodationIds.slice(0, 99),
      },
    }),
  });
}

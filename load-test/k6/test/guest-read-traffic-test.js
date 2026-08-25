import { check } from 'k6';

import {
  buildGuestTarget,
  GUEST_TARGETS,
  guestManifestGaps,
  matchesGuestTargetContract,
} from '../traffic/guest-read.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

const manifest = {
  datasetVersion: 'nplus1-v1',
  maxRequestedSize: 200,
  requiredRows: 201,
  review: { accommodationId: 901, publishedReviewCount: 201, reviewsWithImages: 201 },
  hostAccommodations: { expectedRows: 201, detailAccommodationId: 902 },
  guestReservations: { filterType: 'PAST', expectedRows: 201 },
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

const expectedGuestTargets = [
  'accommodation-detail',
  'review-list',
  'review-summary',
  'guest-reservations',
  'wishlist-list',
  'wishlist-accommodations',
  'recently-viewed',
];

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function cursorPayload(collection, rows, hasNext) {
  return {
    success: true,
    data: {
      [collection]: rows,
      page_info: {
        current_size: rows.length,
        has_next: hasNext,
        next_cursor: hasNext ? 'cursor' : null,
      },
    },
  };
}

export default function () {
  const detail = buildGuestTarget(manifest, 'accommodation-detail', {});
  const reviews = buildGuestTarget(manifest, 'review-list', { PAGE_SIZE: '20' });
  const reviewSummary = buildGuestTarget(manifest, 'review-summary', {});
  const reservations = buildGuestTarget(manifest, 'guest-reservations', { PAGE_SIZE: '50' });
  const wishlists = buildGuestTarget(manifest, 'wishlist-list', { PAGE_SIZE: '20' });
  const wishlistAccommodations = buildGuestTarget(
    manifest,
    'wishlist-accommodations',
    { PAGE_SIZE: '50' },
  );
  const recentlyViewed = buildGuestTarget(
    manifest,
    'recently-viewed',
    { RECENTLY_VIEWED_SIZE: '20' },
  );
  const reviewRows = Array.from({ length: 20 }, (_, index) => ({
    id: index + 1,
    images: [{ id: index + 1 }],
  }));
  const reservationRows = Array.from({ length: 50 }, (_, index) => ({
    reservation_uid: `reservation-${index}`,
    check_out_date: '2024-12-31',
    accommodation: { id: index + 1 },
  }));
  const wishlistRows = Array.from({ length: 20 }, (_, index) => ({
    id: index + 1,
    name: `wishlist-${index}`,
  }));
  const wishlistAccommodationRows = Array.from({ length: 50 }, (_, index) => ({
    id: index + 1,
    accommodation: { id: index + 100 },
  }));
  const recentlyViewedRows = Array.from({ length: 20 }, (_, index) => ({ id: index + 1000 }));
  const gaps = guestManifestGaps(manifest);

  check(null, {
    'detail target uses only the manifest accommodation id': () => (
      detail.path === '/api/v1/accommodations/902'
        && detail.requestName === 'GET /api/v1/accommodations/{accommodationId}'
    ),
    'review targets use the manifest review id and row contract': () => (
      reviews.path === '/api/v1/accommodations/901/reviews?size=20&sortType=LATEST'
        && reviews.expectedRows === 20
        && reviewSummary.path === '/api/v1/accommodations/901/reviews/summary'
        && reviewSummary.expectedRows === 201
    ),
    'authenticated targets use manifest filters and ids': () => (
      reservations.path === '/api/v1/profile/guest/reservations?size=50&filterType=PAST'
        && wishlists.path === '/api/v1/members/wishlists?size=20'
        && wishlistAccommodations.path
          === '/api/v1/members/wishlists/accommodations/903?size=50'
    ),
    'recently viewed size is bounded by the manifest': () => (
      recentlyViewed.path === '/api/v1/members/recently-viewed'
        && recentlyViewed.expectedRows === 20
    ),
    'guest traffic exposes exactly the reviewed read target allowlist': () => (
      GUEST_TARGETS.length === expectedGuestTargets.length
        && expectedGuestTargets.every((target) => GUEST_TARGETS.includes(target))
    ),
    'mixed, search, inventory-dependent, mutation, and unknown targets are rejected': () => (
      [
        'mixed',
        'search',
        'accommodation-availability',
        'reservation-quote',
        'reservation-create',
        'reservation-checkout',
        'unknown',
      ].every((target) => rejects(() => (
        buildGuestTarget(manifest, target, {})
      )))
    ),
    'unsupported or oversized page sizes are rejected': () => (
      ['0', '51', '100'].every((size) => rejects(() => (
        buildGuestTarget(manifest, 'review-list', { PAGE_SIZE: size })
      )))
    ),
    'detail contract validates the exact manifest id': () => (
      matchesGuestTargetContract(detail, { success: true, data: { id: 902 } }, '2025-01-01')
        && !matchesGuestTargetContract(detail, { success: true, data: { id: 999 } }, '2025-01-01')
    ),
    'review list contract validates rows and cursor': () => (
      matchesGuestTargetContract(
        reviews,
        cursorPayload('reviews', reviewRows, true),
        '2025-01-01',
      )
        && !matchesGuestTargetContract(
          reviews,
          cursorPayload('reviews', reviewRows.slice(1), true),
          '2025-01-01',
        )
        && !matchesGuestTargetContract(
          reviews,
          cursorPayload('reviews', reviewRows, false),
          '2025-01-01',
        )
    ),
    'review summary contract validates total and rating range': () => (
      matchesGuestTargetContract(reviewSummary, {
        success: true,
        data: { total_count: 201, average_rating: 4.5 },
      }, '2025-01-01')
        && !matchesGuestTargetContract(reviewSummary, {
          success: true,
          data: { total_count: 201, average_rating: 6 },
        }, '2025-01-01')
    ),
    'guest reservation contract validates past rows and omits host status': () => (
      matchesGuestTargetContract(
        reservations,
        cursorPayload('reservations', reservationRows, true),
        '2025-01-01',
      )
        && !matchesGuestTargetContract(
          reservations,
          cursorPayload('reservations', [
            { ...reservationRows[0], status: 'CONFIRMED' },
            ...reservationRows.slice(1),
          ], true),
          '2025-01-01',
        )
    ),
    'wishlist contracts validate every expected row': () => (
      matchesGuestTargetContract(
        wishlists,
        cursorPayload('wishlists', wishlistRows, true),
        '2025-01-01',
      )
        && matchesGuestTargetContract(
          wishlistAccommodations,
          cursorPayload('wishlist_accommodations', wishlistAccommodationRows, true),
          '2025-01-01',
        )
    ),
    'recently viewed contract rejects a shorter response': () => (
      matchesGuestTargetContract(recentlyViewed, {
        success: true,
        data: { accommodations: recentlyViewedRows, total_count: 20 },
      }, '2025-01-01')
        && !matchesGuestTargetContract(recentlyViewed, {
          success: true,
          data: { accommodations: recentlyViewedRows.slice(1), total_count: 19 },
        }, '2025-01-01')
    ),
    'missing representative-release fields are a public gap inventory': () => (
      gaps.includes('account-concurrency-capacity')
        && gaps.includes('dataset-run-id')
        && gaps.includes('canonical-payload-digest')
        && gaps.includes('etl-commit')
        && gaps.includes('flyway-version-checksums')
        && gaps.includes('evaluation-time-and-expiry')
        && gaps.includes('target-cardinality-popularity')
    ),
  });
}

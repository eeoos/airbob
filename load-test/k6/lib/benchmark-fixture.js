import http from 'k6/http';
import { fail } from 'k6';

function failTarget(response, label) {
  fail(`${label} failed with HTTP ${response.status}`);
}

function requireTarget(response, label, predicate) {
  if (response.status !== 200) {
    failTarget(response, label);
  }

  let payload;
  try {
    payload = response.json();
  } catch (_) {
    failTarget(response, label);
  }

  let matchesContract = false;
  try {
    matchesContract = Boolean(predicate(payload));
  } catch (_) {
    matchesContract = false;
  }

  if (!matchesContract) {
    failTarget(response, label);
  }
}

export function authenticatedParams(sessionId, tags = {}) {
  return {
    cookies: { SESSION_ID: sessionId },
    tags,
  };
}

export function loginBenchmarkAccount({ baseUrl, email, password }) {
  const response = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: {
        phase: 'setup',
        name: 'POST /api/v1/auth/login',
      },
    },
  );

  const sessionId = response.cookies.SESSION_ID?.[0]?.value;
  if (response.status !== 200 || !sessionId) {
    fail(`benchmark login failed with HTTP ${response.status}`);
  }
  return sessionId;
}

export function smokeCheckManifestTargets({ baseUrl, sessionId, manifest }) {
  const today = new Date().toISOString().slice(0, 10);

  const detailResponse = http.get(
    `${baseUrl}/api/v1/accommodations/${manifest.hostAccommodations.detailAccommodationId}`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/accommodations/{accommodationId}',
      }),
    },
  );
  requireTarget(detailResponse, 'accommodation detail', (payload) => (
    payload.data.id === manifest.hostAccommodations.detailAccommodationId
  ));

  const reviewResponse = http.get(
    `${baseUrl}/api/v1/accommodations/${manifest.review.accommodationId}/reviews?size=1`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/accommodations/{accommodationId}/reviews',
      }),
    },
  );
  requireTarget(reviewResponse, 'reviews', (payload) => (
    payload.data.reviews.length === 1
      && payload.data.reviews[0].images.length >= 1
      && payload.data.page_info.current_size === 1
      && payload.data.page_info.has_next === true
  ));

  const hostAccommodationResponse = http.get(
    `${baseUrl}/api/v1/profile/host/accommodations?size=1&status=PUBLISHED`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/profile/host/accommodations',
      }),
    },
  );
  requireTarget(hostAccommodationResponse, 'host accommodations', (payload) => (
    payload.data.accommodations.length === 1
      && payload.data.accommodations[0].status === 'PUBLISHED'
      && payload.data.page_info.current_size === 1
      && payload.data.page_info.has_next === true
  ));

  const guestReservationResponse = http.get(
    `${baseUrl}/api/v1/profile/guest/reservations?size=1&filterType=PAST`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/profile/guest/reservations',
      }),
    },
  );
  requireTarget(guestReservationResponse, 'guest reservations', (payload) => (
    payload.data.reservations.length === 1
      && !Object.prototype.hasOwnProperty.call(payload.data.reservations[0], 'status')
      && payload.data.reservations[0].check_out_date < today
      && payload.data.reservations[0].accommodation.id > 0
      && payload.data.page_info.current_size === 1
      && payload.data.page_info.has_next === true
  ));

  const hostReservationResponse = http.get(
    `${baseUrl}/api/v1/profile/host/reservations?size=1&filterType=PAST`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/profile/host/reservations',
      }),
    },
  );
  requireTarget(hostReservationResponse, 'host reservations', (payload) => (
    payload.data.reservations.length === 1
      && payload.data.reservations[0].status === 'CONFIRMED'
      && payload.data.reservations[0].check_out_date < today
      && payload.data.reservations[0].accommodation.id > 0
      && payload.data.page_info.current_size === 1
      && payload.data.page_info.has_next === true
  ));

  const wishlistResponse = http.get(
    `${baseUrl}/api/v1/members/wishlists?size=1`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/members/wishlists',
      }),
    },
  );
  requireTarget(wishlistResponse, 'wishlists', (payload) => (
    payload.data.wishlists.length === 1
      && payload.data.wishlists[0].id > 0
      && payload.data.page_info.current_size === 1
      && payload.data.page_info.has_next === true
  ));

  const wishlistAccommodationResponse = http.get(
    `${baseUrl}/api/v1/members/wishlists/accommodations/${manifest.wishlists.primaryWishlistId}?size=1`,
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'GET /api/v1/members/wishlists/accommodations/{wishlistId}',
      }),
    },
  );
  requireTarget(
    wishlistAccommodationResponse,
    'primary wishlist accommodations',
    (payload) => (
      payload.data.wishlist_accommodations.length === 1
        && payload.data.wishlist_accommodations[0].accommodation.id > 0
        && payload.data.page_info.current_size === 1
        && payload.data.page_info.has_next === true
    ),
  );
}

export function resetRecentlyViewed({
  baseUrl,
  sessionId,
  accommodationIds,
  datasetSize,
}) {
  const response = http.put(
    `${baseUrl}/api/v2/members/recently-viewed/fixture`,
    buildRecentlyViewedFixtureBody({ accommodationIds, datasetSize }),
    {
      ...authenticatedParams(sessionId, {
        phase: 'setup',
        name: 'PUT /api/v2/members/recently-viewed/fixture',
      }),
      headers: { 'Content-Type': 'application/json' },
    },
  );

  if (response.status !== 200) {
    fail(`recently viewed fixture replacement failed with HTTP ${response.status}`);
  }
}

export function buildRecentlyViewedFixtureBody({ accommodationIds, datasetSize }) {
  return JSON.stringify({
    accommodation_ids: accommodationIds.slice(0, datasetSize),
  });
}

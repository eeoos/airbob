import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

import { authenticatedParams } from '../lib/benchmark-fixture.js';
import { parseRecentlyViewedSize } from '../lib/benchmark-manifest.js';
import {
  buildTrafficOptions,
  parseTrafficRunConfig,
  summarizeTrafficMetrics,
} from '../lib/traffic-benchmark.js';

export const GUEST_TARGETS = [
  'accommodation-detail',
  'review-list',
  'review-summary',
  'guest-reservations',
  'wishlist-list',
  'wishlist-accommodations',
  'recently-viewed',
];
const PAGE_SIZES = [1, 20, 50];

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function requiredPageSize(environment, maximumRows) {
  const raw = environment.PAGE_SIZE;
  requireCondition(typeof raw === 'string' && /^[1-9][0-9]*$/.test(raw), 'PAGE_SIZE is required');
  const value = Number(raw);
  requireCondition(
    PAGE_SIZES.includes(value) && value <= maximumRows,
    'PAGE_SIZE is not supported by this manifest target',
  );
  return value;
}

function cursorContract(payload, collection, target, rowPredicate) {
  const rows = payload?.data?.[collection];
  const pageInfo = payload?.data?.page_info;
  return payload?.success === true
    && Array.isArray(rows)
    && rows.length === target.expectedRows
    && rows.every(rowPredicate)
    && pageInfo?.current_size === target.expectedRows
    && pageInfo?.has_next === target.sourceRows > target.expectedRows;
}

export function buildGuestTarget(manifest, name, environment) {
  requireCondition(GUEST_TARGETS.includes(name), 'guest target is not allowlisted');

  if (name === 'accommodation-detail') {
    const id = manifest.hostAccommodations.detailAccommodationId;
    return {
      name,
      path: `/api/v1/accommodations/${id}`,
      requestName: 'GET /api/v1/accommodations/{accommodationId}',
      expectedId: id,
      authenticated: false,
      gaps: ['accommodation-detail-response-invariant'],
    };
  }
  if (name === 'review-list') {
    const expectedRows = requiredPageSize(environment, manifest.review.publishedReviewCount);
    return {
      name,
      path: `/api/v1/accommodations/${manifest.review.accommodationId}/reviews?size=${expectedRows}&sortType=LATEST`,
      requestName: 'GET /api/v1/accommodations/{accommodationId}/reviews',
      expectedRows,
      sourceRows: manifest.review.publishedReviewCount,
      authenticated: false,
      gaps: [],
    };
  }
  if (name === 'review-summary') {
    return {
      name,
      path: `/api/v1/accommodations/${manifest.review.accommodationId}/reviews/summary`,
      requestName: 'GET /api/v1/accommodations/{accommodationId}/reviews/summary',
      expectedRows: manifest.review.publishedReviewCount,
      authenticated: false,
      gaps: ['review-summary-expected-rating'],
    };
  }
  if (name === 'guest-reservations') {
    const expectedRows = requiredPageSize(environment, manifest.guestReservations.expectedRows);
    return {
      name,
      path: `/api/v1/profile/guest/reservations?size=${expectedRows}&filterType=${manifest.guestReservations.filterType}`,
      requestName: 'GET /api/v1/profile/guest/reservations',
      expectedRows,
      sourceRows: manifest.guestReservations.expectedRows,
      authenticated: true,
      gaps: ['reservation-evaluation-time'],
    };
  }
  if (name === 'wishlist-list') {
    const expectedRows = requiredPageSize(environment, manifest.wishlists.expectedRows);
    return {
      name,
      path: `/api/v1/members/wishlists?size=${expectedRows}`,
      requestName: 'GET /api/v1/members/wishlists',
      expectedRows,
      sourceRows: manifest.wishlists.expectedRows,
      authenticated: true,
      gaps: ['wishlist-row-identities'],
    };
  }
  if (name === 'wishlist-accommodations') {
    const expectedRows = requiredPageSize(
      environment,
      manifest.wishlists.primaryWishlistAccommodationRows,
    );
    return {
      name,
      path: `/api/v1/members/wishlists/accommodations/${manifest.wishlists.primaryWishlistId}?size=${expectedRows}`,
      requestName: 'GET /api/v1/members/wishlists/accommodations/{wishlistId}',
      expectedRows,
      sourceRows: manifest.wishlists.primaryWishlistAccommodationRows,
      authenticated: true,
      gaps: ['wishlist-accommodation-row-identities'],
    };
  }

  const expectedRows = parseRecentlyViewedSize(environment.RECENTLY_VIEWED_SIZE, manifest);
  return {
    name,
    path: '/api/v1/members/recently-viewed',
    requestName: 'GET /api/v1/members/recently-viewed',
    expectedRows,
    authenticated: true,
    gaps: ['recently-viewed-reset-receipt'],
  };
}

export function matchesGuestTargetContract(target, payload, evaluationDate) {
  try {
    if (target.name === 'accommodation-detail') {
      return payload?.success === true && payload?.data?.id === target.expectedId;
    }
    if (target.name === 'review-list') {
      return cursorContract(payload, 'reviews', target, (review) => (
        Number.isInteger(review.id)
          && review.id > 0
          && Array.isArray(review.images)
          && review.images.length > 0
      ));
    }
    if (target.name === 'review-summary') {
      const rating = payload?.data?.average_rating;
      return payload?.success === true
        && payload?.data?.total_count === target.expectedRows
        && Number.isFinite(rating)
        && rating >= 0
        && rating <= 5;
    }
    if (target.name === 'guest-reservations') {
      return cursorContract(payload, 'reservations', target, (reservation) => (
        typeof reservation.reservation_uid === 'string'
          && reservation.reservation_uid.length > 0
          && !Object.prototype.hasOwnProperty.call(reservation, 'status')
          && typeof reservation.check_out_date === 'string'
          && reservation.check_out_date < evaluationDate
          && Number.isInteger(reservation.accommodation?.id)
          && reservation.accommodation.id > 0
      ));
    }
    if (target.name === 'wishlist-list') {
      return cursorContract(payload, 'wishlists', target, (wishlist) => (
        Number.isInteger(wishlist.id)
          && wishlist.id > 0
          && typeof wishlist.name === 'string'
      ));
    }
    if (target.name === 'wishlist-accommodations') {
      return cursorContract(payload, 'wishlist_accommodations', target, (entry) => (
        Number.isInteger(entry.id)
          && entry.id > 0
          && Number.isInteger(entry.accommodation?.id)
          && entry.accommodation.id > 0
      ));
    }
    if (target.name === 'recently-viewed') {
      return payload?.success === true
        && Array.isArray(payload?.data?.accommodations)
        && payload.data.accommodations.length === target.expectedRows
        && payload.data.total_count === target.expectedRows;
    }
  } catch (_) {
    return false;
  }
  return false;
}

export function guestManifestGaps() {
  return [
    'account-concurrency-capacity',
    'dataset-run-id',
    'canonical-payload-digest',
    'etl-commit',
    'flyway-version-checksums',
    'evaluation-time-and-expiry',
    'target-cardinality-popularity',
  ];
}

function parseHttpOrigin(raw) {
  requireCondition(typeof raw === 'string' && raw === raw.trim(), 'BASE_URL is required');
  const value = raw.replace(/\/+$/, '');
  requireCondition(/^https?:\/\/[^/]+$/.test(value), 'BASE_URL must be one HTTP origin');
  return value;
}

function parseSession(raw) {
  requireCondition(typeof raw === 'string' && raw === raw.trim(), 'benchmark session is invalid');
  requireCondition(/^[A-Za-z0-9._~-]{16,512}$/.test(raw), 'benchmark session is invalid');
  return raw;
}

const manifestPath = __ENV.BENCHMARK_MANIFEST;
requireCondition(typeof manifestPath === 'string' && manifestPath.length > 0, 'BENCHMARK_MANIFEST is required');
const manifestRaw = open(manifestPath);
const run = parseTrafficRunConfig(__ENV, manifestRaw, GUEST_TARGETS);
const target = buildGuestTarget(run.manifest, run.target, __ENV);
const baseUrl = parseHttpOrigin(__ENV.BASE_URL);
let sessionId = '';
if (target.authenticated && run.mode !== 'inspect') {
  const sessionPath = __ENV.BENCHMARK_SESSION_FILE;
  requireCondition(
    typeof sessionPath === 'string' && sessionPath.length > 0,
    'BENCHMARK_SESSION_FILE is required for an authenticated target',
  );
  sessionId = parseSession(open(sessionPath));
}

const clientDuration = new Trend('traffic_client_duration', true);
const requestSuccess = new Rate('traffic_request_success');
const completedSamples = new Counter('traffic_completed_samples');

export const options = buildTrafficOptions(run);

export function inspect() {
  // Configuration and manifest parsing happen during module initialization.
}

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function requestTarget(phase) {
  const tags = {
    phase,
    role: run.role,
    target: run.target,
    name: target.requestName,
  };
  const params = target.authenticated ? authenticatedParams(sessionId, tags) : { tags };
  const response = http.get(`${baseUrl}${target.path}`, params);
  const contractMatches = matchesGuestTargetContract(
    target,
    parsePayload(response),
    new Date().toISOString().slice(0, 10),
  );
  const success = response.status === 200 && contractMatches;

  check(response, {
    [`${run.target} returns HTTP 200`]: (value) => value.status === 200,
    [`${run.target} matches its response contract`]: () => contractMatches,
  }, tags);
  requestSuccess.add(success, tags);
  if (phase === 'measure') {
    clientDuration.add(response.timings.duration, tags);
    completedSamples.add(1, tags);
  }
}

export function warmup() {
  requestTarget('warmup');
}

export function measure() {
  requestTarget('measure');
}

export function handleSummary(data) {
  if (run.mode !== 'measure') {
    return { stdout: `traffic ${run.mode} completed: guest/${run.target}\n` };
  }
  const artifact = summarizeTrafficMetrics(data, run);
  artifact.metadata.endpoint = target.path;
  artifact.metadata.endpointTemplate = target.requestName;
  artifact.manifestGaps = [...guestManifestGaps(), ...target.gaps];
  const serialized = `${JSON.stringify(artifact, null, 2)}\n`;
  return {
    stdout: serialized,
    [run.resultPath]: serialized,
  };
}

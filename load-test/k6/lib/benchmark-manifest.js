function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requireObject(value, path) {
  requireCondition(isObject(value), `BENCHMARK_MANIFEST.${path} must be an object`);
}

function requirePositiveInteger(value, path) {
  requireCondition(
    Number.isInteger(value) && value > 0,
    `BENCHMARK_MANIFEST.${path} must be a positive integer`,
  );
}

function requireRequiredRows(value, path, requiredRows) {
  requirePositiveInteger(value, path);
  requireCondition(
    value === requiredRows,
    `BENCHMARK_MANIFEST.${path} must equal requiredRows`,
  );
}

export function parseBenchmarkManifest(raw) {
  let manifest;

  try {
    manifest = JSON.parse(raw);
  } catch (_) {
    throw new Error('BENCHMARK_MANIFEST must contain valid JSON');
  }

  requireObject(manifest, 'root');
  requireObject(manifest.account, 'account');
  requireObject(manifest.review, 'review');
  requireObject(manifest.hostAccommodations, 'hostAccommodations');
  requireObject(manifest.guestReservations, 'guestReservations');
  requireObject(manifest.hostReservations, 'hostReservations');
  requireObject(manifest.wishlists, 'wishlists');
  requireObject(manifest.recentlyViewed, 'recentlyViewed');

  requireCondition(
    manifest.datasetVersion === 'nplus1-v1',
    'BENCHMARK_MANIFEST.datasetVersion must equal nplus1-v1',
  );
  requireCondition(
    manifest.account.email === 'benchmark-nplus1@airbob.cloud',
    'BENCHMARK_MANIFEST.account.email is not the benchmark account',
  );

  requirePositiveInteger(manifest.maxRequestedSize, 'maxRequestedSize');
  requirePositiveInteger(manifest.requiredRows, 'requiredRows');
  requirePositiveInteger(manifest.review.accommodationId, 'review.accommodationId');
  requirePositiveInteger(manifest.review.publishedReviewCount, 'review.publishedReviewCount');
  requirePositiveInteger(manifest.hostAccommodations.detailAccommodationId, 'hostAccommodations.detailAccommodationId');
  requirePositiveInteger(manifest.wishlists.primaryWishlistId, 'wishlists.primaryWishlistId');

  requireCondition(
    manifest.requiredRows === manifest.maxRequestedSize + 1,
    'BENCHMARK_MANIFEST.requiredRows must equal maxRequestedSize + 1',
  );
  requireCondition(
    manifest.guestReservations.filterType === 'PAST',
    'BENCHMARK_MANIFEST.guestReservations.filterType must equal PAST',
  );
  requireCondition(
    manifest.hostReservations.filterType === 'PAST',
    'BENCHMARK_MANIFEST.hostReservations.filterType must equal PAST',
  );

  const { requiredRows } = manifest;
  requireRequiredRows(manifest.review.reviewsWithImages, 'review.reviewsWithImages', requiredRows);
  requireRequiredRows(manifest.hostAccommodations.expectedRows, 'hostAccommodations.expectedRows', requiredRows);
  requireRequiredRows(manifest.guestReservations.expectedRows, 'guestReservations.expectedRows', requiredRows);
  requireRequiredRows(manifest.hostReservations.expectedRows, 'hostReservations.expectedRows', requiredRows);
  requireRequiredRows(manifest.wishlists.expectedRows, 'wishlists.expectedRows', requiredRows);
  requireRequiredRows(
    manifest.wishlists.primaryWishlistAccommodationRows,
    'wishlists.primaryWishlistAccommodationRows',
    requiredRows,
  );
  requireCondition(
    manifest.review.publishedReviewCount >= requiredRows,
    'BENCHMARK_MANIFEST.review.publishedReviewCount must be at least requiredRows',
  );

  const { maxRows, accommodationIds } = manifest.recentlyViewed;
  requirePositiveInteger(maxRows, 'recentlyViewed.maxRows');
  requireCondition(
    maxRows === Math.min(100, requiredRows),
    'BENCHMARK_MANIFEST.recentlyViewed.maxRows must equal min(100, requiredRows)',
  );
  requireCondition(
    Array.isArray(accommodationIds) && accommodationIds.length === maxRows,
    'BENCHMARK_MANIFEST.recentlyViewed.accommodationIds must contain exactly maxRows ids',
  );
  accommodationIds.forEach((id, index) => {
    requirePositiveInteger(id, `recentlyViewed.accommodationIds[${index}]`);
  });
  requireCondition(
    new Set(accommodationIds).size === maxRows,
    'BENCHMARK_MANIFEST.recentlyViewed.accommodationIds must be unique',
  );

  return manifest;
}

export function parseRecentlyViewedSize(raw, manifest) {
  const supported = [1, 20, 50, 100]
    .filter((size) => size <= manifest.recentlyViewed.maxRows);
  const isBlank = raw === undefined || (typeof raw === 'string' && raw.trim() === '');
  const requested = isBlank
    ? (supported.includes(20) ? 20 : supported[supported.length - 1])
    : Number(raw);

  if (!supported.includes(requested)) {
    throw new Error(`RECENTLY_VIEWED_SIZE must be one of: ${supported.join(', ')}`);
  }

  return requested;
}

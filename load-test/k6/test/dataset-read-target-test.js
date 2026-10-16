import { check } from 'k6';
import { Counter } from 'k6/metrics';

import {
  applyCacheWarmCoverageValidity,
  buildCacheWarmCoverage,
  buildDatasetReadTarget,
  matchesDatasetReadContract,
  parseCacheResetReceipt,
  selectCacheResourceId,
} from '../lib/dataset-read-target.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    contract_test_completed: ['count==1'],
  },
};

const contractTestCompleted = new Counter('contract_test_completed');

const ids = Array.from({ length: 200 }, (_, index) => index + 1);
const query = {
  kind: 'ACCOMMODATION_SEARCH_V1',
  destination: '',
  minPrice: 0,
  maxPrice: 200000,
  adultOccupancy: 2,
  childOccupancy: 0,
  infantOccupancy: 0,
  petOccupancy: 0,
  topLeftLat: 38,
  topLeftLng: 126,
  bottomRightLat: 36,
  bottomRightLng: 128,
  page: 0,
};
const manifest = {
  capsules: [{
    capsuleId: 'cache-detail-v1',
    mutability: 'READ_ONLY',
    distributions: [{
      id: 'detail-uniform',
      shape: 'UNIFORM',
      parameters: { totalKeys: 200 },
    }, {
      id: 'detail-hotset-80-20',
      shape: 'HOTSET',
      parameters: { totalKeys: 200, hotKeys: 40, hotTrafficPercent: 80 },
    }],
    accountPool: { capacity: 0, emails: [] },
    targets: [
      { id: 'detail-pool', expectedRows: 200, resourceIds: ids },
      { id: 'same-key', expectedRows: 1, resourceIds: [1] },
    ],
    runtime: {
      owner: 'AWS_LAB',
      setup: 'flush-dedicated-cache-redis',
      resetPolicy: 'restore-cache-state-per-run',
    },
  }, {
    capsuleId: 'index-query-v1',
    mutability: 'READ_ONLY',
    accountPool: { capacity: 0, emails: [] },
    targets: [
      { id: 'search-broad', expectedRows: 25_001, resourceIds: [1], query },
      { id: 'search-medium', expectedRows: 100, resourceIds: [2], query },
      { id: 'search-no-hit', expectedRows: 0, resourceIds: [], query: { ...query, minPrice: 1, maxPrice: 1 } },
    ],
    runtime: {
      owner: 'AWS_LAB',
      setup: 'rebuild-versioned-index',
      resetPolicy: 'restore-index-snapshot',
    },
  }],
};

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function searchPayload({
  success = true,
  rows = Array.from({ length: 18 }, (_, index) => ({ id: index + 1 })),
  pageInfo = {},
} = {}) {
  return {
    success,
    data: {
      stay_search_result_listing: rows,
      page_info: {
        current_page: 0,
        total_elements: 100,
        total_pages: 6,
        ...pageInfo,
      },
    },
  };
}

export default function () {
  const sameKey = buildDatasetReadTarget(manifest, 'cache-detail', 'same-key', {
    CACHE_VARIANT: 'warm',
    CACHE_ENABLED: 'true',
    CACHE_DISTRIBUTION: 'same-key',
  });
  const uniform = buildDatasetReadTarget(manifest, 'cache-detail', 'detail-pool', {
    CACHE_VARIANT: 'disabled',
    CACHE_ENABLED: 'false',
    CACHE_DISTRIBUTION: 'uniform',
  });
  const hotset = buildDatasetReadTarget(manifest, 'cache-detail', 'detail-pool', {
    CACHE_VARIANT: 'warm',
    CACHE_ENABLED: 'true',
    CACHE_DISTRIBUTION: 'hotset-80-20',
  });
  const search = buildDatasetReadTarget(manifest, 'index-query', 'search-medium');
  const broadSearch = buildDatasetReadTarget(manifest, 'index-query', 'search-broad');
  const noHit = buildDatasetReadTarget(manifest, 'index-query', 'search-no-hit');
  const hotIds = Array.from({ length: 10 }, (_, iteration) => (
    selectCacheResourceId(hotset, iteration)
  ));
  const receipt = JSON.stringify({
    schemaVersion: 1,
    manifestSha256: 'a'.repeat(64),
    capsuleId: 'cache-detail-v1',
    action: 'flush-dedicated-cache-redis',
    dbSizeAfter: 0,
    cacheEnabled: true,
    variant: 'warm',
    runLabel: 'cache-warm-r1',
    generatedAt: '2026-08-26T12:00:00Z',
  });
  const invalidSearchPayloads = [
    searchPayload({ success: false }),
    searchPayload({ rows: Array.from({ length: 17 }, (_, index) => ({ id: index + 1 })) }),
    searchPayload({
      rows: Array.from({ length: 18 }, (_, index) => ({ id: index === 17 ? 0 : index + 1 })),
    }),
    searchPayload({ pageInfo: { current_page: 1 } }),
    searchPayload({ pageInfo: { total_elements: 99 } }),
    searchPayload({ pageInfo: { total_pages: 5 } }),
  ];
  const completeArtifact = { validity: { status: 'valid', reasons: [] } };
  const completeCoverage = buildCacheWarmCoverage(hotset, 'measure', {
    metrics: { traffic_cache_warm_prefetch_completed_keys: { values: { count: 200 } } },
  });
  applyCacheWarmCoverageValidity(completeArtifact, completeCoverage);
  const incompleteArtifact = { validity: { status: 'valid', reasons: [] } };
  const incompleteCoverage = buildCacheWarmCoverage(hotset, 'measure', {
    metrics: { traffic_cache_warm_prefetch_completed_keys: { values: { count: 199 } } },
  });
  applyCacheWarmCoverageValidity(incompleteArtifact, incompleteCoverage);

  check(null, {
    'same-key always resolves the representative id': () => (
      selectCacheResourceId(sameKey, 0) === 1 && selectCacheResourceId(sameKey, 999) === 1
    ),
    'uniform walks the complete pool deterministically': () => (
      selectCacheResourceId(uniform, 0) === 1
        && selectCacheResourceId(uniform, 199) === 200
        && selectCacheResourceId(uniform, 200) === 1
    ),
    'hotset routes eight of every ten requests to the first forty keys': () => (
      hotIds.slice(0, 8).every((id) => id <= 40)
        && hotIds.slice(8).every((id) => id > 40)
    ),
    'search path is derived only from the typed manifest query': () => (
      search.path.startsWith('/api/v1/search/accommodations?')
        && search.path.includes('adultOccupancy=2')
        && search.path.includes('topLeftLat=38')
        && !search.path.includes('amenityTypes')
        && !search.path.includes('checkIn')
    ),
    'cache response must match the selected resource': () => (
      matchesDatasetReadContract(sameKey, { success: true, data: { id: 1 } }, 1)
        && !matchesDatasetReadContract(sameKey, { success: true, data: { id: 2 } }, 1)
    ),
    'search response binds the exact manifest total below the default bound': () => (
      matchesDatasetReadContract(search, searchPayload())
    ),
    'search response uses the default API total bound while retaining dataset truth': () => (
      broadSearch.expectedRows === 25_001
        && broadSearch.expectedApiReportedTotal === 10_000
        && matchesDatasetReadContract(broadSearch, searchPayload({
          pageInfo: { total_elements: 10_000, total_pages: 556 },
        }))
        && !matchesDatasetReadContract(broadSearch, searchPayload({
          pageInfo: { total_elements: 25_001, total_pages: 1_389 },
        }))
    ),
    'search response rejects drift in success, rows, ids, page, total, and pages': () => (
      invalidSearchPayloads.every((payload) => !matchesDatasetReadContract(search, payload))
    ),
    'no-hit search requires an empty exact result': () => matchesDatasetReadContract(noHit, {
      success: true,
      data: {
        stay_search_result_listing: [],
        page_info: { current_page: 0, total_elements: 0, total_pages: 0 },
      },
    }),
    'cache receipt binds flush, manifest, variant, and run': () => (
      parseCacheResetReceipt(receipt, {
        manifestSha256: 'a'.repeat(64),
        cacheEnabled: true,
        variant: 'warm',
        runLabel: 'cache-warm-r1',
      }).dbSizeAfter === 0
    ),
    'cache receipt drift is rejected': () => rejects(() => parseCacheResetReceipt(receipt, {
      manifestSha256: 'b'.repeat(64),
      cacheEnabled: true,
      variant: 'warm',
      runLabel: 'cache-warm-r1',
    })),
    'cache toggle must match the variant': () => rejects(() => buildDatasetReadTarget(
      manifest,
      'cache-detail',
      'same-key',
      { CACHE_VARIANT: 'warm', CACHE_ENABLED: 'false', CACHE_DISTRIBUTION: 'same-key' },
    )),
    'warm cache coverage binds every declared key without invalidating a complete run': () => (
      completeCoverage.declaredTargetKeys === 200
        && completeCoverage.expectedKeys === 200
        && completeCoverage.completedKeys === 200
        && completeCoverage.status === 'complete'
        && completeArtifact.validity.status === 'valid'
    ),
    'incomplete warm cache coverage invalidates the artifact': () => (
      incompleteCoverage.status === 'incomplete'
        && incompleteArtifact.validity.status === 'invalid'
        && incompleteArtifact.validity.reasons.includes('cache-warm-coverage-incomplete')
    ),
  });
  contractTestCompleted.add(1);
}

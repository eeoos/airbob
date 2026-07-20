import { check } from 'k6';
import {
  buildReadModelOptions,
  buildReadModelPath,
  buildReadModelRequestParams,
  canonicalizeReadModelData,
  matchesReadModelContract,
  parseDurationSeconds,
  parseIsoDate,
  parsePositiveInteger,
  parseReadModelRunConfig,
  parseRequiredText,
  parseVariant,
  readModelPayloadsEquivalent,
  summarizeReadModelMetrics,
} from '../lib/read-model-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const runConfig = parseReadModelRunConfig({
    VARIANT: 'before',
    BENCHMARK_READ_MODEL_TOKEN: ' benchmark-token ',
    BASE_URL: 'http://localhost:8080/',
    DATASET_LABEL: 'synthetic-v1',
  }, 'review-summary');
  const reviewAfterPayload = {
    success: true,
    data: { total_count: 12, average_rating: 4.75 },
  };
  const wishlistAfterPayload = {
    success: true,
    data: {
      wishlists: [{
        id: 1,
        name: 'Seoul',
        created_at: '2026-01-01T00:00:00',
        wishlist_item_count: 3,
        thumbnail_image_url: 'https://example.com/image.jpg',
        is_contained: null,
        wishlist_accommodation_id: null,
      }],
      page_info: { current_size: 1, has_next: false, next_cursor: null },
    },
  };
  const revenueBeforePayload = {
    success: true,
    data: {
      from: '2026-01-01',
      to: '2026-01-31',
      source: 'raw',
      items: [{
        date: '2026-01-03',
        gross_amount: 10000,
        refund_amount: 1000,
        net_amount: 9000,
        payment_count: 2,
        refund_count: 1,
      }],
    },
  };
  const revenueAfterPayload = {
    success: true,
    data: { ...revenueBeforePayload.data, source: 'stats' },
  };
  const benchmarkOptions = buildReadModelOptions({
    rate: 2,
    warmupDuration: '30s',
    measureDuration: '1m',
    warmupSettleSeconds: 5,
    preAllocatedVUs: 10,
    maxVUs: 20,
  });
  const beforeParams = buildReadModelRequestParams({
    variant: 'before',
    benchmarkToken: ' secret ',
    sessionId: 'session-id',
    tags: { phase: 'measure' },
    timeout: '5s',
  });
  const afterParams = buildReadModelRequestParams({
    variant: 'after',
    benchmarkToken: 'ignored',
    tags: { phase: 'measure' },
  });
  const summary = summarizeReadModelMetrics({
    metrics: {
      read_model_request_success: { values: { passes: 9, fails: 1 } },
      read_model_client_duration: {
        values: {
          avg: 4,
          med: 3,
          'p(90)': 7,
          'p(95)': 8,
          'p(99)': 9,
          max: 10,
        },
      },
      'dropped_iterations{scenario:measure}': { values: { count: 2 } },
    },
  }, 5);

  check(null, {
    'before variant is accepted': () => parseVariant('before') === 'before',
    'after variant is accepted': () => parseVariant('after') === 'after',
    'unknown variant is rejected': () => rejects(() => parseVariant('mixed')),
    'required text is trimmed': () => parseRequiredText(' value ', 'VALUE') === 'value',
    'common run config applies stable defaults': () => (
      runConfig.variant === 'before'
        && runConfig.benchmarkToken === 'benchmark-token'
        && runConfig.baseUrl === 'http://localhost:8080'
        && runConfig.rate === 5
        && runConfig.round === 1
        && runConfig.resultPath
          === 'build/k6/read-model/review-summary-before-r1.json'
        && runConfig.metadata.dataset_label === 'synthetic-v1'
    ),
    'common run config requires token for after parity': () => rejects(() => (
      parseReadModelRunConfig({ VARIANT: 'after', DATASET_LABEL: 'synthetic-v1' }, 'review')
    )),
    'positive integer is parsed': () => parsePositiveInteger('50', 'SIZE') === 50,
    'zero integer is rejected': () => rejects(() => parsePositiveInteger('0', 'SIZE')),
    'compound k6 duration is parsed': () => parseDurationSeconds('1m30s', 'DURATION') === 90,
    'measurement starts after warmup graceful stop and settle time': () => (
      benchmarkOptions.scenarios.measure.startTime === '40s'
        && benchmarkOptions.scenarios.measure.duration === '1m'
        && benchmarkOptions.scenarios.measure.maxVUs === 20
    ),
    'scenario duration shorter than one second is rejected': () => rejects(() => (
      buildReadModelOptions({
        rate: 1,
        warmupDuration: '500ms',
        measureDuration: '1s',
        warmupSettleSeconds: 0,
        preAllocatedVUs: 1,
        maxVUs: 1,
      })
    )),
    'ISO date is accepted': () => parseIsoDate('2026-07-20', 'FROM') === '2026-07-20',
    'impossible ISO date is rejected': () => rejects(() => parseIsoDate('2026-02-30', 'FROM')),
    'review paths keep before and after versions explicit': () => (
      buildReadModelPath({
        domain: 'review',
        variant: 'before',
        accommodationId: 42,
      }) === '/api/v2/accommodations/42/reviews/summary'
        && buildReadModelPath({
          domain: 'review',
          variant: 'after',
          accommodationId: 42,
        }) === '/api/v1/accommodations/42/reviews/summary'
    ),
    'wishlist path includes page size': () => (
      buildReadModelPath({ domain: 'wishlist', variant: 'after', size: 50 })
        === '/api/v1/members/wishlists?size=50'
    ),
    'revenue path includes the closed date range': () => (
      buildReadModelPath({
        domain: 'revenue',
        variant: 'before',
        from: '2026-01-01',
        to: '2026-01-31',
      }) === '/api/v2/admin/stats/revenue?from=2026-01-01&to=2026-01-31'
    ),
    'before request carries token and session': () => (
      beforeParams.headers['X-Benchmark-Token'] === 'secret'
        && beforeParams.cookies.SESSION_ID === 'session-id'
        && beforeParams.tags.phase === 'measure'
        && beforeParams.timeout === '5s'
    ),
    'after request does not expose benchmark header': () => (
      !Object.prototype.hasOwnProperty.call(afterParams, 'headers')
    ),
    'before request rejects blank token': () => rejects(() => buildReadModelRequestParams({
      variant: 'before',
      benchmarkToken: ' ',
    })),
    'review contract checks count and average': () => matchesReadModelContract({
      domain: 'review',
      variant: 'after',
      expectedCount: 12,
      expectedData: canonicalizeReadModelData('review', reviewAfterPayload.data),
      payload: reviewAfterPayload,
    }),
    'review contract rejects a different average': () => !matchesReadModelContract({
      domain: 'review',
      variant: 'after',
      expectedCount: 12,
      expectedData: { total_count: 12, average_rating: 4.5 },
      payload: reviewAfterPayload,
    }),
    'wishlist contract checks every returned row': () => matchesReadModelContract({
      domain: 'wishlist',
      variant: 'before',
      expectedCount: 1,
      expectedData: canonicalizeReadModelData('wishlist', wishlistAfterPayload.data),
      payload: wishlistAfterPayload,
    }),
    'wishlist contract rejects a wrong row count': () => !matchesReadModelContract({
      domain: 'wishlist',
      variant: 'after',
      expectedCount: 2,
      payload: wishlistAfterPayload,
    }),
    'wishlist contract rejects a different representative image': () => !matchesReadModelContract({
      domain: 'wishlist',
      variant: 'after',
      expectedCount: 1,
      expectedData: canonicalizeReadModelData('wishlist', {
        ...wishlistAfterPayload.data,
        wishlists: [{
          ...wishlistAfterPayload.data.wishlists[0],
          thumbnail_image_url: 'https://example.com/other.jpg',
        }],
      }),
      payload: wishlistAfterPayload,
    }),
    'revenue contract checks source range and rows': () => matchesReadModelContract({
      domain: 'revenue',
      variant: 'before',
      expectedCount: 1,
      expectedData: canonicalizeReadModelData('revenue', revenueAfterPayload.data),
      from: '2026-01-01',
      to: '2026-01-31',
      payload: revenueBeforePayload,
    }),
    'revenue parity ignores source but compares every aggregate': () => (
      readModelPayloadsEquivalent('revenue', revenueBeforePayload, revenueAfterPayload)
        && !readModelPayloadsEquivalent('revenue', revenueBeforePayload, {
          ...revenueAfterPayload,
          data: {
            ...revenueAfterPayload.data,
            items: [{ ...revenueAfterPayload.data.items[0], net_amount: 8999 }],
          },
        })
    ),
    'summary exposes request integrity and p99': () => (
      summary.requests.attempted === 10
        && summary.requests.successful === 9
        && summary.requests.error_rate === 0.1
        && summary.requests.achieved_rps === 2
        && summary.requests.dropped_iterations === 2
        && summary.latency_ms.p99 === 9
    ),
  });
}

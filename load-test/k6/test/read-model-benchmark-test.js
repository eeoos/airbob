import { check } from 'k6';
import { Counter } from 'k6/metrics';

import {
  buildCanonicalResultHash,
  buildReadModelOptions,
  buildReadModelRequestParams,
  buildReadModelTarget,
  canonicalWishlistCreatedAt,
  canonicalizeReadModelData,
  matchesReadModelContract,
  parseDurationSeconds,
  parseReadModelEvidenceContext,
  parseReadModelRunConfig,
  readModelPayloadsEquivalent,
  summarizeReadModelMetrics,
} from '../lib/read-model-benchmark.js';
import {
  buildReadModelEvidenceArtifact,
  createReadModelBenchmark,
} from '../lib/read-model-runner.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    contract_test_completed: ['count==1'],
  },
};

const contractTestCompleted = new Counter('contract_test_completed');

const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const SHA_C = 'c'.repeat(64);
const SHA_D = 'd'.repeat(64);
const SHA_E = 'e'.repeat(64);
const SHA_F = 'f'.repeat(64);
const CANONICAL_MANIFEST_RAW = open('../../../infra/aws/tests/fixtures/benchmark-dataset-v2.json');
let assembleSetupCalls = 0;
const ASSEMBLE_BENCHMARK = createReadModelBenchmark({
  mode: 'assemble',
  measureDuration: '1s',
  variant: 'after',
  target: {
    domain: 'review',
    target: { id: 'review-hot' },
    beforePath: '/before',
    afterPath: '/after',
  },
  setup: () => {
    assembleSetupCalls += 1;
    return {};
  },
});

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function manifestWithExpectedHashes() {
  const manifest = JSON.parse(CANONICAL_MANIFEST_RAW);
  const capsule = manifest.capsules.find((candidate) => candidate.capsuleId === 'read-model-v2');
  const hashes = {
    'review-hot': 'bb6d20d04744055da7642f71dae129460e49fcced1213166a9359f373f25e74e',
    'review-empty': 'dfc558e9e4416a60aad6732f60db110bacb2215e199e537f9ac9877f31ae082b',
    'wishlist-hot': 'b476b31d35fb8660f5f1335ae986770fb899a69179c9f449117699237c169ebf',
    'revenue-recent-1d': '6638b0ca5fd3b01aff54fdf276a31a7fbead1164adac467aff21b16d92ee5693',
    'revenue-empty': 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  };
  capsule.targets.forEach((target) => {
    if (hashes[target.id]) {
      target.expectedResultHash = hashes[target.id];
    }
  });
  return manifest;
}

function evidenceContext(target, manifest) {
  return {
    schema_version: 'read-model-run-context-v1',
    run_id: 'read-model-run',
    design: 'READ_MODEL_AB',
    experiment_id: 'review-hot-ab',
    block_id: 'block-01',
    window_id: 'window-01',
    statement_event_id: 'event-01',
    pair_role: 'BEFORE',
    release_tuple: {
      release_id: 'airbob-v27-production-skew-20260827',
      dataset_version: 'benchmark-dataset-v2',
      world_version: 'world-v2',
      source_calibration_sha256: manifest.world.provenance.calibrationSha256,
      production_skew_spec_sha256: manifest.world.provenance.specSha256,
      dataset_manifest_sha256: SHA_A,
      dump_sha256: SHA_B,
      schema_migration_sha256: SHA_C,
      target_fingerprint_sha256: manifest.targetFingerprint,
    },
    app_build: {
      commit_sha: '1'.repeat(40),
      image_digest: `sha256:${SHA_D}`,
      build_id: 'airbob-read-model-20260827',
      instance_count: 1,
      runtime_revision: SHA_E,
      app_instance_id: 'i-0123456789abcdef0',
      resource_fencing_token_sha256: SHA_B,
    },
    database: {
      clone_id: 'clone-a',
      pre_fingerprint_sha256: manifest.world.fingerprints['final-world'],
      post_fingerprint_sha256: manifest.world.fingerprints['final-world'],
      optimizer_snapshot_sha256: SHA_E,
      statistics_snapshot_sha256: SHA_F,
      histogram_snapshot_sha256: SHA_A,
      analyze_receipt_sha256: SHA_B,
      mysql_version: '8.0.42',
      auto_statistics_recalculation_detected: false,
    },
    treatment: {
      kind: 'READ_MODEL',
      candidate_index: null,
      candidate_visible: null,
      optimizer_switch_use_invisible_indexes: false,
    },
    lifecycle: {
      scheduler_enabled: false,
      kafka_listener_enabled: false,
      inventory_lifecycle_enabled: false,
      external_side_effects_enabled: false,
    },
    measurement_fencing_token_sha256: SHA_C,
    runtime_assertion: {
      runtime_assertion_pre_sha256: SHA_A,
      runtime_assertion_post_sha256: SHA_B,
      pre: {
        schema_version: 1,
        run_id: 'read-model-run',
        resource_fencing_token_sha256: SHA_B,
        challenge_sha256: SHA_D,
        runtime_revision: SHA_E,
        app_instance_id: 'i-0123456789abcdef0',
        active_profiles: ['aws', 'read-model-benchmark', 'traffic-benchmark'],
        scheduler_enabled: false,
        kafka_listener_enabled: false,
        inventory_lifecycle_enabled: false,
        external_side_effects_enabled: false,
      },
      post: {
        schema_version: 1,
        run_id: 'read-model-run',
        resource_fencing_token_sha256: SHA_B,
        challenge_sha256: SHA_F,
        runtime_revision: SHA_E,
        app_instance_id: 'i-0123456789abcdef0',
        active_profiles: ['aws', 'read-model-benchmark', 'traffic-benchmark'],
        scheduler_enabled: false,
        kafka_listener_enabled: false,
        inventory_lifecycle_enabled: false,
        external_side_effects_enabled: false,
      },
    },
    mysql_evidence: {
      statement_event: {
        window_id: 'window-01',
        event_id: 'event-01',
        digest: SHA_C,
        digest_text: 'SELECT COUNT ( * ) FROM `review` WHERE `accommodation_id` = ?',
        delta: {
          calls: 100,
          timer_wait_ps: '1000000',
          rows_examined: '10000',
          rows_sent: '100',
          errors: 0,
        },
      },
      optimizer_state: {
        snapshot_sha256: SHA_E,
        statistics_snapshot_sha256: SHA_F,
        histogram_snapshot_sha256: SHA_A,
        analyze_receipt_sha256: SHA_B,
      },
      explain: {
        json_raw: '{"query_block":{"table":{"key":"FK_review_accommodation_id"}}}',
        tree_raw: '-> Aggregate (actual time=1.0e-2..2.5E+1 rows=1.20K loops=1)',
        candidate_in_chosen_plan: false,
      },
    },
    target_id: target.target.id,
  };
}

export default function () {
  const manifest = manifestWithExpectedHashes();
  const manifestRaw = JSON.stringify(manifest);
  const review = buildReadModelTarget(manifestRaw, 'review-hot');
  const reviewEmpty = buildReadModelTarget(manifestRaw, 'review-empty');
  const wishlist = buildReadModelTarget(manifestRaw, 'wishlist-hot');
  const wishlistDeep = buildReadModelTarget(manifestRaw, 'wishlist-hot-deep');
  const revenue = buildReadModelTarget(manifestRaw, 'revenue-recent-1d');
  const revenueEmpty = buildReadModelTarget(manifestRaw, 'revenue-empty');

  const reviewPayload = {
    success: true,
    data: { total_count: 12, average_rating: 4.75 },
  };
  const reviewEmptyPayload = {
    success: true,
    data: { total_count: 0, average_rating: 0 },
  };
  const wishlistPayload = {
    success: true,
    data: {
      wishlists: [{
        id: 1,
        name: 'Seoul',
        created_at: '2026-01-01T00:00:00Z',
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
      from: '2026-07-30',
      to: '2026-07-30',
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

  const contextValue = evidenceContext(review, manifest);
  contextValue.release_tuple.dataset_manifest_sha256 = review.manifestSha256;
  const context = parseReadModelEvidenceContext(
    JSON.stringify(contextValue),
    review,
    'before',
  );
  const runConfig = parseReadModelRunConfig({
    VARIANT: 'before',
    BENCHMARK_READ_MODEL_TOKEN: ' benchmark-token ',
    BASE_URL: 'http://localhost:8080/',
  }, review, context);

  const optionsValue = buildReadModelOptions({
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
    benchmarkToken: ' after-secret ',
    tags: { phase: 'measure' },
  });
  const summary = summarizeReadModelMetrics({
    metrics: {
      read_model_request_success: { values: { passes: 9, fails: 1 } },
      read_model_client_duration: {
        values: {
          count: 9,
          min: 2,
          med: 3,
          'p(95)': 8,
          'p(99)': 9,
          max: 10,
        },
      },
      'dropped_iterations{scenario:measure}': { values: { count: 2 } },
    },
  }, 5);
  const evidenceArtifact = buildReadModelEvidenceArtifact(runConfig, {
    metrics: {
      read_model_request_success: { values: { passes: 100, fails: 0 } },
      read_model_client_duration: {
        values: {
          count: 100,
          min: 1,
          med: 3,
          'p(95)': 8,
          'p(99)': 9,
          max: 10,
        },
      },
      'dropped_iterations{scenario:measure}': { values: { count: 0 } },
    },
  }, 60, '2026-08-27T00:00:00.000Z');

  check(null, {
    'manifest target selects review path and expected count': () => (
      review.domain === 'review'
        && review.beforePath === '/api/v2/accommodations/101/reviews/summary'
        && review.afterPath === '/api/v1/accommodations/101/reviews/summary'
        && review.expectedCount === 100
        && review.manifestTarget.query_kind === 'REVIEW_SUMMARY_V1'
        && review.manifestTarget.account_ref === null
    ),
    'manifest target preserves zero review target': () => (
      reviewEmpty.expectedCount === 0
        && matchesReadModelContract({
          domain: 'review',
          variant: 'after',
          expectedCount: 0,
          payload: reviewEmptyPayload,
        })
    ),
    'wishlist first page is bound to manifest account': () => (
      wishlist.domain === 'wishlist'
        && wishlist.account.memberId === wishlist.query.memberId
        && wishlist.account.role === 'MEMBER'
        && wishlist.beforePath === '/api/v2/members/wishlists?size=50'
        && wishlist.manifestTarget.account_ref === 'member-201'
    ),
    'wishlist deep target encodes the exact cursor': () => (
      wishlistDeep.beforePath.startsWith('/api/v2/members/wishlists?size=50&cursor=')
        && decodeURIComponent(wishlistDeep.beforePath.split('cursor=')[1])
          === 'eyJpZCI6OTk5LCJsYXN0X2NyZWF0ZWRfYXQiOiIyMDI2LTA3LTMxVDIzOjU5OjU5In0='
    ),
    'revenue target uses manifest UTC range': () => (
      revenue.beforePath
        === '/api/v2/admin/stats/revenue?from=2026-07-30&to=2026-07-30'
        && revenue.account.role === 'ADMIN'
    ),
    'empty revenue response is a valid zero-row target': () => matchesReadModelContract({
      domain: 'revenue',
      variant: 'after',
      expectedCount: revenueEmpty.expectedCount,
      from: revenueEmpty.query.from,
      to: revenueEmpty.query.to,
      payload: {
        success: true,
        data: {
          from: revenueEmpty.query.from,
          to: revenueEmpty.query.to,
          source: 'stats',
          items: [],
        },
      },
    }),
    'refund-only boundary allows a negative net amount': () => matchesReadModelContract({
      domain: 'revenue',
      variant: 'after',
      expectedCount: 1,
      from: '2026-06-30',
      to: '2026-06-30',
      payload: {
        success: true,
        data: {
          from: '2026-06-30',
          to: '2026-06-30',
          source: 'stats',
          items: [{
            date: '2026-06-30',
            gross_amount: 0,
            refund_amount: 5000,
            net_amount: -5000,
            payment_count: 0,
            refund_count: 1,
          }],
        },
      },
    }),
    'review canonical hash matches ETL length-prefix contract': () => (
      buildCanonicalResultHash('review', reviewPayload.data)
        === 'bb6d20d04744055da7642f71dae129460e49fcced1213166a9359f373f25e74e'
    ),
    'zero review canonical hash keeps two decimal average': () => (
      buildCanonicalResultHash('review', reviewEmptyPayload.data)
        === 'dfc558e9e4416a60aad6732f60db110bacb2215e199e537f9ac9877f31ae082b'
    ),
    'wishlist canonical hash matches ETL row-field contract': () => (
      buildCanonicalResultHash('wishlist', wishlistPayload.data)
        === 'b476b31d35fb8660f5f1335ae986770fb899a69179c9f449117699237c169ebf'
    ),
    'wishlist created_at canonicalization pads zero fractions to DATETIME(6)': () => (
      canonicalWishlistCreatedAt('2026-01-01T00:00:00Z')
        === '2026-01-01T00:00:00.000000'
        && canonicalWishlistCreatedAt('2026-01-01T00:00:00.1Z')
          === '2026-01-01T00:00:00.100000'
        && canonicalWishlistCreatedAt('2026-01-01T00:00:00')
          === '2026-01-01T00:00:00.000000'
    ),
    'wishlist created_at canonicalization preserves database microseconds': () => (
      canonicalWishlistCreatedAt('2026-01-01T00:00:00.123456Z')
        === '2026-01-01T00:00:00.123456'
        && rejects(() => canonicalWishlistCreatedAt('2026-01-01T00:00:00.1234567Z'))
    ),
    'revenue canonical hash matches ETL ordered rows': () => (
      buildCanonicalResultHash('revenue', revenueAfterPayload.data)
        === '6638b0ca5fd3b01aff54fdf276a31a7fbead1164adac467aff21b16d92ee5693'
    ),
    'empty relation hash matches SHA-256 empty stream': () => (
      buildCanonicalResultHash('revenue', { items: [] })
        === 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
    ),
    'run config is target and release bound': () => (
      runConfig.target.target.id === 'review-hot'
        && runConfig.metadata.release_tuple.release_id
          === 'airbob-v27-production-skew-20260827'
        && runConfig.resultPath
          === 'build/k6/read-model/review-review-hot-before-block-01.json'
    ),
    'legacy manual target environment is rejected': () => rejects(() => (
      parseReadModelRunConfig({
        VARIANT: 'before',
        BENCHMARK_READ_MODEL_TOKEN: 'token',
        REVIEW_ACCOMMODATION_ID: '101',
      }, review, context)
    )),
    'result artifact cannot escape the read-model boundary': () => rejects(() => (
      parseReadModelRunConfig({
        VARIANT: 'before',
        BENCHMARK_READ_MODEL_TOKEN: 'token',
        K6_RESULT_PATH: '../../app.env',
      }, review, context)
    )),
    'context rejects a lifecycle writer': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.lifecycle.scheduler_enabled = true;
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'context rejects a runtime receipt for another run': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.runtime_assertion.pre.run_id = 'another-run';
      changed.runtime_assertion.post.run_id = 'another-run';
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'context rejects a runtime receipt for another resource fence': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.runtime_assertion.pre.resource_fencing_token_sha256 = SHA_C;
      changed.runtime_assertion.post.resource_fencing_token_sha256 = SHA_C;
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'resource and measurement fences remain independently bound': () => (
      context.app_build.resource_fencing_token_sha256 === SHA_B
        && context.measurement_fencing_token_sha256 === SHA_C
    ),
    'context rejects database fingerprint drift': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.database.post_fingerprint_sha256 = SHA_D;
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'context rejects a mismatched selected target': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.target_id = 'review-cold';
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'context rejects an app-level invisible-index latency claim': () => rejects(() => {
      const changed = JSON.parse(JSON.stringify(contextValue));
      changed.design = 'INVISIBLE_INDEX_AB';
      changed.pair_role = 'INDEX_BASELINE';
      changed.treatment = {
        kind: 'INVISIBLE_INDEX',
        candidate_index: 'idx_review_candidate',
        candidate_visible: false,
        optimizer_switch_use_invisible_indexes: false,
      };
      parseReadModelEvidenceContext(JSON.stringify(changed), review, 'before');
    }),
    'setup-only API source field does not break revenue parity': () => (
      readModelPayloadsEquivalent('revenue', revenueBeforePayload, revenueAfterPayload)
    ),
    'request options separate warmup and measurement': () => (
      optionsValue.scenarios.measure.startTime === '40s'
        && optionsValue.summaryTrendStats.includes('count')
    ),
    'assemble mode performs no login or parity setup': () => (
      Object.keys(ASSEMBLE_BENCHMARK.setup()).length === 0
        && assembleSetupCalls === 0
    ),
    'before request carries token and session': () => (
      beforeParams.headers['X-Benchmark-Token'] === 'secret'
        && beforeParams.cookies.SESSION_ID === 'session-id'
        && beforeParams.timeout === '5s'
    ),
    'after request carries benchmark header': () => (
      afterParams.headers['X-Benchmark-Token'] === 'after-secret'
    ),
    'duration parser accepts compound k6 values': () => (
      parseDurationSeconds('1m30s', 'DURATION') === 90
    ),
    'canonical parity retains every response field that affects business output': () => (
      JSON.stringify(canonicalizeReadModelData('wishlist', wishlistPayload.data))
        .includes('thumbnail_image_url')
    ),
    'summary maps to evidence requests and percentiles': () => (
      summary.requests.attempted === 10
        && summary.requests.failed === 1
        && summary.requests.dropped_iterations === 2
        && summary.latency_ms.count === 9
        && summary.latency_ms.p50 === 3
        && summary.latency_ms.p99 === 9
    ),
    'runner emits the adapter-free read-model-evidence-v1 contract': () => (
      JSON.stringify(Object.keys(evidenceArtifact).sort())
        === JSON.stringify([
          'measurement_fencing_token_sha256', 'metadata', 'mysql_evidence', 'parity',
          'performance', 'runtime_assertion', 'schema_version', 'validity',
        ])
        && evidenceArtifact.schema_version === 'read-model-evidence-v1'
        && evidenceArtifact.validity.status === 'valid'
        && evidenceArtifact.metadata.manifest_target.target_id === 'review-hot'
        && evidenceArtifact.performance.headline_scope === 'measure-only'
        && evidenceArtifact.performance.requests.attempted === 100
        && evidenceArtifact.performance.latency_ms.p50 === 3
        && !JSON.stringify(evidenceArtifact).includes('benchmark-token')
        && !JSON.stringify(evidenceArtifact).includes('@airbob.cloud')
    ),
  });
  contractTestCompleted.add(1);
}

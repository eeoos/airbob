import { check } from 'k6';
import { Counter } from 'k6/metrics';

import {
  CACHE_BENCHMARK_METRICS,
  buildCacheBenchmarkOptions,
  buildCacheBenchmarkRequest,
  canonicalAccommodationData,
  matchesCacheBenchmarkResponse,
  parseCacheBenchmarkConfig,
  parseCacheBenchmarkFixture,
  selectCacheBenchmarkAccommodation,
  summarizeCacheBenchmark,
} from '../lib/accommodation-cache-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'], cache_contract_completed: ['count==1'] },
};
const completed = new Counter('cache_contract_completed');

const environment = {
  BASE_URL: 'http://127.0.0.1:8080/',
  BENCHMARK_READ_MODEL_TOKEN: 'test-token-not-a-real-secret',
  CACHE_BENCHMARK_FIXTURE: '/tmp/cache-fixture.json',
  VARIANT: 'before',
  MODE: 'measure',
  DISTRIBUTION: 'uniform',
  RATE: '10',
  DURATION: '2s',
  RUN_LABEL: 'offline-before-r1',
  RESULT_PATH: '/tmp/cache-result.json',
  APP_COMMIT: 'a'.repeat(40),
};

function fixture(count = 20) {
  return {
    schemaVersion: 1,
    datasetId: 'offline-cache-detail',
    accommodations: Array.from({ length: count }, (_, index) => ({
      id: index + 1,
      data: {
        id: index + 1,
        name: `Stay ${index + 1}`,
        is_in_wishlist: false,
        amenities: [{ type: 'wifi', count: 1 }, { type: 'bed', count: 2 }],
        images: [{ id: 7, url: '/first.png' }, { id: 9, url: '/second.png' }],
        review_summary: { average_rating: 4.7, review_count: 12 },
        nested: { ordered: ['first', 'second'], nullable: null },
      },
    })),
  };
}

function run(overrides = {}, value = fixture()) {
  return parseCacheBenchmarkConfig({ ...environment, ...overrides }, JSON.stringify(value));
}

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

function metrics(overrides = {}) {
  return { metrics: {
    [CACHE_BENCHMARK_METRICS.completed]: { values: { count: 20 } },
    [CACHE_BENCHMARK_METRICS.success]: { values: { passes: 20, fails: 0, rate: 1 } },
    [CACHE_BENCHMARK_METRICS.duration]: {
      values: { avg: 4, min: 1, med: 3, 'p(95)': 8, 'p(99)': 10, max: 12 },
    },
    http_req_failed: { values: { rate: 0 } },
    dropped_iterations: { values: { count: 0 } },
    ...overrides,
  } };
}

function payload(change = (data) => data) {
  return { success: true, data: change(fixture().accommodations[0].data) };
}

export default function () {
  const before = run();
  const after = run({ VARIANT: 'after' });
  const beforeRequest = buildCacheBenchmarkRequest(before, 7);
  const afterRequest = buildCacheBenchmarkRequest(after, 7);
  const expected = before.fixture.accommodations[0];
  const same = run({ DISTRIBUTION: 'same-key' });
  const hot = run({ DISTRIBUTION: 'hotset-80-20' });
  const sequence = (config, length) => Array.from({ length }, (_, i) => (
    selectCacheBenchmarkAccommodation(config, i).id
  ));
  const frequency = new Map();
  sequence(hot, 800).forEach((id) => frequency.set(id, (frequency.get(id) || 0) + 1));
  const sortedData = fixture().accommodations[0].data;
  const originalJson = JSON.stringify(sortedData);
  Object.freeze(sortedData.amenities);
  const canonical = canonicalAccommodationData(sortedData);
  const validSummary = summarizeCacheBenchmark(metrics(), before);
  const droppedSummary = summarizeCacheBenchmark(metrics({
    dropped_iterations: { values: { count: 1 } },
  }), before);
  const insufficientSummary = summarizeCacheBenchmark(metrics({
    [CACHE_BENCHMARK_METRICS.completed]: { values: { count: 19 } },
    [CACHE_BENCHMARK_METRICS.success]: { values: { passes: 19, fails: 0, rate: 1 } },
  }), before);
  const failedSummary = summarizeCacheBenchmark(metrics({
    [CACHE_BENCHMARK_METRICS.success]: { values: { passes: 19, fails: 1, rate: 0.95 } },
  }), before);
  const benchmarkOptions = buildCacheBenchmarkOptions(before);
  const warmup = run({ MODE: 'warmup', RESULT_PATH: undefined });

  check(null, {
    'before is exactly the uncached V2 detail endpoint': () => (
      beforeRequest.url === 'http://127.0.0.1:8080/api/v2/accommodations/7'
    ),
    'after is exactly the cache-enabled V1 detail endpoint': () => (
      afterRequest.url === 'http://127.0.0.1:8080/api/v1/accommodations/7'
    ),
    'both variants use only the benchmark header and never login credentials': () => (
      JSON.stringify(beforeRequest.params.headers)
        === JSON.stringify({ 'X-Benchmark-Token': environment.BENCHMARK_READ_MODEL_TOKEN })
      && JSON.stringify(afterRequest.params.headers) === JSON.stringify(beforeRequest.params.headers)
    ),
    'redirects are disabled to preserve one request per iteration': () => (
      beforeRequest.params.redirects === 0 && benchmarkOptions.maxRedirects === 0
    ),
    'cookies reset between anonymous single-request iterations': () => (
      benchmarkOptions.noCookiesReset === false
    ),
    'object key and top-level amenity order do not change equality': () => (
      matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data,
        amenities: [{ count: 2, type: 'bed' }, { count: 1, type: 'wifi' }],
        review_summary: { review_count: 12, average_rating: 4.7 },
      })), expected)
    ),
    'canonical comparison does not mutate a frozen source amenity list': () => (
      typeof canonical === 'string' && originalJson === JSON.stringify(sortedData)
    ),
    'full nested data mismatches are rejected': () => (
      !matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data, review_summary: { ...data.review_summary, review_count: 13 },
      })), expected)
    ),
    'missing and unexpected data fields are rejected': () => (
      !matchesCacheBenchmarkResponse(200, payload(({ name, ...data }) => data), expected)
      && !matchesCacheBenchmarkResponse(200, payload((data) => ({ ...data, unexpected: true })), expected)
    ),
    'image order and nested array order remain significant': () => (
      !matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data, images: [...data.images].reverse(),
      })), expected)
      && !matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data, nested: { ...data.nested, ordered: ['second', 'first'] },
      })), expected)
    ),
    'amenity counts and duplicate entries remain significant': () => (
      !matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data, amenities: [{ type: 'wifi', count: 2 }, { type: 'bed', count: 2 }],
      })), expected)
      && !matchesCacheBenchmarkResponse(200, payload((data) => ({
        ...data, amenities: [...data.amenities, data.amenities[0]],
      })), expected)
    ),
    'status success id and primitive types are checked': () => (
      !matchesCacheBenchmarkResponse(500, payload(), expected)
      && !matchesCacheBenchmarkResponse(200, { ...payload(), success: false }, expected)
      && !matchesCacheBenchmarkResponse(200, payload((data) => ({ ...data, id: 2 })), expected)
      && !matchesCacheBenchmarkResponse(200, payload((data) => ({ ...data, is_in_wishlist: 'false' })), expected)
    ),
    'null malformed and partial response envelopes are rejected': () => (
      [null, {}, [], { success: true }, { success: true, data: null }]
        .every((value) => !matchesCacheBenchmarkResponse(200, value, expected))
    ),
    'same-key always selects the first fixture entry': () => sequence(same, 101).every((id) => id === 1),
    'uniform visits every key in fixture order and repeats exactly': () => (
      JSON.stringify(sequence(before, 40))
      === JSON.stringify([...Array.from({ length: 20 }, (_, i) => i + 1),
        ...Array.from({ length: 20 }, (_, i) => i + 1)])
    ),
    'hotset deterministic sequence has eight hot then two cold arrivals': () => (
      JSON.stringify(sequence(hot, 20))
      === JSON.stringify([1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 1, 2, 3, 4, 1, 2, 3, 4, 7, 8])
    ),
    'hotset traffic is exactly 80 percent hot over complete ten-arrival blocks': () => (
      [1, 2, 3, 4].every((id) => frequency.get(id) === 160)
      && Array.from({ length: 16 }, (_, i) => i + 5).every((id) => frequency.get(id) === 10)
    ),
    'hotset floors the first 20 percent of fixture keys': () => (
      run({ DISTRIBUTION: 'hotset-80-20' }, fixture(9)).hotKeys === 1
      && run({ DISTRIBUTION: 'hotset-80-20' }, fixture(11)).hotKeys === 2
    ),
    'variant changes do not change the request sequence': () => (
      JSON.stringify(sequence(before, 63)) === JSON.stringify(sequence(after, 63))
    ),
    'negative and fractional iteration indexes are rejected': () => (
      [-1, 0.5, NaN].every((value) => rejects(() => selectCacheBenchmarkAccommodation(before, value)))
    ),
    'arrival load and required completed samples match rate times duration': () => (
      benchmarkOptions.scenarios.measure.executor === 'constant-arrival-rate'
      && benchmarkOptions.scenarios.measure.rate === 10
      && benchmarkOptions.scenarios.measure.duration === '2s'
      && before.minimumCompletedSamples === 20
      && benchmarkOptions.thresholds[CACHE_BENCHMARK_METRICS.completed][0] === 'count>=20'
    ),
    'errors and dropped arrivals fail k6 thresholds': () => (
      benchmarkOptions.thresholds[CACHE_BENCHMARK_METRICS.success][0] === 'rate==1'
      && benchmarkOptions.thresholds.http_req_failed[0] === 'rate==0'
      && benchmarkOptions.thresholds.dropped_iterations[0] === 'count==0'
    ),
    'default VU allocation is bounded at high requested rates': () => (
      run({ RATE: '100000' }).preAllocatedVUs === 200
      && run({ RATE: '100000' }).maxVUs <= 1000
      && run({ PRE_ALLOCATED_VUS: '30', MAX_VUS: '40' }).maxVUs === 40
    ),
    'compound whole-second durations produce exact expected counts': () => (
      run({ DURATION: '1m30s' }).minimumCompletedSamples === 900
    ),
    'warmup needs no result file and cannot generate measurement artifact': () => (
      warmup.resultPath === null
      && buildCacheBenchmarkOptions(warmup).scenarios.warmup !== undefined
      && rejects(() => summarizeCacheBenchmark(metrics(), warmup))
    ),
    'artifact latency and RPS use only measurement samples and duration': () => (
      validSummary.validity.status === 'valid'
      && validSummary.load.achievedRps === 10
      && validSummary.load.iterations.successful === 20
      && validSummary.performance.errorRate === 0
      && JSON.stringify(validSummary.performance.latencyMs)
        === JSON.stringify({ avg: 4, min: 1, p50: 3, p95: 8, p99: 10, max: 12 })
    ),
    'artifact binds phase variant distribution fixture and app revision': () => (
      validSummary.metadata.phase === 'measure'
      && validSummary.metadata.variant === 'before'
      && validSummary.metadata.distribution === 'uniform'
      && validSummary.metadata.datasetId === 'offline-cache-detail'
      && validSummary.metadata.fixtureSha256 === before.fixture.sha256
      && /^[a-f0-9]{64}$/.test(before.fixture.sha256)
      && validSummary.metadata.appCommit === environment.APP_COMMIT
    ),
    'artifact never contains token response data or service origin': () => (
      !JSON.stringify(validSummary).includes(environment.BENCHMARK_READ_MODEL_TOKEN)
      && !JSON.stringify(validSummary).includes('Stay 1')
      && !JSON.stringify(validSummary).includes('127.0.0.1')
    ),
    'failed samples invalidate result even with HTTP 200': () => (
      failedSummary.validity.status === 'invalid'
      && failedSummary.validity.reasons.includes('request-errors')
      && failedSummary.performance.errorRate === 0.05
    ),
    'dropped arrivals and insufficient completed samples each invalidate result': () => (
      droppedSummary.validity.reasons.includes('dropped-iterations')
      && insufficientSummary.validity.reasons.includes('minimum-samples-not-met')
    ),
    'missing or inconsistent metrics cannot be reported as valid': () => (
      summarizeCacheBenchmark({}, before).validity.status === 'invalid'
      && summarizeCacheBenchmark(metrics({
        [CACHE_BENCHMARK_METRICS.success]: { values: { passes: 19, fails: 0 } },
      }), before).validity.reasons.includes('inconsistent-sample-counts')
      && summarizeCacheBenchmark(metrics({
        [CACHE_BENCHMARK_METRICS.duration]: { values: {} },
      }), before).validity.reasons.includes('missing-latency-samples')
    ),
  });

  const invalidConfigurations = [
    ['missing token', { BENCHMARK_READ_MODEL_TOKEN: undefined }],
    ['header injection', { BENCHMARK_READ_MODEL_TOKEN: 'value\r\nOther: bad' }],
    ['URL credentials', { BASE_URL: 'http://user:pass@example.com' }],
    ['URL path', { BASE_URL: 'http://example.com/api' }],
    ['URL query', { BASE_URL: 'http://example.com?token=wrong' }],
    ['URL invalid port', { BASE_URL: 'http://example.com:65536' }],
    ['relative fixture', { CACHE_BENCHMARK_FIXTURE: 'fixture.json' }],
    ['unknown variant', { VARIANT: 'disabled' }],
    ['unknown mode', { MODE: 'inspect' }],
    ['unknown distribution', { DISTRIBUTION: 'random' }],
    ['zero rate', { RATE: '0' }],
    ['fractional rate', { RATE: '1.5' }],
    ['unsafe rate', { RATE: '9007199254740992' }],
    ['zero duration', { DURATION: '0s' }],
    ['fractional duration', { DURATION: '0.5s' }],
    ['malformed duration', { DURATION: '3seconds' }],
    ['insufficient maximum VUs', { PRE_ALLOCATED_VUS: '11', MAX_VUS: '10' }],
    ['zero VUs', { PRE_ALLOCATED_VUS: '0' }],
    ['missing run label', { RUN_LABEL: undefined }],
    ['short revision', { APP_COMMIT: 'abc123' }],
    ['missing result file', { RESULT_PATH: undefined }],
    ['fixture overwrite', { RESULT_PATH: environment.CACHE_BENCHMARK_FIXTURE }],
  ];
  for (const [label, overrides] of invalidConfigurations) {
    check(null, { [`configuration rejects ${label}`]: () => rejects(() => run(overrides)) });
  }
  const invalidFixtures = [
    ['invalid JSON', 'not-json'],
    ['unknown schema', JSON.stringify({ ...fixture(), schemaVersion: 2 })],
    ['missing dataset id', JSON.stringify({ ...fixture(), datasetId: '' })],
    ['empty list', JSON.stringify(fixture(0))],
    ['oversized list', JSON.stringify(fixture(1001))],
    ['duplicate ids', JSON.stringify({ ...fixture(), accommodations: [fixture(1).accommodations[0], fixture(1).accommodations[0]] })],
    ['nonpositive id', JSON.stringify({ ...fixture(), accommodations: [{ id: 0, data: { id: 0 } }] })],
    ['unsafe id', JSON.stringify({ ...fixture(), accommodations: [{ id: Number.MAX_SAFE_INTEGER + 1, data: { id: Number.MAX_SAFE_INTEGER + 1 } }] })],
    ['mismatched data id', JSON.stringify({ ...fixture(), accommodations: [{ id: 1, data: { id: 2 } }] })],
    ['missing data object', JSON.stringify({ ...fixture(), accommodations: [{ id: 1, data: null }] })],
  ];
  for (const [label, raw] of invalidFixtures) {
    check(null, { [`fixture rejects ${label}`]: () => rejects(() => parseCacheBenchmarkFixture(raw)) });
  }
  check(null, {
    'hotset rejects fewer than five fixture keys': () => (
      rejects(() => run({ DISTRIBUTION: 'hotset-80-20' }, fixture(4)))
    ),
    'one-key fixtures support same-key and uniform': () => (
      run({ DISTRIBUTION: 'same-key' }, fixture(1)).fixture.accommodations.length === 1
      && run({ DISTRIBUTION: 'uniform' }, fixture(1)).fixture.accommodations.length === 1
    ),
    'valid IPv6 origins are preserved': () => run({ BASE_URL: 'http://[::1]:8080' }).baseUrl === 'http://[::1]:8080',
  });
  completed.add(1);
}

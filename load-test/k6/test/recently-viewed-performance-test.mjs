import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
// k6 uses ES modules in .js files without a Node package.json.
const moduleUrl = (text) => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const manifestUrl = moduleUrl(readFileSync(new URL('../lib/benchmark-manifest.js', import.meta.url), 'utf8'));
const helperSource = readFileSync(new URL('../lib/recently-viewed-benchmark.js', import.meta.url), 'utf8')
  .replace('./benchmark-manifest.js', manifestUrl);
const { assertRecentlyViewedPreflight, matchesRecentlyViewedData,
  matchesRecentlyViewedFixture, parseRecentlyViewedBenchmarkManifest } = await import(moduleUrl(helperSource));

const source = readFileSync(new URL('../recently-viewed-nplus1-performance.js', import.meta.url), 'utf8')
  .replace(/import[\s\S]*?from\s+'[^']+';/g, '')
  .replace(/export (const|function)/g, '$1');
const manifest = {
  datasetVersion: 'recently-viewed-v1', datasetId: 'raw-review-test',
  account: { email: 'benchmark@example.test' },
  recentlyViewed: { maxRows: 1, accommodationIds: [251] },
};

function harness(overrides = {}) {
  const requests = [];
  const samples = [];
  let logins = 0;
  let payload = { data: { total_count: 1, accommodations: [{
    accommodation_id: 251, viewed_at: '2026-10-12T00:00:00Z',
    review_summary: { total_count: 2, average_rating: 4.5 },
  }] } };
  const context = vm.createContext({
    __ENV: { BENCHMARK_MANIFEST: 'fixture.json', BENCHMARK_READ_MODEL_TOKEN: 'test-token',
      BENCHMARK_SESSION_ID: 'owned-session', EXPECTED_ROWS: '1', VARIANT: 'after', ...overrides },
    open: () => JSON.stringify(manifest),
    http: { get: (url, params) => {
      requests.push({ url, params });
      return { status: 200, json: () => structuredClone(payload), timings: { duration: 1 } };
    } },
    check: () => {}, fail: (message) => { throw new Error(message); },
    Trend: class { add() {} },
    Rate: class { add(value) { samples.push(value); } },
    authenticatedParams: (sessionId, tags) => ({ cookies: { SESSION_ID: sessionId }, tags }),
    benchmarkHeaders: (token) => ({ 'X-Benchmark-Token': token }),
    loginBenchmarkAccount: () => { logins++; return 'login-session'; },
    resetRecentlyViewed: () => {},
    assertRecentlyViewedPreflight, matchesRecentlyViewedData,
    matchesRecentlyViewedFixture, parseRecentlyViewedBenchmarkManifest,
  });
  vm.runInContext(source + '\nglobalThis.harness = { setup, measure, handleSummary, options };', context);
  return { ...context.harness, requests, samples, logins: () => logins,
    changeReview: () => { payload.data.accommodations[0].review_summary.total_count++; } };
}

test('both preflights and the measured after use protected raw-review benchmark endpoints', () => {
  const h = harness();
  h.measure(h.setup());
  assert.deepEqual(h.requests.map((r) => new URL(r.url).pathname), [
    '/api/v2/members/recently-viewed/nplus1-before',
    '/api/v2/members/recently-viewed/nplus1-after',
    '/api/v2/members/recently-viewed/nplus1-after',
  ]);
  assert.ok(h.requests.every((r) => r.params.headers['X-Benchmark-Token'] === 'test-token'));
  assert.ok(h.requests.every((r) => r.params.cookies.SESSION_ID === 'owned-session'));
  assert.ok(h.requests.every((r) => r.params.timeout === '5s'));
  assert.equal(h.logins(), 0);
  assert.deepEqual(h.samples, [true]);
});

test('a changed review summary fails the measured request even when IDs and status match', () => {
  const h = harness();
  const data = h.setup();
  h.changeReview();
  h.measure(data);
  assert.deepEqual(h.samples, [false]);
});

test('password login remains available and missing credentials fail before any request', () => {
  const h = harness({ BENCHMARK_SESSION_ID: '', TEST_PASSWORD: 'test-password' });
  h.setup();
  assert.equal(h.logins(), 1);
  assert.throws(() => harness({ BENCHMARK_SESSION_ID: '' }), /TEST_PASSWORD or BENCHMARK_SESSION_ID/);
});

test('summary identifies the raw-review baseline and the three-query after without credentials', () => {
  const h = harness();
  const result = JSON.parse(h.handleSummary({ metrics: {} }).stdout);
  assert.equal(result.review_summary_source, 'raw-review-aggregation');
  assert.equal(result.expected_select_queries_per_request, 3);
  assert.equal(result.endpoint, '/api/v2/members/recently-viewed/nplus1-after');
  assert.ok(!JSON.stringify(result).includes('owned-session'));
  assert.equal(h.options.scenarios.measure.maxVUs, h.options.scenarios.measure.preAllocatedVUs);
});

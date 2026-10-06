import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseConfig, targetPath, buildOptions, matchesContract, canonicalData, summarize, comparePair, METRICS } from '../review-summary/lib.mjs';

const environment = {
  TARGET: 'accommodation-detail', VARIANT: 'before', ACCOMMODATION_ID: '30',
  BENCHMARK_READ_MODEL_TOKEN: 'test-token', RESULT_PATH: '/tmp/review-result.json',
};
const config = (overrides = {}) => parseConfig({ ...environment, ...overrides });
const fixture = (name) => JSON.parse(readFileSync(new URL(`../../../src/test/resources/contracts/${name}.json`, import.meta.url), 'utf8'));
const detail = fixture('public-accommodation-detail');
const wishlist = fixture('wishlist-detail-without-reviews');
const recent = fixture('recently-viewed-mixed-history');
const member = { BENCHMARK_SESSION_ID: '00000000-0000-0000-0000-000000000001' };
const wishlistConfig = config({ ...member, TARGET: 'wishlist-accommodations', WISHLIST_ID: '42', EXPECTED_ROWS: '1' });
const recentConfig = config({ ...member, TARGET: 'recently-viewed', EXPECTED_ROWS: '3' });

function metricData() {
  return {
    setup_data: { verified: true, responseHash: 'a'.repeat(64), sessionId: 'private-session' },
    metrics: {
      [METRICS.started]: { values: { count: 600 } },
      [METRICS.completed]: { values: { count: 600 } },
      [METRICS.success]: { values: { passes: 600, fails: 0 } },
      [METRICS.duration]: { values: { count: 600, med: 10, 'p(95)': 20, 'p(99)': 30, max: 40 } },
      [METRICS.completionTime]: { values: { max: 61000 } },
      'dropped_iterations{scenario:measure}': { values: { count: 0 } },
      // 로그인·워밍업이 느려도 측정 지연이나 처리량 계산에 섞이지 않는다.
      http_req_duration: { values: { med: 9999, 'p(95)': 9999 } },
      http_reqs: { values: { count: 902 } },
    },
  };
}

test('상세 After는 비캐시 V2이고 최근 본 Before는 N+1 경로가 아니다', () => {
  assert.equal(targetPath(config(), 'before'), '/api/v2/accommodations/30/review-summary-before');
  assert.equal(targetPath(config(), 'after'), '/api/v2/accommodations/30');
  assert.equal(targetPath(recentConfig, 'before'), '/api/v2/members/recently-viewed/review-summary-before');
  assert.equal(targetPath(recentConfig, 'after'), '/api/v1/members/recently-viewed');
  const withCursor = { ...wishlistConfig, cursor: 'ab+/=' };
  for (const variant of ['before', 'after']) {
    assert.match(targetPath(withCursor, variant), /size=20&cursor=ab%2B%2F%3D$/);
  }
});

test('예상 행 수·회원 세션·페이지 상한과 요청률을 잘못 지정하면 실행하지 않는다', () => {
  for (const overrides of [
    { RATE: '0' }, { RATE: '-1' }, { MEASURE_DURATION: 'bad' }, { VARIANT: 'both' },
    { BENCHMARK_READ_MODEL_TOKEN: 'token\r\nheader' }, { BASE_URL: 'http://user:pass@localhost' },
    { TARGET: 'recently-viewed', EXPECTED_ROWS: '3' },
    { ...member, TARGET: 'recently-viewed' },
    { ...member, TARGET: 'recently-viewed', EXPECTED_ROWS: '101' },
    { ...member, TARGET: 'wishlist-accommodations', WISHLIST_ID: '42', EXPECTED_ROWS: '51', PAGE_SIZE: '51' },
    { ...member, BENCHMARK_EMAIL: 'test@example.com', BENCHMARK_PASSWORD: 'password' },
    { BENCHMARK_EMAIL: 'test@example.com' }, { PRE_ALLOCATED_VUS: '20', MAX_VUS: '19' },
  ]) assert.throws(() => config(overrides));
  assert.equal(config({ ...member, TARGET: 'recently-viewed', EXPECTED_ROWS: '0' }).expectedRows, 0);
});

test('워밍업의 진행 중 요청이 끝난 후 측정하고 측정 지표에만 임계값을 적용한다', () => {
  const options = buildOptions(config());
  assert.equal(options.scenarios.measure.startTime, '38s');
  assert.equal(options.scenarios.warmup.gracefulStop, '6s');
  assert.equal(options.scenarios.measure.rate, options.scenarios.warmup.rate);
  assert.equal(options.scenarios.measure.executor, 'constant-arrival-rate');
  assert.deepEqual(options.thresholds['dropped_iterations{scenario:measure}'], ['count==0']);
});

test('저장소의 실제 응답 계약을 사용하고 빈 목록·중복·잘못된 평점을 구분한다', () => {
  for (const [c, data] of [[config(), detail], [wishlistConfig, wishlist], [recentConfig, recent]]) {
    assert.equal(matchesContract(c, { success: true, data }), true);
    assert.equal(matchesContract(c, { success: false, data }), false);
  }
  const changed = structuredClone(recent);
  changed.accommodations[1] = changed.accommodations[0];
  assert.equal(matchesContract(recentConfig, { success: true, data: changed }), false);
  const wrongRating = { ...detail, review_summary: { total_count: 0, average_rating: 5 } };
  assert.equal(matchesContract(config(), { success: true, data: wrongRating }), false);
  assert.equal(matchesContract(recentConfig, { success: true, data: { accommodations: [], total_count: 0 } }), false);
  assert.equal(matchesContract({ ...recentConfig, expectedRows: 0 }, { success: true, data: { accommodations: [], total_count: 0 } }), true);
  assert.equal(matchesContract(wishlistConfig, { success: true, data: { ...wishlist, page_info: { ...wishlist.page_info, current_size: 2 } } }), false);
});

test('편의시설 순서만 정규화하고 이미지·숙소 순서 및 리뷰 값의 변경은 감지한다', () => {
  assert.equal(canonicalData(config().target, detail), canonicalData(config().target, { ...detail, amenities: [...detail.amenities].reverse() }));
  assert.notEqual(canonicalData(config().target, detail), canonicalData(config().target, { ...detail, images: [...detail.images].reverse() }));
  assert.notEqual(canonicalData(recentConfig.target, recent), canonicalData(recentConfig.target, { ...recent, accommodations: [...recent.accommodations].reverse() }));
  assert.notEqual(canonicalData(config().target, detail), canonicalData(config().target, { ...detail, review_summary: { total_count: 5, average_rating: 4.5 } }));
});

test('요약은 측정만 집계하고 지연된 마지막 요청과 세션 비공개를 처리한다', () => {
  const result = summarize(config(), metricData());
  assert.equal(result.valid, true);
  assert.equal(result.measurement.completed, 600);
  assert.equal(result.measurement.latencyMs.p95, 20);
  assert.equal(result.measurement.achievedRps, 600 / 61);
  assert.equal(JSON.stringify(result).includes('private-session'), false);
  assert.equal(JSON.stringify(result).includes('test-token'), false);
});

test('누락·중단·오류·미검증·표본 부족을 성공 결과로 표시하지 않는다', () => {
  const missing = summarize(config(), {});
  assert.equal(missing.valid, false);
  for (const change of [
    (d) => { d.metrics['dropped_iterations{scenario:measure}'].values.count = 1; },
    (d) => { d.metrics[METRICS.started].values.count = 601; },
    (d) => { d.metrics[METRICS.success].values = { passes: 599, fails: 1 }; },
    (d) => { d.setup_data.verified = false; },
    (d) => { d.metrics[METRICS.completed].values.count = 599; },
  ]) {
    const data = metricData(); change(data);
    assert.equal(summarize(config(), data).valid, false);
  }
});

test('전후 조건 또는 응답이 달라지면 개선율을 계산하지 않는다', () => {
  const before = summarize(config(), metricData());
  const after = summarize(config({ VARIANT: 'after' }), metricData());
  after.measurement.latencyMs.p95 = 10;
  assert.equal(comparePair(before, after).latencyReductionPercent.p95, 50);
  for (const changed of [
    { ...after, responseHash: 'b'.repeat(64) }, { ...after, valid: false },
    { ...after, load: { ...after.load, rate: 20 } }, { ...after, parameters: { ...after.parameters, targetId: 31 } },
  ]) assert.throws(() => comparePair(before, changed));
});

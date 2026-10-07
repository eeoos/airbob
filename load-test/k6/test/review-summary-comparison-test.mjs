import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const script = fileURLToPath(new URL('../review-summary/comparison.js', import.meta.url));
const runner = fileURLToPath(new URL('../review-summary/run-comparison.mjs', import.meta.url));
const load = (name) => JSON.parse(readFileSync(new URL(`../../../src/test/resources/contracts/${name}.json`, import.meta.url), 'utf8'));
const fixtures = {
  detail: load('public-accommodation-detail'), wishlist: load('wishlist-detail-without-reviews'), recent: load('recently-viewed-mixed-history'),
};
const token = 'offline-review-token';
const session = '00000000-0000-0000-0000-000000000007';
const password = 'offline-review-password';

function execute(command, args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', (chunk) => { output += chunk; });
    child.stderr.on('data', (chunk) => { output += chunk; });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, output }));
  });
}

async function withServer(mode, action) {
  const directory = mkdtempSync(join(tmpdir(), 'airbob-review-k6-'));
  const observed = [];
  let firstReadAt;
  let reads = 0;
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://localhost');
    const entry = { method: request.method, path: url.pathname, query: url.search, token: request.headers['x-benchmark-token'] === token,
      session: request.headers.cookie === `SESSION_ID=${session}` };
    observed.push(entry);
    if (url.pathname === '/api/v1/auth/login' && request.method === 'POST') {
      let body = '';
      request.on('data', (chunk) => { body += chunk; });
      request.on('end', () => {
        const valid = JSON.parse(body).password === password;
        response.writeHead(valid ? 200 : 401, { 'Set-Cookie': `SESSION_ID=${session}; Path=/`, 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ success: valid }));
      });
      return;
    }
    let kind;
    if (/^\/api\/v2\/accommodations\/30(?:\/review-summary-before)?$/.test(url.pathname)) kind = 'detail';
    if (['/api/v1/members/wishlists/accommodations/42', '/api/v2/members/wishlists/accommodations/42/review-summary-before'].includes(url.pathname)) kind = 'wishlist';
    if (['/api/v1/members/recently-viewed', '/api/v2/members/recently-viewed/review-summary-before'].includes(url.pathname)) kind = 'recent';
    if (request.method !== 'GET' || !kind || !entry.token || (kind !== 'detail' && !entry.session)) {
      response.writeHead(403).end(); return;
    }
    const before = url.pathname.endsWith('/review-summary-before');
    reads++;
    firstReadAt ??= Date.now();
    const data = structuredClone(fixtures[kind]);
    if (kind === 'detail' && !before) data.amenities.reverse();
    if (mode === 'parity' && !before) data.review_summary.total_count++;
    if (mode === 'warmup-drift' && reads > 2) data.review_summary.total_count++;
    const measured = Date.now() - firstReadAt > 2500;
    const respond = () => {
      if (mode === 'redirect') { response.writeHead(302, { location: '/not-allowed' }).end(); return; }
      if (mode === 'expired-session' && measured) { response.writeHead(401).end(); return; }
      response.writeHead(200, { 'Content-Type': 'application/json', 'Set-Cookie': 'SESSION_ID=should-not-be-used; Path=/' });
      if (mode === 'invalid-json' || (mode === 'measure-json' && measured)) { response.end('{invalid'); return; }
      response.end(JSON.stringify({ success: true, data }));
    };
    if (mode === 'slow') setTimeout(respond, 150); else respond();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  // 테스트에는 가짜 자격 증명과 로컬 서버만 전달한다. 사용자의 k6 설정은 상속하지 않는다.
  const env = {
    PATH: process.env.PATH, K6_NO_USAGE_REPORT: 'true',
    BASE_URL: `http://127.0.0.1:${server.address().port}`,
    BENCHMARK_READ_MODEL_TOKEN: token, TARGET: 'accommodation-detail', ACCOMMODATION_ID: '30', VARIANT: 'before',
    WARMUP_DURATION: '1s', MEASURE_DURATION: '1s', REQUEST_TIMEOUT: '1s', SETTLE_SECONDS: '0',
    PRE_ALLOCATED_VUS: '5', MAX_VUS: '5', RATE: '10', RESULT_PATH: join(directory, 'result.json'),
  };
  const run = (overrides = {}) => execute(process.env.K6_BIN || 'k6', ['run', '--address', '', '--quiet', script], { ...env, ...overrides });
  try { await action({ run, env, directory, observed }); }
  finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
}

for (const target of ['accommodation-detail', 'wishlist-accommodations', 'recently-viewed']) {
  test(`${target}: 실제 k6로 두 경로 검증·로그인·측정 분리 확인`, async () => {
    await withServer('success', async ({ run, env, observed }) => {
      const overrides = target === 'wishlist-accommodations'
        ? { TARGET: target, VARIANT: 'after', WISHLIST_ID: '42', PAGE_SIZE: '20', CURSOR: 'ab+/=', EXPECTED_ROWS: '1', BENCHMARK_EMAIL: 'offline@example.test', BENCHMARK_PASSWORD: password }
        : target === 'recently-viewed' ? { TARGET: target, EXPECTED_ROWS: '3', BENCHMARK_SESSION_ID: session } : {};
      const execution = await run(overrides);
      assert.equal(execution.code, 0, execution.output);
      const result = JSON.parse(readFileSync(env.RESULT_PATH, 'utf8'));
      assert.equal(result.valid, true);
      assert.equal(result.measurement.started, result.measurement.completed);
      assert.ok(result.measurement.completed >= 10);
      assert.equal(result.measurement.dropped, 0);
      assert.equal(result.measurement.errorRate, 0);
      assert.match(result.responseHash, /^[0-9a-f]{64}$/);
      assert.ok(Number.isFinite(Date.parse(result.measurement.startedAt)));
      assert.ok(Date.parse(result.measurement.finishedAt) > Date.parse(result.measurement.startedAt));
      const gets = observed.filter((row) => row.method === 'GET');
      assert.ok(gets.length > result.measurement.completed + 2); // 워밍업은 결과 표본에서 제외
      assert.ok(gets.every((row) => row.token && (target === 'accommodation-detail' ? !row.session : row.session)));
      if (target === 'wishlist-accommodations') assert.ok(gets.every((row) => row.query.endsWith('cursor=ab%2B%2F%3D')));
      for (const secret of [token, session, password, '공개 상세 계약 숙소']) {
        assert.ok(!`${execution.output}${JSON.stringify(result)}`.includes(secret));
      }
      assert.ok(observed.every((row) => row.method === 'GET' || row.path === '/api/v1/auth/login'));
    });
  });
}

for (const mode of ['parity', 'redirect', 'invalid-json']) {
  test(`${mode}: 사전 검증 실패 시 측정을 시작하지 않는다`, async () => {
    await withServer(mode, async ({ run, observed }) => {
      const result = await run();
      assert.notEqual(result.code, 0);
      assert.equal(observed.length, 2);
      assert.ok(observed.every((row) => row.path !== '/not-allowed'));
    });
  });
}

test('빈 목록을 의도하지 않았다면 잘못 준비된 최근 본 기록을 거부한다', async () => {
  await withServer('success', async ({ run, observed }) => {
    const result = await run({ TARGET: 'recently-viewed', EXPECTED_ROWS: '20', BENCHMARK_SESSION_ID: session });
    assert.notEqual(result.code, 0);
    assert.equal(observed.length, 2);
  });
});

test('DNS에 없는 호스트도 지정 IP로 접속하고 잘못된 리뷰 합계는 부하 전에 거부한다', async () => {
  await withServer('success', async ({ run, env, observed }) => {
    const BASE_URL = env.BASE_URL.replace('127.0.0.1', 'review-summary.invalid');
    const success = await run({ BASE_URL, TARGET_IP: '127.0.0.1', EXPECTED_REVIEW_COUNT: String(fixtures.detail.review_summary.total_count) });
    assert.equal(success.code, 0, success.output);
    const previous = observed.length;
    const rejected = await run({ BASE_URL, TARGET_IP: '127.0.0.1', EXPECTED_REVIEW_COUNT: String(fixtures.detail.review_summary.total_count + 1) });
    assert.notEqual(rejected.code, 0);
    assert.equal(observed.length - previous, 2);
  });
});

test('워밍업 중 데이터가 바뀌면 측정을 중단한다', async () => {
  await withServer('warmup-drift', async ({ run, env }) => {
    const execution = await run();
    assert.notEqual(execution.code, 0);
    const result = JSON.parse(readFileSync(env.RESULT_PATH, 'utf8'));
    assert.equal(result.valid, false);
    assert.equal(result.measurement.completed, 0);
  });
});

for (const mode of ['expired-session', 'measure-json']) {
  test(`${mode}: 측정 중 응답 오류를 실패율과 종료 코드에 반영한다`, async () => {
    await withServer(mode, async ({ run, env }) => {
      const execution = await run();
      assert.notEqual(execution.code, 0);
      const result = JSON.parse(readFileSync(env.RESULT_PATH, 'utf8'));
      assert.equal(result.valid, false);
      assert.ok(result.measurement.failed > 0);
      assert.ok(result.reasons.includes('response-errors'));
    });
  });
}

test('느린 서버에서 발생한 dropped_iterations를 개선 결과로 인정하지 않는다', async () => {
  await withServer('slow', async ({ run, env }) => {
    const execution = await run({ RATE: '30', PRE_ALLOCATED_VUS: '1', MAX_VUS: '1' });
    assert.notEqual(execution.code, 0);
    const result = JSON.parse(readFileSync(env.RESULT_PATH, 'utf8'));
    assert.equal(result.valid, false);
    assert.ok(result.measurement.dropped > 0);
    assert.ok(result.reasons.includes('dropped-iterations'));
  });
});

test('실행기는 AB·BA 순서로 실행하고 회차별 비교 파일을 만든다', async () => {
  await withServer('success', async ({ env, directory }) => {
    const execution = await execute(process.execPath, [runner], { ...env, ROUNDS: '2', RESULT_DIR: directory, K6_BIN: process.env.K6_BIN || 'k6' });
    assert.equal(execution.code, 0, execution.output);
    const child = readdirSync(directory).find((name) => name.startsWith('accommodation-detail-'));
    const result = JSON.parse(readFileSync(join(directory, child, 'comparison.json'), 'utf8'));
    assert.deepEqual(result.pairs.map((pair) => pair.order), [['before', 'after'], ['after', 'before']]);
    assert.ok(result.pairs.every((pair) => Number.isFinite(pair.latencyReductionPercent.p95)));
  });
});

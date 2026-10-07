import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import childProcess from 'node:child_process';
import { promises as dns } from 'node:dns';
import { EventEmitter } from 'node:events';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { caseEnvironment, prepare, readPrepared, requestRuntime, runAws, validateConfig, verifyRuntime } from '../review-summary/aws.mjs';

const example = JSON.parse(readFileSync(new URL('../review-summary/aws-experiment.example.json', import.meta.url)));
const script = fileURLToPath(new URL('../review-summary/aws.mjs', import.meta.url));
const token = 'private-test-token';
const secret = 'private-test-password';
function config() {
  const c = structuredClone(example);
  c.example = false;
  Object.assign(c.aws, { albDnsName: 'owned-lab.ap-northeast-2.elb.amazonaws.com',
    appInstanceId: 'i-1234567890abcdef1', loadGeneratorInstanceId: 'i-1234567890abcdef2' });
  Object.assign(c.app, { imageDigest: `sha256:${'a'.repeat(64)}`, sourceCommit: 'b'.repeat(40),
    runId: 'review-test-run', resourceFencingTokenSha256: 'c'.repeat(64) });
  Object.assign(c.dataset, { id: 'test-v28', manifestSha256: 'd'.repeat(64) });
  return c;
}
function proof(c, challenge) {
  return { schema_version: 1, run_id: c.app.runId, resource_fencing_token_sha256: c.app.resourceFencingTokenSha256,
    challenge_sha256: challenge, runtime_revision: c.app.imageDigest.slice(7), app_instance_id: c.aws.appInstanceId,
    active_profiles: ['aws', 'read-model-benchmark', 'traffic-benchmark', 'performance-lab'],
    scheduler_enabled: false, kafka_listener_enabled: false, inventory_lifecycle_enabled: false, external_side_effects_enabled: false };
}
async function temporary(action) {
  const directory = mkdtempSync(join(tmpdir(), 'airbob-review-aws-'));
  try { return await action(directory); }
  finally { rmSync(directory, { recursive: true, force: true }); }
}
function noNetwork() { throw new Error('Unexpected network or subprocess call'); }

test('prepare는 DNS·HTTP·프로세스 실행 없이 파일과 해시만 생성한다', async () => {
  await temporary((directory) => {
    const original = { lookup: dns.lookup, request: https.request, spawn: childProcess.spawn, spawnSync: childProcess.spawnSync };
    dns.lookup = noNetwork; https.request = noNetwork; childProcess.spawn = noNetwork; childProcess.spawnSync = noNetwork;
    syncBuiltinESMExports();
    try {
      const output = join(directory, 'prepared');
      const plan = prepare(example, output);
      assert.equal(plan.awsCallsPerformed, 0);
      assert.equal(plan.networkCallsPerformed, 0);
      assert.equal(plan.readyToExecute, false);
      assert.equal(plan.commands.length, 21);
      assert.equal(statSync(output).mode & 0o777, 0o700);
      assert.equal(statSync(join(output, 'config.json')).mode & 0o777, 0o600);
      for (const line of readFileSync(join(output, 'SHA256SUMS'), 'utf8').trim().split('\n')) {
        const [expected, name] = line.split('  ');
        assert.equal(createHash('sha256').update(readFileSync(join(output, name))).digest('hex'), expected);
      }
      assert.deepEqual(readPrepared(output), example);
      assert.throws(() => prepare(example, output));
    } finally {
      dns.lookup = original.lookup; https.request = original.request;
      childProcess.spawn = original.spawn; childProcess.spawnSync = original.spawnSync;
      syncBuiltinESMExports();
    }
  });
});

test('예전 스키마·잘못된 대상·비밀 값·모호한 사례 설정을 거부한다', () => {
  for (const change of [
    (c) => { c.dataset.flywayVersion = 27; },
    (c) => { c.baseUrl = 'https://production.example.com'; },
    (c) => { c.aws.albDnsName = 'api.airbob.cloud'; },
    (c) => { c.aws.loadGeneratorInstanceId = c.aws.appInstanceId; },
    (c) => { c.token = secret; },
    (c) => { c.cases[2].password = secret; },
    (c) => { c.cases[1].id = c.cases[0].id; },
    (c) => { c.cases[2].expectedRows = 21; },
    (c) => { c.cases[2].authEnvPrefix = 'user@example.com'; },
    (c) => { c.cases[0].publishedReviewCount = -1; },
    (c) => { c.load.rates = [20, 20]; },
    (c) => { c.load.maxVUs = 1; },
  ]) { const c = config(); change(c); assert.throws(() => validateConfig(c)); }
  assert.throws(() => validateConfig({ ...example, example: false }));
  assert.deepEqual(validateConfig(config()), config());
});

test('준비 후 설정이나 실행 파일을 수정하면 실행 전에 거부한다', async () => {
  await temporary((directory) => {
    for (const file of ['config.json', 'load-test/k6/review-summary/lib.mjs']) {
      const output = join(directory, file === 'config.json' ? 'config' : 'source');
      prepare(config(), output);
      writeFileSync(join(output, file), `${readFileSync(join(output, file), 'utf8')}\n`);
      assert.throws(() => readPrepared(output), /변경/);
    }
  });
});

test('사례 인증만 전달하고 외부 k6·프록시·익명 요청의 세션 설정을 상속하지 않는다', () => {
  const c = config();
  const source = { PATH: process.env.PATH, BENCHMARK_READ_MODEL_TOKEN: token,
    BENCHMARK_SESSION_ID: '00000000-0000-0000-0000-000000000099',
    RS_WISHLIST_LIGHT_EMAIL: 'fixture@example.test', RS_WISHLIST_LIGHT_PASSWORD: secret,
    K6_INSECURE_SKIP_TLS_VERIFY: 'true', K6_HOSTS: 'api.airbob.cloud=127.0.0.1',
    HTTPS_PROXY: 'http://unexpected.test', AWS_SECRET_ACCESS_KEY: 'not-inherited', NODE_OPTIONS: '--trace-warnings' };
  const detail = caseEnvironment(c, c.cases[0], 20, source, '/tmp/results', '10.0.1.10');
  assert.equal(detail.BENCHMARK_SESSION_ID, undefined);
  for (const name of ['K6_HOSTS', 'K6_INSECURE_SKIP_TLS_VERIFY', 'HTTPS_PROXY', 'AWS_SECRET_ACCESS_KEY', 'NODE_OPTIONS']) {
    assert.equal(detail[name], undefined);
  }
  const wishlist = caseEnvironment(c, c.cases[2], 20, source, '/tmp/results', '10.0.1.10');
  assert.equal(wishlist.BENCHMARK_PASSWORD, secret);
  assert.equal(wishlist.BENCHMARK_EMAIL, 'fixture@example.test');
  assert.equal(wishlist.EXPECTED_REVIEW_COUNT, '200');
  const smoke = caseEnvironment(c, c.cases[2], 20, source, '/tmp/results', '10.0.1.10', true);
  assert.equal(smoke.RATE, '2'); assert.equal(smoke.ROUNDS, '1'); assert.equal(smoke.MEASURE_DURATION, '5s');
  assert.throws(() => caseEnvironment(c, c.cases[2], 20, { BENCHMARK_READ_MODEL_TOKEN: token }, '/tmp/results', '10.0.1.10'));
});

test('런타임의 앱·이미지·challenge·격리 설정이 다르면 실패한다', () => {
  const c = config(); const challenge = 'e'.repeat(64);
  assert.deepEqual(verifyRuntime(c, proof(c, challenge), challenge), proof(c, challenge));
  for (const change of [
    { run_id: 'different-run' }, { runtime_revision: 'f'.repeat(64) }, { app_instance_id: c.aws.loadGeneratorInstanceId },
    { challenge_sha256: 'f'.repeat(64) }, { scheduler_enabled: true }, { kafka_listener_enabled: true },
    { inventory_lifecycle_enabled: true }, { external_side_effects_enabled: true },
    { active_profiles: ['aws', 'read-model-benchmark', 'test'] },
  ]) assert.throws(() => verifyRuntime(c, { ...proof(c, challenge), ...change }, challenge));
});

test('예제 또는 누락된 인증으로는 DNS 조회와 k6 실행까지 도달하지 않는다', async () => {
  await temporary(async (directory) => {
    const deps = { platform: 'linux', lookup: noNetwork, requestRuntime: noNetwork, compare: noNetwork };
    const examplePath = join(directory, 'example'); prepare(example, examplePath);
    await assert.rejects(runAws(examplePath, join(directory, 'not-created'), { case: 'detail-light', rate: 20 }, {}, deps), /예제/);
    const realPath = join(directory, 'real'); prepare(config(), realPath);
    await assert.rejects(runAws(realPath, join(directory, 'not-created'), { case: 'wishlist-light', rate: 20 }, { BENCHMARK_READ_MODEL_TOKEN: token }, deps), /로그인/);
    assert.equal(existsSync(join(directory, 'not-created')), false);
  });
});

test('가짜 AWS 응답으로 각 버전 전후 검증과 동일 ALB IP·비밀 없는 결과를 확인한다', async () => {
  await temporary(async (directory) => {
    const prepared = join(directory, 'prepared'); const c = config(); prepare(c, prepared);
    const output = join(directory, 'results'); const seen = [];
    const result = await runAws(prepared, output, { case: 'detail-light', rate: 20 }, { PATH: process.env.PATH, BENCHMARK_READ_MODEL_TOKEN: token }, {
      platform: 'linux', lookup: async (host) => {
        assert.equal(host, c.aws.albDnsName); return [{ address: '10.0.1.10', family: 4 }];
      },
      requestRuntime: async (cfg, ip, provided, challenge) => {
        assert.equal(ip, '10.0.1.10'); assert.equal(provided, token); seen.push(challenge); return proof(cfg, challenge);
      },
      compare: async (env, hooks) => {
        assert.equal(env.BASE_URL, 'https://api.airbob.cloud'); assert.equal(env.TARGET_IP, '10.0.1.10');
        const child = join(output, 'comparison'); mkdirSync(child);
        for (const variant of ['before', 'after']) {
          const context = { round: 1, variant, directory: child };
          await hooks.beforeRun(context); await hooks.afterRun(context);
        }
        return child;
      },
    });
    assert.equal(result.status, 'complete'); assert.equal(new Set(seen).size, 4);
    const saved = readFileSync(join(output, 'run.json'), 'utf8');
    assert.ok(!saved.includes(token)); assert.ok(!saved.includes(secret));
    assert.equal(JSON.parse(saved).targetIp, '10.0.1.10');
    assert.ok(existsSync(join(output, 'comparison/1-after-runtime-after.json')));
  });
});

test('측정 뒤 앱 식별자가 바뀌면 완료 상태로 기록하지 않는다', async () => {
  await temporary(async (directory) => {
    const prepared = join(directory, 'prepared'); prepare(config(), prepared);
    const output = join(directory, 'failed'); let requests = 0;
    await assert.rejects(runAws(prepared, output, { case: 'detail-light', rate: 20 }, { BENCHMARK_READ_MODEL_TOKEN: token }, {
      platform: 'linux', lookup: async () => [{ address: '10.0.1.10', family: 4 }],
      requestRuntime: async (c, _ip, _token, challenge) => ({ ...proof(c, challenge), scheduler_enabled: ++requests > 1 }),
      compare: async (_env, hooks) => {
        const child = join(output, 'comparison'); mkdirSync(child);
        const context = { round: 1, variant: 'before', directory: child };
        await hooks.beforeRun(context); await hooks.afterRun(context);
        throw new Error('Must not complete');
      },
    }), /백그라운드/);
    assert.equal(JSON.parse(readFileSync(join(output, 'run.json'))).status, 'failed');
  });
});

test('HTTPS 검증 요청은 SNI·인증서 검증을 유지하고 리다이렉트를 따르지 않는다', async () => {
  const original = https.request; const c = config(); const challenge = 'e'.repeat(64);
  let status = 200;
  https.request = (url, options, callback) => {
    assert.equal(url.hostname, 'api.airbob.cloud'); assert.equal(options.servername, url.hostname);
    assert.equal(options.rejectUnauthorized, true); assert.equal(options.headers['X-Benchmark-Token'], token);
    options.lookup(url.hostname, { all: true }, (error, values) => {
      assert.equal(error, null); assert.deepEqual(values, [{ address: '10.0.1.10', family: 4 }]);
    });
    const request = new EventEmitter();
    request.end = (body) => {
      assert.equal(JSON.parse(body).challenge_sha256, challenge);
      queueMicrotask(() => {
        const response = new EventEmitter(); response.statusCode = status; response.setEncoding = () => {};
        callback(response); response.emit('data', JSON.stringify(proof(c, challenge))); response.emit('end'); request.emit('close');
      });
    };
    return request;
  };
  try {
    assert.deepEqual(await requestRuntime(c, '10.0.1.10', token, challenge), proof(c, challenge));
    status = 302;
    await assert.rejects(requestRuntime(c, '10.0.1.10', token, challenge), /검증 실패/);
  } finally { https.request = original; }
});

test('CLI prepare와 복사된 실행기는 AWS 도구나 자격증명 없이 동작한다', async () => {
  await temporary((directory) => {
    const output = join(directory, 'prepared');
    const result = childProcess.spawnSync(process.execPath, [script, 'prepare', '--output', output], {
      encoding: 'utf8', env: { PATH: directory, BENCHMARK_READ_MODEL_TOKEN: token, AWS_SECRET_ACCESS_KEY: secret },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /AWS 호출 0회/);
    const rejected = childProcess.spawnSync(process.execPath, [join(output, 'load-test/k6/review-summary/aws.mjs'),
      'run', '--prepared', output, '--output', join(directory, 'unused'), '--case', 'detail-light', '--rate', '20'],
    { encoding: 'utf8', env: { PATH: directory } });
    assert.notEqual(rejected.status, 0); assert.match(rejected.stderr, /예제/);
    const noConfig = childProcess.spawnSync(process.execPath, [join(output, 'load-test/k6/review-summary/run-comparison.mjs')],
      { encoding: 'utf8', env: { PATH: directory } });
    assert.notEqual(noConfig.status, 0); assert.match(noConfig.stderr, /TARGET/);
    const imported = childProcess.spawnSync(process.execPath, ['--input-type=module', '-'], {
      input: `await import(${JSON.stringify(pathToFileURL(join(output, 'load-test/k6/review-summary/aws.mjs')).href)});`,
      encoding: 'utf8', env: { PATH: directory },
    });
    assert.equal(imported.status, 0, imported.stderr); assert.equal(imported.stdout, '');
    for (const name of ['config.json', 'plan.json', 'SHA256SUMS']) {
      const value = readFileSync(join(output, name), 'utf8'); assert.ok(!value.includes(token)); assert.ok(!value.includes(secret));
    }
  });
});

#!/usr/bin/env node

const assert = require('node:assert/strict');
const {
  chmodSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} = require('node:fs');
const { tmpdir } = require('node:os');
const { resolve } = require('node:path');
const { createHash } = require('node:crypto');

const { prepareCouponSessions } = require('../coupon/prepare-coupon-sessions.js');

const temporary = mkdtempSync(resolve(tmpdir(), 'airbob-coupon-sessions-test-'));
const password = resolve(temporary, 'password');
const manifest = resolve(temporary, 'benchmark-dataset.json');
writeFileSync(password, 'coupon-password', { mode: 0o600 });
chmodSync(password, 0o600);

const canonicalFixture = JSON.parse(readFileSync(resolve(
  __dirname,
  '../../../infra/aws/tests/fixtures/benchmark-dataset-v1.json',
), 'utf8'));
const couponCapsule = canonicalFixture.capsules.find(
  (capsule) => capsule.capsuleId === 'coupon-accounts-v1',
);
assert.ok(couponCapsule);
couponCapsule.accountPool = {
  capacity: 5,
  emails: [
    'coupon-benchmark-00001@airbob.cloud',
    'coupon-benchmark-00002@airbob.cloud',
    'coupon-benchmark-00003@airbob.cloud',
    'coupon-benchmark-00004@airbob.cloud',
    'coupon-benchmark-00005@airbob.cloud',
  ],
};
const manifestRaw = JSON.stringify(canonicalFixture);
writeFileSync(manifest, manifestRaw);

function successfulLogin(sessionPrefix, body) {
  const suffix = body.email.match(/(\d+)@/)[1];
  return {
    status: 200,
    headers: { get: () => `SESSION_ID=${sessionPrefix}-${suffix}-1234567890; Path=/; HttpOnly` },
    json: async () => ({ success: true, data: null }),
  };
}

async function assertManifestRejectedBeforeLogin(filter, label) {
  const malformedManifest = resolve(temporary, `${label}.json`);
  const malformedOutput = resolve(temporary, `${label}-sessions.json`);
  const malformed = JSON.parse(manifestRaw);
  filter(malformed);
  writeFileSync(malformedManifest, JSON.stringify(malformed));
  let fetchCalls = 0;

  await assert.rejects(
    prepareCouponSessions({
      baseUrl: 'http://127.0.0.1:8080',
      manifestPath: malformedManifest,
      passwordPath: password,
      sessionOutput: malformedOutput,
      requiredCapacity: 1,
      testMode: true,
      fetchImpl: async () => {
        fetchCalls += 1;
        throw new Error('network must not be reached');
      },
    }),
    /benchmark dataset manifest is invalid/,
  );
  assert.equal(fetchCalls, 0);
  assert.equal(existsSync(malformedOutput), false);
}

async function main() {
  const output = resolve(temporary, 'coupon-sessions.json');
  const receivedEmails = [];
  const fetchImpl = async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:8080/api/v1/auth/login');
    const body = JSON.parse(options.body);
    receivedEmails.push(body.email);
    assert.equal(body.password, 'coupon-password');
    return successfulLogin('session-value', body);
  };

  try {
    await prepareCouponSessions({
      baseUrl: 'http://127.0.0.1:8080',
      manifestPath: manifest,
      passwordPath: password,
      sessionOutput: output,
      requiredCapacity: 2,
      concurrency: 2,
      testMode: true,
      fetchImpl,
    });
    const fixture = JSON.parse(readFileSync(output, 'utf8'));
    assert.equal(fixture.datasetVersion, 'coupon-issuance-v2');
    assert.equal(
      fixture.benchmarkDatasetManifestSha256,
      createHash('sha256').update(manifestRaw, 'utf8').digest('hex'),
    );
    assert.equal(fixture.sessions.length, 2);
    assert.equal(statSync(output).mode & 0o077, 0);
    assert.deepEqual(receivedEmails, [
      'coupon-benchmark-00001@airbob.cloud',
      'coupon-benchmark-00002@airbob.cloud',
    ]);

    const workerOutput = resolve(temporary, 'worker-pool.json');
    const startedEmails = [];
    let releaseSlowLogin;
    const slowLogin = new Promise((resolveSlowLogin) => {
      releaseSlowLogin = resolveSlowLogin;
    });
    const workerFetch = async (_url, options) => {
      const body = JSON.parse(options.body);
      startedEmails.push(body.email);
      const suffix = body.email.match(/(\d+)@/)[1];
      if (suffix === '00001') {
        await slowLogin;
      }
      return successfulLogin('session-worker', body);
    };
    const workerRun = prepareCouponSessions({
      baseUrl: 'http://127.0.0.1:8080',
      manifestPath: manifest,
      passwordPath: password,
      sessionOutput: workerOutput,
      requiredCapacity: 3,
      concurrency: 2,
      testMode: true,
      fetchImpl: workerFetch,
    });
    await new Promise((resolveImmediate) => setImmediate(resolveImmediate));
    assert.deepEqual(startedEmails, [
      'coupon-benchmark-00001@airbob.cloud',
      'coupon-benchmark-00002@airbob.cloud',
      'coupon-benchmark-00003@airbob.cloud',
    ]);
    releaseSlowLogin();
    await workerRun;
    assert.deepEqual(
      JSON.parse(readFileSync(workerOutput, 'utf8')).sessions,
      [
        'session-worker-00001-1234567890',
        'session-worker-00002-1234567890',
        'session-worker-00003-1234567890',
      ],
    );

    await assertManifestRejectedBeforeLogin((malformed) => {
      malformed.world.observedDistributions = malformed.world.observedDistributions
        .filter((distribution) => distribution.id !== 'payment-recency');
    }, 'missing-observed-distribution');
    await assertManifestRejectedBeforeLogin((malformed) => {
      malformed.capsules.find((capsule) => capsule.capsuleId === 'coupon-accounts-v1')
        .distributions[0].axis = 'VALUE_SKEW';
    }, 'malformed-coupon-capsule');

    const failedOutput = resolve(temporary, 'late-failure.json');
    const failureLoginEmails = [];
    const logoutSessions = [];
    let logoutInFlight = 0;
    let maximumLogoutInFlight = 0;
    let timedOutLogoutSignal;
    let releaseLateLogin;
    const lateLogin = new Promise((resolveLateLogin) => {
      releaseLateLogin = resolveLateLogin;
    });
    const failureFetch = async (url, options) => {
      if (url.endsWith('/logout')) {
        const sessionId = options.headers.Cookie.replace('SESSION_ID=', '');
        logoutSessions.push(sessionId);
        logoutInFlight += 1;
        maximumLogoutInFlight = Math.max(maximumLogoutInFlight, logoutInFlight);
        await new Promise((resolveImmediate) => setImmediate(resolveImmediate));
        if (sessionId === 'session-cleanup-00002-1234567890') {
          logoutInFlight -= 1;
          return { status: 500 };
        }
        if (sessionId === 'session-cleanup-00003-1234567890') {
          timedOutLogoutSignal = options.signal;
          try {
            return await new Promise(() => {});
          } finally {
            logoutInFlight -= 1;
          }
        }
        logoutInFlight -= 1;
        return { status: 200 };
      }
      const body = JSON.parse(options.body);
      failureLoginEmails.push(body.email);
      const suffix = body.email.match(/(\d+)@/)[1];
      if (suffix === '00001') {
        await lateLogin;
      }
      if (suffix === '00005') {
        setImmediate(releaseLateLogin);
        return {
          status: 500,
          headers: { get: () => null },
          json: async () => ({ success: false, data: null }),
        };
      }
      return successfulLogin('session-cleanup', body);
    };
    let preparationError;
    await assert.rejects(
      prepareCouponSessions({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: failedOutput,
        requiredCapacity: 5,
        concurrency: 2,
        requestTimeoutMs: 25,
        testMode: true,
        fetchImpl: failureFetch,
      }),
      (error) => {
        preparationError = error;
        return error.message === 'coupon benchmark login failed';
      },
    );
    assert.deepEqual(failureLoginEmails, [
      'coupon-benchmark-00001@airbob.cloud',
      'coupon-benchmark-00002@airbob.cloud',
      'coupon-benchmark-00003@airbob.cloud',
      'coupon-benchmark-00004@airbob.cloud',
      'coupon-benchmark-00005@airbob.cloud',
    ]);
    assert.deepEqual(logoutSessions, [
      'session-cleanup-00001-1234567890',
      'session-cleanup-00002-1234567890',
      'session-cleanup-00003-1234567890',
      'session-cleanup-00004-1234567890',
    ]);
    assert.equal(maximumLogoutInFlight, 2);
    assert.equal(timedOutLogoutSignal.aborted, true);
    assert.deepEqual(preparationError.couponSessionCleanup, {
      attemptedCount: 4,
      failureCount: 2,
    });
    assert.equal(preparationError.message, 'coupon benchmark login failed');
    assert.equal(existsSync(failedOutput), false);

    let stalledSignal;
    const stalledOutput = resolve(temporary, 'stalled-request.json');
    const startedAt = Date.now();
    await assert.rejects(
      prepareCouponSessions({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: stalledOutput,
        requiredCapacity: 1,
        concurrency: 1,
        requestTimeoutMs: 25,
        testMode: true,
        fetchImpl: async (_url, options) => {
          stalledSignal = options.signal;
          return new Promise(() => {});
        },
      }),
      /coupon benchmark login failed/,
    );
    assert.equal(stalledSignal.aborted, true);
    assert.equal(Date.now() - startedAt < 1000, true);
    assert.equal(existsSync(stalledOutput), false);

    let stalledBodySignal;
    await assert.rejects(
      prepareCouponSessions({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'stalled-body.json'),
        requiredCapacity: 1,
        concurrency: 1,
        requestTimeoutMs: 25,
        testMode: true,
        fetchImpl: async (_url, options) => {
          stalledBodySignal = options.signal;
          return {
            status: 200,
            headers: { get: () => 'SESSION_ID=never-reached-session-1234567890' },
            json: async () => new Promise(() => {}),
          };
        },
      }),
      /coupon benchmark login failed/,
    );
    assert.equal(stalledBodySignal.aborted, true);

    await assert.rejects(
      prepareCouponSessions({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'too-many.json'),
        requiredCapacity: 6,
        testMode: true,
        fetchImpl,
      }),
      /insufficient coupon account capacity/,
    );

    chmodSync(password, 0o644);
    await assert.rejects(
      prepareCouponSessions({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'unsafe.json'),
        requiredCapacity: 1,
        testMode: true,
        fetchImpl,
      }),
      /password file is missing or unsafe/,
    );
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }

  console.log('coupon session preparation tests passed');
}

main().catch((error) => {
  const rendered = error instanceof Error ? error.message : 'coupon session preparation test failed';
  assert.equal(rendered.includes('coupon-password'), false);
  assert.equal(rendered.includes('session-value'), false);
  console.error(rendered);
  process.exitCode = 1;
});

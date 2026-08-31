#!/usr/bin/env node
'use strict';

const { createHash } = require('node:crypto');
const {
  closeSync,
  constants,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} = require('node:fs');
const { basename, dirname, isAbsolute, resolve } = require('node:path');
const { parseBenchmarkDatasetManifest } = require('./benchmark-dataset-manifest-validator.js');

const MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
const MAX_PASSWORD_BYTES = 256;
const MAX_CONCURRENCY = 50;
const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;
const MAX_REQUEST_TIMEOUT_MS = 120_000;

function requireCondition(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function readSafeFile(path, label, maximumBytes, privateFile = false) {
  requireCondition(typeof path === 'string' && isAbsolute(path), `${label} file is missing or unsafe`);
  const stat = lstatSync(path);
  requireCondition(
    stat.isFile()
      && !stat.isSymbolicLink()
      && stat.size > 0
      && stat.size <= maximumBytes
      && (!privateFile || ((stat.mode & 0o077) === 0 && stat.uid === process.getuid())),
    `${label} file is missing or unsafe`,
  );
  return readFileSync(path, 'utf8');
}

function couponAccountEmails(manifestRaw, requiredCapacity) {
  try {
    const manifest = parseBenchmarkDatasetManifest(manifestRaw);
    const capsule = manifest.capsules.find((candidate) => candidate.capsuleId === 'coupon-accounts-v1');
    requireCondition(
      capsule !== undefined && capsule.accountPool.capacity >= requiredCapacity,
      'benchmark dataset manifest is invalid or has insufficient coupon account capacity',
    );
    return capsule.accountPool.emails.slice(0, requiredCapacity);
  } catch (_) {
    throw new Error('benchmark dataset manifest is invalid or has insufficient coupon account capacity');
  }
}

function parseSessionCookie(raw) {
  requireCondition(typeof raw === 'string', 'coupon benchmark login did not return a session');
  const matches = [...raw.matchAll(/(?:^|,\s*)SESSION_ID=([^;,\s]+)/g)];
  requireCondition(matches.length === 1, 'coupon benchmark login did not return a session');
  const session = matches[0][1];
  requireCondition(/^[A-Za-z0-9._~-]{16,512}$/.test(session), 'coupon benchmark login did not return a session');
  return session;
}

function writePrivateFixture(path, fixture) {
  requireCondition(typeof path === 'string' && isAbsolute(path), 'session fixture output path is unsafe');
  const parent = realpathSync(dirname(path));
  const target = resolve(parent, basename(path));
  requireCondition(target.startsWith(`${parent}/`), 'session fixture output path is unsafe');
  let descriptor;
  try {
    descriptor = openSync(target, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
  } catch (_) {
    throw new Error('session fixture output already exists or is unsafe');
  }
  try {
    writeFileSync(descriptor, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8');
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
  } catch (_) {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch (_) {
        // Preserve the bounded write failure.
      }
    }
    try {
      unlinkSync(target);
    } catch (_) {
      // The target may already be absent.
    }
    throw new Error('session fixture output could not be written safely');
  }
}

async function boundedRequest(url, options, fetchImpl, timeoutMs, readJson) {
  const controller = new AbortController();
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => {
      controller.abort();
      reject(new Error('coupon benchmark request timed out'));
    }, timeoutMs);
  });
  try {
    const response = await Promise.race([
      Promise.resolve().then(() => fetchImpl(url, { ...options, signal: controller.signal })),
      timeout,
    ]);
    if (!readJson) {
      return { response };
    }
    const payload = await Promise.race([
      Promise.resolve().then(() => response.json()),
      timeout,
    ]);
    return { response, payload };
  } finally {
    clearTimeout(timeoutId);
  }
}

async function login(baseUrl, email, password, fetchImpl, requestTimeoutMs) {
  try {
    const { response, payload } = await boundedRequest(`${baseUrl}/api/v1/auth/login`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }, fetchImpl, requestTimeoutMs, true);
    requireCondition(response.status === 200 && payload?.success === true, 'coupon benchmark login failed');
    return parseSessionCookie(response.headers.get('set-cookie'));
  } catch (_) {
    throw new Error('coupon benchmark login failed');
  }
}

async function logout(baseUrl, sessionId, fetchImpl, requestTimeoutMs) {
  try {
    const { response } = await boundedRequest(`${baseUrl}/api/v1/auth/logout`, {
      method: 'POST',
      redirect: 'manual',
      headers: { Cookie: `SESSION_ID=${sessionId}` },
    }, fetchImpl, requestTimeoutMs, false);
    return response.status === 200;
  } catch (_) {
    return false;
  }
}

async function cleanupSessions(baseUrl, sessions, fetchImpl, requestTimeoutMs, concurrency) {
  const issuedSessions = [...new Set(sessions.filter((sessionId) => typeof sessionId === 'string'))];
  let nextIndex = 0;
  let failureCount = 0;
  async function worker() {
    while (nextIndex < issuedSessions.length) {
      const index = nextIndex;
      nextIndex += 1;
      if (!await logout(baseUrl, issuedSessions[index], fetchImpl, requestTimeoutMs)) {
        failureCount += 1;
      }
    }
  }
  const workerCount = Math.min(concurrency, issuedSessions.length);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  return Object.freeze({
    attemptedCount: issuedSessions.length,
    failureCount,
  });
}

async function prepareCouponSessions({
  baseUrl,
  manifestPath,
  passwordPath,
  sessionOutput,
  requiredCapacity,
  concurrency = 20,
  requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
  testMode = false,
  fetchImpl = globalThis.fetch,
}) {
  requireCondition(
    (testMode && /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(baseUrl))
      || (!testMode && baseUrl === 'https://api.airbob.cloud'),
    'base URL is outside the AWS benchmark boundary',
  );
  requireCondition(typeof fetchImpl === 'function', 'Fetch API is required');
  requireCondition(Number.isSafeInteger(requiredCapacity) && requiredCapacity > 0, 'required capacity is invalid');
  requireCondition(
    Number.isSafeInteger(concurrency) && concurrency > 0 && concurrency <= MAX_CONCURRENCY,
    'session preparation concurrency is invalid',
  );
  requireCondition(
    Number.isSafeInteger(requestTimeoutMs)
      && requestTimeoutMs > 0
      && requestTimeoutMs <= MAX_REQUEST_TIMEOUT_MS,
    'session preparation request timeout is invalid',
  );
  const manifestRaw = readSafeFile(
    manifestPath,
    'benchmark dataset manifest',
    MAX_MANIFEST_BYTES,
  );
  const emails = couponAccountEmails(manifestRaw, requiredCapacity);
  const manifestSha256 = createHash('sha256').update(manifestRaw, 'utf8').digest('hex');
  const password = readSafeFile(passwordPath, 'password', MAX_PASSWORD_BYTES, true);
  requireCondition(!/[\r\n\0]/.test(password), 'password file is missing or unsafe');

  const sessions = new Array(emails.length);
  let nextIndex = 0;
  let firstFailure;
  async function worker() {
    while (firstFailure === undefined && nextIndex < emails.length) {
      const index = nextIndex;
      nextIndex += 1;
      try {
        sessions[index] = await login(
          baseUrl,
          emails[index],
          password,
          fetchImpl,
          requestTimeoutMs,
        );
      } catch (error) {
        if (firstFailure === undefined) {
          firstFailure = error;
        }
      }
    }
  }
  const workerCount = Math.min(concurrency, emails.length);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  try {
    if (firstFailure !== undefined) {
      throw firstFailure;
    }
    requireCondition(new Set(sessions).size === sessions.length, 'coupon benchmark login returned duplicate sessions');
    writePrivateFixture(sessionOutput, {
      datasetVersion: 'coupon-issuance-v2',
      benchmarkDatasetManifestSha256: manifestSha256,
      sessions,
    });
  } catch (error) {
    const cleanup = await cleanupSessions(baseUrl, sessions, fetchImpl, requestTimeoutMs, concurrency);
    if (cleanup.failureCount > 0 && error instanceof Error) {
      Object.defineProperty(error, 'couponSessionCleanup', {
        configurable: true,
        value: cleanup,
      });
    }
    throw error;
  }
}

function parsePositiveInteger(raw, label) {
  const value = Number(raw);
  requireCondition(Number.isSafeInteger(value) && value > 0, `${label} is invalid`);
  return value;
}

function parseArguments(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    requireCondition(flag?.startsWith('--') && value !== undefined, 'session preparation arguments are invalid');
    requireCondition(options[flag] === undefined, 'session preparation argument is duplicated');
    options[flag] = value;
  }
  const required = ['--base-url', '--manifest', '--password-file', '--session-output', '--required-capacity'];
  const allowed = [...required, '--concurrency'];
  requireCondition(
    required.every((key) => options[key] !== undefined)
      && Object.keys(options).every((key) => allowed.includes(key)),
    'session preparation arguments are invalid',
  );
  return options;
}

async function main(argv) {
  const options = parseArguments(argv);
  const testMode = process.env.AIRBOB_SESSION_PREPARATION_TEST_MODE === '1';
  requireCondition(
    testMode || process.env.AIRBOB_SESSION_PREPARATION_TEST_MODE === undefined,
    'session preparation test mode is invalid',
  );
  await prepareCouponSessions({
    baseUrl: options['--base-url'],
    manifestPath: options['--manifest'],
    passwordPath: options['--password-file'],
    sessionOutput: options['--session-output'],
    requiredCapacity: parsePositiveInteger(options['--required-capacity'], 'required capacity'),
    concurrency: options['--concurrency'] === undefined
      ? 20
      : parsePositiveInteger(options['--concurrency'], 'concurrency'),
    testMode,
  });
  process.stdout.write('coupon benchmark sessions prepared\n');
}

module.exports = { prepareCouponSessions };

if (require.main === module) {
  main(process.argv.slice(2)).catch((error) => {
    const cleanup = error instanceof Error ? error.couponSessionCleanup : undefined;
    if (cleanup?.failureCount > 0) {
      process.stderr.write(
        `warning: ${cleanup.failureCount} of ${cleanup.attemptedCount} coupon session logouts failed; active sessions may remain\n`,
      );
    }
    process.stderr.write(`${error instanceof Error ? error.message : 'coupon session preparation failed'}\n`);
    process.exitCode = 1;
  });
}

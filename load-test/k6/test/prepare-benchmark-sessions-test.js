#!/usr/bin/env node

const assert = require('node:assert/strict');
const {
  chmodSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} = require('node:fs');
const { tmpdir } = require('node:os');
const { resolve } = require('node:path');

const { prepareBenchmarkSession } = require('../traffic/prepare-benchmark-sessions.js');

const repoRoot = resolve(__dirname, '../../..');
const manifest = resolve(repoRoot, 'load-test/k6/test/fixtures/nplus1-v1.json');
const temporary = mkdtempSync(resolve(tmpdir(), 'airbob-session-test-'));
const password = resolve(temporary, 'password');
writeFileSync(password, 'benchmark-password', { mode: 0o600 });
chmodSync(password, 0o600);

async function main() {
  const sessionOutput = resolve(temporary, 'session');
  let receivedBody;
  const fetchImpl = async (url, options) => {
    assert.equal(url, 'http://127.0.0.1:8080/api/v1/auth/login');
    assert.equal(options.method, 'POST');
    assert.equal(options.redirect, 'manual');
    receivedBody = JSON.parse(options.body);
    return {
      status: 200,
      headers: { get: (name) => (name === 'set-cookie' ? 'SESSION_ID=session-value-1234567890; Path=/; HttpOnly; Secure' : null) },
      json: async () => ({ success: true, data: null }),
    };
  };

  try {
    await prepareBenchmarkSession({
      baseUrl: 'http://127.0.0.1:8080',
      manifestPath: manifest,
      passwordPath: password,
      sessionOutput,
      testMode: true,
      fetchImpl,
    });
    assert.equal(readFileSync(sessionOutput, 'utf8'), 'session-value-1234567890');
    assert.equal(statSync(sessionOutput).mode & 0o077, 0);
    assert.deepEqual(receivedBody, {
      email: 'benchmark-nplus1@airbob.cloud',
      password: 'benchmark-password',
    });

    await assert.rejects(
      prepareBenchmarkSession({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput,
        testMode: true,
        fetchImpl,
      }),
      /session output already exists/,
    );

    chmodSync(password, 0o644);
    await assert.rejects(
      prepareBenchmarkSession({
        baseUrl: 'http://127.0.0.1:8080',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'unsafe-session'),
        testMode: true,
        fetchImpl,
      }),
      /password file is missing or unsafe/,
    );

    await assert.rejects(
      prepareBenchmarkSession({
        baseUrl: 'http://api.airbob.cloud',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'http-session'),
        testMode: false,
        fetchImpl,
      }),
      /base URL is outside the AWS benchmark boundary/,
    );

    chmodSync(password, 0o600);
    await assert.rejects(
      prepareBenchmarkSession({
        baseUrl: 'https://api.airbob.cloud',
        manifestPath: manifest,
        passwordPath: password,
        sessionOutput: resolve(temporary, 'redirect-session'),
        testMode: false,
        fetchImpl: async () => ({
          status: 302,
          headers: { get: () => null },
          json: async () => ({}),
        }),
      }),
      /benchmark login failed/,
    );
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }

  console.log('benchmark session preparation tests passed');
}

main().catch((error) => {
  const rendered = error instanceof Error ? error.message : 'session preparation test failed';
  assert.equal(rendered.includes('benchmark-password'), false);
  assert.equal(rendered.includes('session-value'), false);
  console.error(rendered);
  process.exitCode = 1;
});

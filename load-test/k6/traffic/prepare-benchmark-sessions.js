#!/usr/bin/env node
'use strict';

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

const MAX_MANIFEST_BYTES = 1024 * 1024;
const MAX_PASSWORD_BYTES = 256;

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

function benchmarkAccountEmail(manifestRaw) {
  let manifest;
  try {
    manifest = JSON.parse(manifestRaw);
  } catch (_) {
    throw new Error('benchmark manifest is invalid');
  }
  requireCondition(
    manifest?.datasetVersion === 'nplus1-v1'
      && manifest?.account?.email === 'benchmark-nplus1@airbob.cloud',
    'benchmark manifest is invalid',
  );
  return manifest.account.email;
}

function parseSessionCookie(raw) {
  requireCondition(typeof raw === 'string', 'benchmark login did not return a session');
  const matches = [...raw.matchAll(/(?:^|,\s*)SESSION_ID=([^;,\s]+)/g)];
  requireCondition(matches.length === 1, 'benchmark login did not return a session');
  const session = matches[0][1];
  requireCondition(/^[A-Za-z0-9._~-]{16,512}$/.test(session), 'benchmark login did not return a session');
  return session;
}

function writePrivateSession(path, session) {
  requireCondition(typeof path === 'string' && isAbsolute(path), 'session output path is unsafe');
  const parent = realpathSync(dirname(path));
  const target = resolve(parent, basename(path));
  requireCondition(target.startsWith(`${parent}/`), 'session output path is unsafe');
  let descriptor;
  try {
    descriptor = openSync(target, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
  } catch (_) {
    throw new Error('session output already exists or is unsafe');
  }
  try {
    writeFileSync(descriptor, session, 'utf8');
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
  } catch (_) {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch (_) {
        // Preserve the bounded write failure below.
      }
    }
    try {
      unlinkSync(target);
    } catch (_) {
      // The target may already be absent; never replay the session value.
    }
    throw new Error('session output could not be written safely');
  }
}

async function prepareBenchmarkSession({
  baseUrl,
  manifestPath,
  passwordPath,
  sessionOutput,
  testMode = false,
  fetchImpl = globalThis.fetch,
}) {
  requireCondition(
    (testMode && /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(baseUrl))
      || (!testMode && baseUrl === 'https://api.airbob.cloud'),
    'base URL is outside the AWS benchmark boundary',
  );
  requireCondition(typeof fetchImpl === 'function', 'Fetch API is required');
  const email = benchmarkAccountEmail(readSafeFile(manifestPath, 'benchmark manifest', MAX_MANIFEST_BYTES));
  const password = readSafeFile(passwordPath, 'password', MAX_PASSWORD_BYTES, true);
  requireCondition(!/[\r\n\0]/.test(password), 'password file is missing or unsafe');

  let response;
  try {
    response = await fetchImpl(`${baseUrl}/api/v1/auth/login`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
  } catch (_) {
    throw new Error('benchmark login failed');
  }
  let payload;
  try {
    payload = await response.json();
  } catch (_) {
    throw new Error('benchmark login failed');
  }
  requireCondition(response.status === 200 && payload?.success === true, 'benchmark login failed');
  const session = parseSessionCookie(response.headers.get('set-cookie'));
  writePrivateSession(sessionOutput, session);
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
  const expected = ['--base-url', '--manifest', '--password-file', '--session-output'];
  requireCondition(
    Object.keys(options).length === expected.length && expected.every((key) => options[key] !== undefined),
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
  await prepareBenchmarkSession({
    baseUrl: options['--base-url'],
    manifestPath: options['--manifest'],
    passwordPath: options['--password-file'],
    sessionOutput: options['--session-output'],
    testMode,
  });
  process.stdout.write('benchmark session prepared\n');
}

module.exports = { prepareBenchmarkSession };

if (require.main === module) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : 'benchmark session preparation failed'}\n`);
    process.exitCode = 1;
  });
}

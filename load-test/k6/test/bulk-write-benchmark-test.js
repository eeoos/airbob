import { check } from 'k6';
import {
  BULK_WRITE_ENDPOINT,
  bulkWriteOperationName,
  buildBulkWriteHeaders,
  parseBulkWriteResultPath,
  parseBulkWriteRunConfig,
  parseBulkWriteToken,
  parseBulkWriteVariant,
  parseDatasetSize,
  parseSafeBaseUrl,
  redactK6Summary,
} from '../lib/bulk-write-benchmark.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

function rejects(action) {
  try {
    action();
    return false;
  } catch (_) {
    return true;
  }
}

export default function () {
  const token = '0123456789abcdef0123456789abcdef';
  const redacted = JSON.stringify(redactK6Summary({
    token,
    nested: { name: `request ${token}`, session_id: 'private-session' },
    metrics: { requests: { values: { count: 3 } } },
  }, [token, 'private-session']));

  check(null, {
    'shared default targets the remaining reservation benchmark': () => (
      BULK_WRITE_ENDPOINT === '/api/v2/admin/benchmarks/bulk-write/reservation-history-insert'
        && bulkWriteOperationName('AFTER') === 'expired-reservation-cleanup-after'
    ),
    'loopback HTTP and HTTPS origins are accepted': () => (
      parseSafeBaseUrl('http://localhost:8080/') === 'http://localhost:8080'
        && parseSafeBaseUrl('https://benchmark.example.com/') === 'https://benchmark.example.com'
    ),
    'remote or lookalike HTTP hosts are rejected': () => [
      'http://benchmark.example.com', 'http://localhost.example.com', 'http://127.0.0.2',
    ].every((url) => rejects(() => parseSafeBaseUrl(url))),
    'credentials paths and query strings are rejected': () => [
      'https://user:password@benchmark.example.com', 'https://benchmark.example.com/api',
      'https://benchmark.example.com?token=secret', 'ftp://localhost:8080',
    ].every((url) => rejects(() => parseSafeBaseUrl(url))),
    'reservation dataset boundaries are accepted': () => (
      parseDatasetSize('0') === 0 && parseDatasetSize('2000') === 2000
    ),
    'invalid dataset sizes are rejected': () => [
      '-1', '2001', '1.5', '1e3', '025', ' 25', '',
    ].every((value) => rejects(() => parseDatasetSize(value))),
    'variant names remain explicit': () => (
      parseBulkWriteVariant('BEFORE') === 'BEFORE'
        && parseBulkWriteVariant('AFTER') === 'AFTER'
        && rejects(() => parseBulkWriteVariant('after'))
        && rejects(() => parseBulkWriteVariant('UNKNOWN'))
    ),
    'token and header preserve the exact token': () => (
      parseBulkWriteToken(token) === token
        && buildBulkWriteHeaders(token)['X-Bulk-Write-Benchmark-Token'] === token
    ),
    'blank short or padded tokens are rejected': () => [
      ' '.repeat(32), 'x'.repeat(31), ` ${token} `,
    ].every((value) => rejects(() => parseBulkWriteToken(value))),
    'result files stay within the artifact directory': () => (
      parseBulkWriteResultPath('build/k6/bulk-write/result.json') === 'build/k6/bulk-write/result.json'
        && ['build/k6/bulk-write/../secret.json', '/tmp/result.json', 'build/k6/other.json']
          .every((path) => rejects(() => parseBulkWriteResultPath(path)))
    ),
    'missing experiment metadata is rejected': () => rejects(() => (
      parseBulkWriteRunConfig({ DATASET_SIZE: '25', BENCHMARK_BULK_WRITE_TOKEN: token })
    )),
    'result redaction removes secrets and preserves metrics': () => (
      !redacted.includes(token) && !redacted.includes('private-session')
        && JSON.parse(redacted).metrics.requests.values.count === 3
    ),
  });
}

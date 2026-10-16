import http from 'k6/http';
import { fail } from 'k6';

// The legacy single-block scripts remain usable without runtime pinning. The new suite always supplies it.
export function verifyBulkDeleteRuntime(config, sessionId, environment) {
  if (!environment.BULK_DELETE_RUNTIME_ID) return;
  const response = http.get(`${config.baseUrl}/api/v2/admin/benchmarks/bulk-write/runtime`, {
    headers: { 'X-Bulk-Write-Benchmark-Token': config.benchmarkToken },
    cookies: { SESSION_ID: sessionId }, redirects: 0, timeout: '10s',
    tags: { phase: 'setup', name: 'GET /api/v2/admin/benchmarks/bulk-write/runtime' },
  });
  let data;
  try { data = response.json().data; } catch (_) { fail('Bulk delete runtime response is invalid'); }
  if (response.status !== 200 || !data
    || data.schema_version !== 'bulk-delete-runtime-v1'
    || data.runtime_id !== environment.BULK_DELETE_RUNTIME_ID
    || data.environment !== environment.BULK_DELETE_ENVIRONMENT
    || data.database_id !== environment.BULK_DELETE_DATABASE_ID
    || data.image_digest !== environment.BULK_DELETE_IMAGE_DIGEST
    || data.pool_size !== Number(environment.BULK_DELETE_POOL_SIZE)
    || data.flyway_version !== '28'
    || data.app_commit !== config.appCommit || data.schema_label !== config.schemaLabel
    || data.jvm_version !== config.jvmVersion || data.mysql_version !== config.mysqlVersion
    || data.rewrite_batched_statements !== config.rewriteBatchedStatements) {
    fail('Bulk delete runtime changed or does not match this experiment');
  }
}

export function logoutBulkDeleteSession(config, setupData, environment) {
  if (!environment.BULK_DELETE_RUNTIME_ID || !setupData?.sessionId) return;
  const response = http.post(`${config.baseUrl}/api/v1/auth/logout`, null, {
    cookies: { SESSION_ID: setupData.sessionId }, redirects: 0, timeout: '10s',
    tags: { phase: 'teardown', name: 'POST /api/v1/auth/logout' },
  });
  if (response.status !== 200) fail('Bulk delete session cleanup failed');
}

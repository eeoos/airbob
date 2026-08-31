import { check, fail } from 'k6';
import http from 'k6/http';
import { Rate, Trend } from 'k6/metrics';

import {
  buildCanonicalResultHash,
  buildReadModelOptions,
  buildReadModelRequestName,
  buildReadModelRequestParams,
  canonicalizeReadModelData,
  matchesReadModelContract,
  parseDurationSeconds,
  readModelPayloadsEquivalent,
  summarizeReadModelMetrics,
} from './read-model-benchmark.js';

const EXCLUDED_HEADLINE_PHASES = ['setup', 'login', 'analyze', 'explain'];

function parsePayload(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function format(value, digits = 2) {
  return Number.isFinite(value) ? value.toFixed(digits) : 'n/a';
}

function observedRows(domain, data) {
  if (domain === 'review') {
    return data.total_count;
  }
  if (domain === 'wishlist') {
    return data.wishlists.length;
  }
  return data.items.length;
}

export function buildReadModelEvidenceArtifact(config, data, measureSeconds, generatedAt) {
  const summary = summarizeReadModelMetrics(data, measureSeconds);
  const reasons = [];
  if (summary.requests.failed > 0) {
    reasons.push('measurement-errors');
  }
  if (summary.requests.dropped_iterations > 0) {
    reasons.push('dropped-iterations');
  }
  if (summary.requests.attempted === 0) {
    reasons.push('no-measurement-requests');
  }
  if (summary.latency_ms.count !== summary.requests.successful) {
    reasons.push('latency-count-mismatch');
  }
  if (![summary.latency_ms.min, summary.latency_ms.p50, summary.latency_ms.p95,
    summary.latency_ms.p99, summary.latency_ms.max]
    .every((value) => Number.isFinite(value) && value >= 0)
    || !(summary.latency_ms.p50 > 0)) {
    reasons.push('latency-metrics-missing');
  }
  const valid = reasons.length === 0;
  return {
    schema_version: 'read-model-evidence-v1',
    metadata: {
      generated_at: generatedAt || new Date().toISOString(),
      ...config.metadata,
    },
    validity: {
      status: valid ? 'valid' : 'invalid',
      reasons,
      errors: summary.requests.failed,
      dropped_iterations: summary.requests.dropped_iterations,
    },
    parity: {
      verified: true,
      expected_rows: config.target.expectedCount,
      observed_rows: config.target.expectedCount,
      expected_result_hash: config.target.expectedResultHash,
      before_result_hash: config.target.expectedResultHash,
      after_result_hash: config.target.expectedResultHash,
    },
    performance: {
      headline_scope: 'measure-only',
      excluded_phases: EXCLUDED_HEADLINE_PHASES,
      requests: {
        attempted: summary.requests.attempted,
        successful: summary.requests.successful,
        failed: summary.requests.failed,
        dropped_iterations: summary.requests.dropped_iterations,
      },
      latency_ms: summary.latency_ms,
    },
    measurement_fencing_token_sha256: config.measurementFencingTokenSha256,
    runtime_assertion: config.runtimeAssertion,
    mysql_evidence: config.mysqlEvidence,
  };
}

export function createReadModelBenchmark(config) {
  const clientDuration = new Trend('read_model_client_duration', true);
  const requestSuccess = new Rate('read_model_request_success');
  const measureSeconds = parseDurationSeconds(config.measureDuration, 'MEASURE_DURATION');
  const targetPath = config.variant === 'before'
    ? config.target.beforePath
    : config.target.afterPath;
  const options = config.mode === 'assemble'
    ? {
      scenarios: {
        assemble: {
          executor: 'shared-iterations',
          exec: 'assemble',
          vus: 1,
          iterations: 1,
          maxDuration: '30s',
        },
      },
    }
    : buildReadModelOptions(config);

  function setup() {
    if (config.mode === 'assemble') {
      return {};
    }
    const setupData = (config.setup && config.setup()) || {};
    const parityRequest = (variant, path) => http.get(
      `${config.baseUrl}${path}`,
      buildReadModelRequestParams({
        variant,
        benchmarkToken: config.benchmarkToken,
        sessionId: setupData.sessionId,
        tags: {
          domain: config.target.domain,
          phase: 'setup',
          purpose: 'parity',
          target_id: config.target.target.id,
          variant,
          name: `PARITY ${buildReadModelRequestName(config.target.domain, variant)}`,
        },
        timeout: config.requestTimeout,
      }),
    );
    const beforeResponse = parityRequest('before', config.target.beforePath);
    const afterResponse = parityRequest('after', config.target.afterPath);
    const beforePayload = parsePayload(beforeResponse);
    const afterPayload = parsePayload(afterResponse);
    const contract = {
      domain: config.target.domain,
      expectedCount: config.target.expectedCount,
      ...config.contract,
    };

    if (beforeResponse.status !== 200 || !matchesReadModelContract({
      ...contract,
      variant: 'before',
      payload: beforePayload,
    })) {
      fail(`${config.target.target.id} before parity request failed its response contract`);
    }
    if (afterResponse.status !== 200 || !matchesReadModelContract({
      ...contract,
      variant: 'after',
      payload: afterPayload,
    })) {
      fail(`${config.target.target.id} after parity request failed its response contract`);
    }
    if (!readModelPayloadsEquivalent(config.target.domain, beforePayload, afterPayload)) {
      fail(`${config.target.target.id} before/after payloads are not equivalent`);
    }

    const beforeHash = buildCanonicalResultHash(config.target.domain, beforePayload.data);
    const afterHash = buildCanonicalResultHash(config.target.domain, afterPayload.data);
    const expectedHash = config.target.expectedResultHash;
    if (beforeHash !== expectedHash || afterHash !== expectedHash) {
      fail(`${config.target.target.id} canonical result hash drifted from the manifest`);
    }
    const beforeRows = observedRows(config.target.domain, beforePayload.data);
    const afterRows = observedRows(config.target.domain, afterPayload.data);
    if (beforeRows !== config.target.expectedCount || afterRows !== config.target.expectedCount) {
      fail(`${config.target.target.id} observed row count drifted from the manifest`);
    }

    return {
      ...setupData,
      expectedData: canonicalizeReadModelData(config.target.domain, afterPayload.data),
      expectedResultHash: expectedHash,
    };
  }

  function requestTarget(data, phase) {
    const tags = {
      domain: config.target.domain,
      phase,
      target_id: config.target.target.id,
      target_class: config.metadata.target_class,
      variant: config.variant,
    };
    const response = http.get(
      `${config.baseUrl}${targetPath}`,
      buildReadModelRequestParams({
        variant: config.variant,
        benchmarkToken: config.benchmarkToken,
        sessionId: data && data.sessionId,
        tags: { ...tags, name: buildReadModelRequestName(config.target.domain, config.variant) },
        timeout: config.requestTimeout,
      }),
    );
    const payload = parsePayload(response);
    const contractMatches = matchesReadModelContract({
      domain: config.target.domain,
      variant: config.variant,
      payload,
      expectedCount: config.target.expectedCount,
      expectedData: data.expectedData,
      ...config.contract,
    });
    let hashMatches = false;
    if (contractMatches) {
      try {
        hashMatches = buildCanonicalResultHash(config.target.domain, payload.data)
          === data.expectedResultHash;
      } catch (_) {
        hashMatches = false;
      }
    }
    const success = response.status === 200 && contractMatches && hashMatches;

    check(response, {
      [`${config.target.target.id} ${config.variant} returns HTTP 200`]: (res) => (
        res.status === 200
      ),
      [`${config.target.target.id} ${config.variant} matches canonical manifest result`]: () => (
        contractMatches && hashMatches
      ),
    }, tags);

    if (phase === 'measure') {
      clientDuration.add(response.timings.duration, tags);
      requestSuccess.add(success, tags);
    }
  }

  function warmup(data) {
    requestTarget(data, 'warmup');
  }

  function measure(data) {
    requestTarget(data, 'measure');
  }

  function assemble() {
    // The measured summary is opened during init. This scenario performs no request.
  }

  function handleSummary(data) {
    if (config.mode === 'measure') {
      return {
        stdout: `read model measurement captured: ${config.measurementPath}\n`,
        [config.measurementPath]: JSON.stringify(data, null, 2),
      };
    }
    const sourceData = config.mode === 'assemble' ? config.measurementSummary : data;
    if (!sourceData || typeof sourceData !== 'object') {
      throw new Error('READ_MODEL_MEASUREMENT_SUMMARY is required for assembly');
    }
    const summary = summarizeReadModelMetrics(sourceData, measureSeconds);
    const artifact = buildReadModelEvidenceArtifact(config, sourceData, measureSeconds);
    const output = [
      `read model: ${config.target.target.id}/${config.variant}`,
      [
        `requests=${summary.requests.attempted}`,
        `successful=${summary.requests.successful}`,
        `rps=${format(summary.requests.achieved_rps)}`,
        `dropped=${summary.requests.dropped_iterations}`,
      ].join(' '),
      [
        `latency(ms) p50=${format(summary.latency_ms.p50)}`,
        `p95=${format(summary.latency_ms.p95)}`,
        `p99=${format(summary.latency_ms.p99)}`,
        `max=${format(summary.latency_ms.max)}`,
      ].join(' '),
      `validity=${artifact.validity.status}`,
      `result=${config.resultPath}`,
      '',
    ].join('\n');

    return {
      stdout: output,
      [config.resultPath]: JSON.stringify(artifact, null, 2),
    };
  }

  return {
    assemble,
    handleSummary,
    measure,
    options,
    setup,
    warmup,
  };
}

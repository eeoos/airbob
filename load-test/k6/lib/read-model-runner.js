import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import {
  buildReadModelOptions,
  buildReadModelRequestParams,
  canonicalizeReadModelData,
  matchesReadModelContract,
  parseDurationSeconds,
  readModelPayloadsEquivalent,
  summarizeReadModelMetrics,
} from './read-model-benchmark.js';

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

export function createReadModelBenchmark(config) {
  const clientDuration = new Trend('read_model_client_duration', true);
  const requestSuccess = new Rate('read_model_request_success');
  const measureSeconds = parseDurationSeconds(config.measureDuration, 'MEASURE_DURATION');
  const options = buildReadModelOptions(config);

  function setup() {
    const setupData = (config.setup && config.setup()) || {};
    const parityRequest = (variant, path) => http.get(
      `${config.baseUrl}${path}`,
      buildReadModelRequestParams({
        variant,
        benchmarkToken: config.benchmarkToken,
        sessionId: setupData.sessionId,
        tags: {
          domain: config.domain,
          phase: 'setup',
          purpose: 'parity',
          variant,
          name: `PARITY GET ${path}`,
        },
        timeout: config.requestTimeout,
      }),
    );
    const beforeResponse = parityRequest('before', config.beforePath);
    const afterResponse = parityRequest('after', config.afterPath);
    const beforePayload = parsePayload(beforeResponse);
    const afterPayload = parsePayload(afterResponse);
    const matchesContract = (response, payload, variant) => (
      response.status === 200
        && matchesReadModelContract({
          domain: config.domain,
          variant,
          payload,
          expectedCount: config.expectedCount,
          ...config.contract,
        })
    );

    if (!matchesContract(beforeResponse, beforePayload, 'before')) {
      fail(`${config.domain} before parity request failed its response contract`);
    }
    if (!matchesContract(afterResponse, afterPayload, 'after')) {
      fail(`${config.domain} after parity request failed its response contract`);
    }
    if (!readModelPayloadsEquivalent(config.domain, beforePayload, afterPayload)) {
      fail(`${config.domain} before/after payloads are not equivalent`);
    }

    return {
      ...setupData,
      expectedDataJson: JSON.stringify(
        canonicalizeReadModelData(config.domain, afterPayload.data),
      ),
    };
  }

  function requestTarget(data, phase) {
    const tags = {
      domain: config.domain,
      dataset_count: String(config.expectedCount),
      phase,
      variant: config.variant,
    };
    const response = http.get(
      `${config.baseUrl}${config.targetPath}`,
      buildReadModelRequestParams({
        variant: config.variant,
        benchmarkToken: config.benchmarkToken,
        sessionId: data && data.sessionId,
        tags: { ...tags, name: config.requestName },
        timeout: config.requestTimeout,
      }),
    );
    const contractMatches = matchesReadModelContract({
      domain: config.domain,
      variant: config.variant,
      payload: parsePayload(response),
      expectedCount: config.expectedCount,
      expectedDataJson: data.expectedDataJson,
      ...config.contract,
    });
    const success = response.status === 200 && contractMatches;

    check(response, {
      [`${config.domain} ${config.variant} returns HTTP 200`]: (res) => res.status === 200,
      [`${config.domain} ${config.variant} matches response contract`]: () => contractMatches,
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

  function handleSummary(data) {
    const performance = summarizeReadModelMetrics(data, measureSeconds);
    const artifact = {
      metadata: {
        generated_at: new Date().toISOString(),
        domain: config.domain,
        variant: config.variant,
        endpoint: config.targetPath,
        expected_count: config.expectedCount,
        configured_rate_per_second: config.rate,
        warmup_duration: config.warmupDuration,
        measure_duration: config.measureDuration,
        pre_allocated_vus: config.preAllocatedVUs,
        max_vus: config.maxVUs,
        ...config.metadata,
      },
      performance,
    };
    const output = [
      `read model: ${config.domain}/${config.variant} run=${config.metadata.run_label}`,
      [
        `requests=${performance.requests.attempted}`,
        `successful=${performance.requests.successful}`,
        `rps=${format(performance.requests.achieved_rps)}`,
        `dropped=${performance.requests.dropped_iterations}`,
      ].join(' '),
      [
        `latency(ms) avg=${format(performance.latency_ms.avg)}`,
        `p50=${format(performance.latency_ms.median)}`,
        `p95=${format(performance.latency_ms.p95)}`,
        `p99=${format(performance.latency_ms.p99)}`,
        `max=${format(performance.latency_ms.max)}`,
      ].join(' '),
      `result=${config.resultPath}`,
      '',
    ].join('\n');

    return {
      stdout: output,
      [config.resultPath]: JSON.stringify(artifact, null, 2),
    };
  }

  return {
    handleSummary,
    measure,
    options,
    setup,
    warmup,
  };
}

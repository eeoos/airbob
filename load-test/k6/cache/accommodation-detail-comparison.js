import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  CACHE_BENCHMARK_METRICS,
  buildCacheBenchmarkOptions,
  buildCacheBenchmarkRequest,
  matchesCacheBenchmarkResponse,
  parseCacheBenchmarkConfig,
  selectCacheBenchmarkAccommodation,
  summarizeCacheBenchmark,
} from '../lib/accommodation-cache-benchmark.js';

if (typeof __ENV.CACHE_BENCHMARK_FIXTURE !== 'string'
    || !__ENV.CACHE_BENCHMARK_FIXTURE.startsWith('/')) {
  throw new Error('CACHE_BENCHMARK_FIXTURE must be an absolute file path');
}
const run = parseCacheBenchmarkConfig(__ENV, open(__ENV.CACHE_BENCHMARK_FIXTURE));
const completed = new Counter(CACHE_BENCHMARK_METRICS.completed);
const success = new Rate(CACHE_BENCHMARK_METRICS.success);
const duration = new Trend(CACHE_BENCHMARK_METRICS.duration, true);

export const options = buildCacheBenchmarkOptions(run);

// The runner owns preflight and warming. No setup/login/prefetch requests enter this run.
export default function () {
  const expected = selectCacheBenchmarkAccommodation(run, execution.scenario.iterationInTest);
  const request = buildCacheBenchmarkRequest(run, expected.id);
  const response = http.get(request.url, request.params);
  let payload = null;
  try {
    payload = response.json();
  } catch (_) {
    // Malformed/non-JSON responses count as failed samples without logging response data.
  }
  success.add(matchesCacheBenchmarkResponse(response.status, payload, expected), request.params.tags);
  completed.add(1, request.params.tags);
  if (run.mode === 'measure') {
    duration.add(response.timings.duration, request.params.tags);
  }
}

export function handleSummary(data) {
  if (run.mode === 'warmup') {
    return { stdout: `cache comparison warmup completed: ${run.runLabel}\n` };
  }
  const artifact = summarizeCacheBenchmark(data, run);
  return {
    stdout: `cache comparison ${run.runLabel}: ${artifact.validity.status}; `
      + `${artifact.load.iterations.completed} samples\n`,
    [run.resultPath]: `${JSON.stringify(artifact, null, 2)}\n`,
  };
}

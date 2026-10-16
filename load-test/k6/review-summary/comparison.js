import http from 'k6/http';
import crypto from 'k6/crypto';
import execution from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import { METRICS, parseConfig, targetPath, buildOptions, matchesContract, canonicalData, summarize } from './lib.mjs';

const config = parseConfig(__ENV);
export const options = buildOptions(config);
const started = new Counter(METRICS.started);
const completed = new Counter(METRICS.completed);
const success = new Rate(METRICS.success);
const duration = new Trend(METRICS.duration, true);
const completionTime = new Trend(METRICS.completionTime, true);
const measureStart = new Trend(METRICS.measureStart);

function responseHash(response) {
  try {
    const payload = response.json();
    return response.status === 200 && matchesContract(config, payload)
      ? crypto.sha256(canonicalData(config.target, payload.data), 'hex') : null;
  } catch (_) {
    return null;
  }
}

function getTarget(sessionId, variant, phase) {
  const path = targetPath(config, variant);
  // 서버가 설정한 쿠키가 익명 요청이나 명시한 회원 세션을 바꾸지 않도록 한다.
  http.cookieJar().clear(config.baseUrl);
  return http.get(`${config.baseUrl}${path}`, {
    headers: { 'X-Benchmark-Token': config.token, Accept: 'application/json' },
    cookies: sessionId ? { SESSION_ID: sessionId } : {},
    redirects: 0, timeout: `${config.timeoutSeconds}s`,
    tags: { phase, variant, name: `${config.target}/${variant}` },
  });
}

export function setup() {
  let sessionId = config.sessionId;
  if (config.email) {
    const response = http.post(`${config.baseUrl}/api/v1/auth/login`, JSON.stringify({
      email: config.email, password: config.password,
    }), {
      headers: { 'Content-Type': 'application/json' }, redirects: 0,
      timeout: `${config.timeoutSeconds}s`, tags: { phase: 'setup', name: 'benchmark-login' },
    });
    sessionId = response.cookies.SESSION_ID?.[0]?.value;
    if (response.status !== 200 || !sessionId) throw new Error('벤치마크 로그인에 실패했습니다.');
  }
  const before = responseHash(getTarget(sessionId, 'before', 'setup'));
  const after = responseHash(getTarget(sessionId, 'after', 'setup'));
  if (!before || !after || before !== after) {
    throw new Error('전후 응답 또는 예상 행 수가 다릅니다. 데이터·권한·리뷰 요약을 확인하세요.');
  }
  return { sessionId, responseHash: before, verified: true };
}

export function warmup(data) {
  const response = getTarget(data.sessionId, config.variant, 'warmup');
  if (responseHash(response) !== data.responseHash) {
    execution.test.abort('워밍업 응답이 사전 검증과 다릅니다.');
  }
}

export function measure(data) {
  const tags = { phase: 'measure' };
  measureStart.add(execution.scenario.startTime, tags);
  started.add(1, tags);
  const response = getTarget(data.sessionId, config.variant, 'measure');
  duration.add(response.timings.duration, tags);
  completionTime.add(Date.now() - execution.scenario.startTime, tags);
  success.add(responseHash(response) === data.responseHash, tags);
  completed.add(1, tags);
}

export function handleSummary(data) {
  const result = summarize(config, data);
  const latency = result.measurement.latencyMs;
  return {
    stdout: `${config.target}/${config.variant}: ${result.valid ? '유효' : `실패 (${result.reasons.join(', ')})`}\n`
      + `측정 요청 ${result.measurement.completed}건, 오류 ${result.measurement.failed}건, 누락 ${result.measurement.dropped}건\n`
      + `p50=${latency.p50}ms p95=${latency.p95}ms p99=${latency.p99}ms\n`
      + `결과: ${config.resultPath}\n`,
    [config.resultPath]: `${JSON.stringify(result, null, 2)}\n`,
  };
}

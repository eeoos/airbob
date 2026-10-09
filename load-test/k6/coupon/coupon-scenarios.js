import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  buildCouponIssueTarget, classifyCouponIssueResponse, parseCouponSessionFixture,
  parsePositiveInteger, parseVariant, summarizeCouponBenchmarkMetrics,
} from './coupon-benchmark-fixture.js';
const { parseCouponAccountManifest } = require('./coupon-account-manifest.js');

// Used only by the bounded local plan runner. One campaign spans the entire spike/recovery.
const workload = JSON.parse(__ENV.WORKLOAD_SPEC);
const variant = parseVariant(__ENV.VARIANT);
const couponId = parsePositiveInteger(__ENV.COUPON_ID, 'COUPON_ID');
const target = buildCouponIssueTarget(variant, couponId, __ENV.BENCHMARK_READ_MODEL_TOKEN);
const metadata = new SharedArray('scenario-manifest', () => {
  const raw = open(__ENV.BENCHMARK_DATASET_MANIFEST);
  const manifest = parseCouponAccountManifest(raw);
  return [{ hash: crypto.sha256(raw, 'hex'), capacity: manifest.capsules[0].accountPool.capacity }];
})[0];
const sessions = new SharedArray('scenario-sessions', () => (
  parseCouponSessionFixture(open(__ENV.SESSION_FIXTURE), metadata.hash)
));
if (sessions.length < workload.requiredSessions || metadata.capacity < workload.requiredSessions) {
  throw new Error('Insufficient real member sessions for the entire local workload');
}
const issueDuration = new Trend('coupon_issue_duration', true);
const successDuration = new Trend('coupon_issue_success_duration', true);
const soldOutDuration = new Trend('coupon_issue_sold_out_duration', true);
const duplicateDuration = new Trend('coupon_issue_duplicate_duration', true);
const startedAt = new Trend('coupon_request_started_at');
const finishedAt = new Trend('coupon_request_finished_at');
const unexpected = new Rate('coupon_issue_unexpected');
const outcomes = Object.fromEntries(['success', 'sold_out', 'duplicate', 'not_issuable', 'unprepared',
  'contended', 'authentication', 'unexpected'].map(name => [name, new Counter(`coupon_issue_${name}_total`)]));
const metricNames = ['http_reqs', 'dropped_iterations', 'coupon_request_started_at', 'coupon_request_finished_at',
  'coupon_issue_duration', 'coupon_issue_success_duration', 'coupon_issue_sold_out_duration',
  'coupon_issue_duplicate_duration', ...Object.keys(outcomes).map(name => `coupon_issue_${name}_total`)];
const scenarios = {};
const thresholds = { coupon_issue_unexpected: ['rate==0'], dropped_iterations: ['count==0'], http_req_failed: ['rate==0'] };
function segmentMetric(name, segment) {
  // Executor drops happen before a VU can attach custom tags. Use k6's built-in scenario tag.
  return `${name}{${name === 'dropped_iterations' ? 'scenario' : 'segment'}:${segment}}`;
}
for (const segment of workload.segments) {
  scenarios[segment.name] = {
    executor: 'constant-arrival-rate', rate: segment.rate, timeUnit: '1s',
    duration: `${segment.seconds}s`, startTime: `${segment.startSeconds}s`,
    preAllocatedVUs: Math.min(Number(__ENV.PRE_ALLOCATED_VUS), Math.max(50, segment.rate * 2)),
    maxVUs: Math.min(Number(__ENV.MAX_VUS), Math.max(50, segment.rate * 2)),
    gracefulStop: '30s', tags: { segment: segment.name, phase: __ENV.PHASE, variant },
  };
  for (const name of metricNames) {
    // Explicit submetrics retain request-start cohorts; a slow peak response cannot become a recovery request.
    thresholds[segmentMetric(name, segment.name)] = [
      name === 'http_reqs' || name === 'dropped_iterations' || name.endsWith('_total') ? 'count>=0' : 'min>=0',
    ];
  }
  thresholds[`coupon_issue_duration{segment:${segment.name}}`] = [
    `p(95)<=${__ENV.P95_LIMIT_MS}`, `p(99)<=${__ENV.P99_LIMIT_MS}`,
  ];
}
http.setResponseCallback(http.expectedStatuses(201, 409, 503));
export const options = { scenarios, thresholds,
  summaryTrendStats: ['avg', 'min', 'p(50)', 'p(95)', 'p(99)', 'max', 'count'] };

export function setup() {
  for (const segment of workload.segments) {
    for (const counter of Object.values(outcomes)) counter.add(0, { segment: segment.name });
  }
}

export default function () {
  const segment = workload.segments.find(s => s.name === exec.scenario.name);
  const iteration = Number(exec.scenario.iterationInTest);
  if (iteration >= segment.plannedRequests) return;
  const member = workload.scenario === 'duplicate'
    ? Math.floor(iteration / workload.duplicateBurst) % workload.uniqueMembers
    : segment.sessionOffset + iteration;
  const session = sessions[member];
  if (!session) exec.test.abort('Local workload session range exhausted');
  const tags = { segment: segment.name, phase: __ENV.PHASE, variant };
  startedAt.add(Date.now(), tags);
  const response = http.post(`${__ENV.BASE_URL}${target.path}`, null, {
    cookies: { SESSION_ID: session }, headers: target.headers, timeout: __ENV.REQUEST_TIMEOUT,
    tags: { ...tags, name: target.metricName },
  });
  finishedAt.add(Date.now(), tags);
  let code;
  try { if (response.status !== 201) code = response.json().error?.code; } catch (_) { /* Classified below. */ }
  const outcome = classifyCouponIssueResponse(response.status, code);
  issueDuration.add(response.timings.duration, tags);
  if (outcome === 'success') successDuration.add(response.timings.duration, tags);
  if (outcome === 'sold_out') soldOutDuration.add(response.timings.duration, tags);
  if (outcome === 'duplicate') duplicateDuration.add(response.timings.duration, tags);
  outcomes[outcome].add(1, tags);
  unexpected.add(!['success', ...(workload.scenario === 'scarcity' ? ['sold_out'] : []),
    ...(workload.scenario === 'duplicate' ? ['duplicate'] : [])].includes(outcome), tags);
}

export function handleSummary(data) {
  const performance = summarizeCouponBenchmarkMetrics(data);
  performance.duplicateDuration = data.metrics.coupon_issue_duplicate_duration?.values || {};
  performance.segments = {};
  for (const segment of workload.segments) {
    const subset = { metrics: Object.fromEntries(metricNames.map(name => [name,
      data.metrics[segmentMetric(name, segment.name)]])) };
    performance.segments[segment.name] = summarizeCouponBenchmarkMetrics(subset);
    performance.segments[segment.name].duplicateDuration = subset.metrics.coupon_issue_duplicate_duration?.values || {};
  }
  if (Object.values(performance.segments).reduce((n, s) => n + s.droppedIterations, 0) !== performance.droppedIterations) {
    throw new Error('Per-scenario executor drops do not match the overall counter');
  }
  const artifact = {
    metadata: {
      generatedAt: new Date().toISOString(), experiment: workload.scenario, workload,
      couponId, couponStock: Number(__ENV.COUPON_STOCK), runLabel: __ENV.RUN_LABEL,
      variant, phase: __ENV.PHASE, appVersion: __ENV.APP_VERSION,
      appInstanceCount: Number(__ENV.APP_INSTANCE_COUNT), duration: __ENV.DURATION,
      round: Number(__ENV.ROUND), runOrder: Number(__ENV.RUN_ORDER),
      benchmarkDatasetManifestSha256: metadata.hash, plannedRequests: workload.plannedRequests,
      requiredUniqueSessions: workload.requiredSessions,
      scenarios,
    }, performance, summary: data,
  };
  return { stdout: JSON.stringify({ workload: workload.name, outcomes: performance.outcomes,
    dropped: performance.droppedIterations }) + '\n',
  [__ENV.K6_RESULT_PATH]: JSON.stringify(artifact, null, 2) };
}

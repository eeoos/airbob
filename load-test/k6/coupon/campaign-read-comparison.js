import http from 'k6/http';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';

const config = JSON.parse(open(__ENV.CAMPAIGN_READ_CONFIG));
const invalid = new Counter('response_mismatches');
export const options = {
  scenarios: { read: { executor: 'constant-arrival-rate', rate: config.rate, timeUnit: '1s',
    duration: `${config.seconds}s`, preAllocatedVUs: 20, maxVUs: 40 } },
  thresholds: { http_req_failed: ['rate==0'], response_mismatches: ['count==0'], dropped_iterations: ['count==0'] },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const response = http.get(`${config.origin}/api/v2/coupons/campaigns/${config.variant}`, {
    headers: { Cookie: `SESSION_ID=${config.session}`, 'X-Benchmark-Token': config.token },
    timeout: '5s',
  });
  invalid.add(response.status !== 200 || crypto.sha256(response.body, 'hex') !== config.responseHash ? 1 : 0);
}

export function handleSummary(data) {
  return { [config.summaryFile]: JSON.stringify(data, null, 2) };
}

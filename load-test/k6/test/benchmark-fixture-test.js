import { check } from 'k6';
import {
  benchmarkLoginRequestParams,
  benchmarkHeaders,
} from '../lib/benchmark-fixture.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const headers = benchmarkHeaders(' benchmark-token ', {
    'Content-Type': 'application/json',
  });
  const defaultLoginParams = benchmarkLoginRequestParams();
  const noRedirectLoginParams = benchmarkLoginRequestParams({ redirects: 0 });

  check(null, {
    'benchmark token header is added without dropping existing headers': () => (
      headers['X-Benchmark-Token'] === 'benchmark-token'
        && headers['Content-Type'] === 'application/json'
    ),
    'blank benchmark token is rejected': () => {
      try {
        benchmarkHeaders(' ');
        return false;
      } catch (_) {
        return true;
      }
    },
    'default login params preserve existing redirect behavior': () => (
      !Object.prototype.hasOwnProperty.call(defaultLoginParams, 'redirects')
        && defaultLoginParams.headers['Content-Type'] === 'application/json'
        && defaultLoginParams.tags.phase === 'setup'
    ),
    'bulk login params disable redirects per request': () => (
      noRedirectLoginParams.redirects === 0
        && noRedirectLoginParams.headers['Content-Type'] === 'application/json'
        && noRedirectLoginParams.tags.name === 'POST /api/v1/auth/login'
    ),
    'negative login redirect limits are rejected': () => {
      try {
        benchmarkLoginRequestParams({ redirects: -1 });
        return false;
      } catch (_) {
        return true;
      }
    },
  });
}

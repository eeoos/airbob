import { check } from 'k6';
import {
  benchmarkHeaders,
  buildRecentlyViewedFixtureBody,
} from '../lib/benchmark-fixture.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const payload = JSON.parse(buildRecentlyViewedFixtureBody({
    accommodationIds: [251, 252, 253],
    datasetSize: 2,
  }));
  const headers = benchmarkHeaders(' benchmark-token ', {
    'Content-Type': 'application/json',
  });

  check(payload, {
    'fixture body uses snake case': (value) => (
      Object.prototype.hasOwnProperty.call(value, 'accommodation_ids')
    ),
    'fixture body keeps requested latest-first order': (value) => (
      JSON.stringify(value.accommodation_ids) === JSON.stringify([251, 252])
    ),
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
  });
}

import { check } from 'k6';
import { buildRecentlyViewedFixtureBody } from '../lib/benchmark-fixture.js';

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

  check(payload, {
    'fixture body uses snake case': (value) => (
      Object.prototype.hasOwnProperty.call(value, 'accommodation_ids')
    ),
    'fixture body keeps requested latest-first order': (value) => (
      JSON.stringify(value.accommodation_ids) === JSON.stringify([251, 252])
    ),
  });
}

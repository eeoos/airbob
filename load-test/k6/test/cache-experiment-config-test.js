import { check } from 'k6';
import { experimentTarget } from '../lib/cache-experiment-config.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
const rejects = (env) => { try { experimentTarget(env); return false; } catch (_) { return true; } };
const aws = { EXPERIMENT_ENVIRONMENT: 'aws', EXPERIMENT_ORIGINS: '["https://api.airbob.cloud"]',
  EXPERIMENT_HOSTS: '{"api.airbob.cloud":"10.1.2.3"}' };

export default function () {
  check(null, {
    'local loopback stays supported': () => experimentTarget({ EXPERIMENT_ORIGINS: '["http://127.0.0.1:1234"]' }).scope === 'local',
    'local rejects remote destination': () => rejects({ EXPERIMENT_ORIGINS: '["https://api.airbob.cloud"]' }),
    'local rejects DNS override': () => rejects({ EXPERIMENT_ORIGINS: '["http://127.0.0.1:1234"]', EXPERIMENT_HOSTS: '{}' }),
    'AWS requires explicit verified routing': () => rejects({ ...aws, EXPERIMENT_HOSTS: undefined }),
    'AWS retains the certificate hostname': () => experimentTarget(aws).hosts['api.airbob.cloud'] === '10.1.2.3',
    'AWS rejects another origin': () => rejects({ ...aws, EXPERIMENT_ORIGINS: '["https://example.com"]' }),
    'AWS rejects link-local destinations': () => rejects({ ...aws, EXPERIMENT_HOSTS: '{"api.airbob.cloud":"169.254.169.254"}' }),
    'AWS rejects malformed IP': () => rejects({ ...aws, EXPERIMENT_HOSTS: '{"api.airbob.cloud":"10.1.2.999"}' }),
    'AWS allows longer capacity confirmation': () => experimentTarget(aws).limits.seconds >= 300,
  });
}

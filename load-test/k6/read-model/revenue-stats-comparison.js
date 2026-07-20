import { loginBenchmarkAccount } from '../lib/benchmark-fixture.js';
import {
  buildReadModelPath,
  parseIsoDate,
  parsePositiveInteger,
  parseRequiredText,
  parseReadModelRunConfig,
} from '../lib/read-model-benchmark.js';
import { createReadModelBenchmark } from '../lib/read-model-runner.js';

const DOMAIN = 'revenue';
const RUN = parseReadModelRunConfig(__ENV, 'revenue');
const ADMIN_EMAIL = parseRequiredText(__ENV.ADMIN_EMAIL, 'ADMIN_EMAIL');
const ADMIN_PASSWORD = parseRequiredText(__ENV.ADMIN_PASSWORD, 'ADMIN_PASSWORD');
const REVENUE_FROM = parseIsoDate(__ENV.REVENUE_FROM, 'REVENUE_FROM');
const REVENUE_TO = parseIsoDate(__ENV.REVENUE_TO, 'REVENUE_TO');
if (REVENUE_FROM > REVENUE_TO) {
  throw new Error('REVENUE_FROM must be on or before REVENUE_TO');
}
const EXPECTED_COUNT = parsePositiveInteger(
  parseRequiredText(__ENV.EXPECTED_ROWS, 'EXPECTED_ROWS'),
  'EXPECTED_ROWS',
);
const BEFORE_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'before',
  from: REVENUE_FROM,
  to: REVENUE_TO,
});
const AFTER_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'after',
  from: REVENUE_FROM,
  to: REVENUE_TO,
});
const TARGET_PATH = RUN.variant === 'before' ? BEFORE_PATH : AFTER_PATH;

const benchmark = createReadModelBenchmark({
  ...RUN,
  domain: DOMAIN,
  beforePath: BEFORE_PATH,
  afterPath: AFTER_PATH,
  targetPath: TARGET_PATH,
  requestName: `GET /api/${RUN.variant === 'before' ? 'v2' : 'v1'}/admin/stats/revenue`,
  expectedCount: EXPECTED_COUNT,
  contract: { from: REVENUE_FROM, to: REVENUE_TO },
  setup: () => ({
    sessionId: loginBenchmarkAccount({
      baseUrl: RUN.baseUrl,
      email: ADMIN_EMAIL,
      password: ADMIN_PASSWORD,
    }),
  }),
  metadata: {
    ...RUN.metadata,
    count_unit: 'revenue_days',
    revenue_from: REVENUE_FROM,
    revenue_to: REVENUE_TO,
  },
});

export const options = benchmark.options;
export function setup() { return benchmark.setup(); }
export function warmup(data) { benchmark.warmup(data); }
export function measure(data) { benchmark.measure(data); }
export function handleSummary(data) { return benchmark.handleSummary(data); }

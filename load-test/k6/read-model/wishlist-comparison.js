import { loginBenchmarkAccount } from '../lib/benchmark-fixture.js';
import {
  buildReadModelPath,
  parsePositiveInteger,
  parseRequiredText,
  parseReadModelRunConfig,
} from '../lib/read-model-benchmark.js';
import { createReadModelBenchmark } from '../lib/read-model-runner.js';

const DOMAIN = 'wishlist';
const RUN = parseReadModelRunConfig(__ENV, 'wishlist');
const BENCHMARK_EMAIL = parseRequiredText(__ENV.BENCHMARK_EMAIL, 'BENCHMARK_EMAIL');
const TEST_PASSWORD = parseRequiredText(__ENV.TEST_PASSWORD, 'TEST_PASSWORD');
const PAGE_SIZE = parsePositiveInteger(__ENV.PAGE_SIZE || '50', 'PAGE_SIZE');
const EXPECTED_COUNT = parsePositiveInteger(
  parseRequiredText(__ENV.EXPECTED_ROWS, 'EXPECTED_ROWS'),
  'EXPECTED_ROWS',
);
if (PAGE_SIZE > 50) {
  throw new Error('PAGE_SIZE must not exceed 50');
}
if (EXPECTED_COUNT > PAGE_SIZE) {
  throw new Error('EXPECTED_ROWS must not exceed PAGE_SIZE');
}

const BEFORE_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'before',
  size: PAGE_SIZE,
});
const AFTER_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'after',
  size: PAGE_SIZE,
});
const TARGET_PATH = RUN.variant === 'before' ? BEFORE_PATH : AFTER_PATH;

const benchmark = createReadModelBenchmark({
  ...RUN,
  domain: DOMAIN,
  beforePath: BEFORE_PATH,
  afterPath: AFTER_PATH,
  targetPath: TARGET_PATH,
  requestName: `GET /api/${RUN.variant === 'before' ? 'v2' : 'v1'}/members/wishlists`,
  expectedCount: EXPECTED_COUNT,
  setup: () => ({
    sessionId: loginBenchmarkAccount({
      baseUrl: RUN.baseUrl,
      email: BENCHMARK_EMAIL,
      password: TEST_PASSWORD,
    }),
  }),
  metadata: {
    ...RUN.metadata,
    count_unit: 'returned_wishlists',
    page_size: PAGE_SIZE,
  },
});

export const options = benchmark.options;
export function setup() { return benchmark.setup(); }
export function warmup(data) { benchmark.warmup(data); }
export function measure(data) { benchmark.measure(data); }
export function handleSummary(data) { return benchmark.handleSummary(data); }

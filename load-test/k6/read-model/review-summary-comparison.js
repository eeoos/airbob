import {
  buildReadModelPath,
  parsePositiveInteger,
  parseRequiredText,
  parseReadModelRunConfig,
} from '../lib/read-model-benchmark.js';
import { createReadModelBenchmark } from '../lib/read-model-runner.js';

const DOMAIN = 'review';
const RUN = parseReadModelRunConfig(__ENV, 'review-summary');
const ACCOMMODATION_ID = parsePositiveInteger(
  parseRequiredText(__ENV.REVIEW_ACCOMMODATION_ID, 'REVIEW_ACCOMMODATION_ID'),
  'REVIEW_ACCOMMODATION_ID',
);
const EXPECTED_COUNT = parsePositiveInteger(
  parseRequiredText(__ENV.EXPECTED_REVIEW_COUNT, 'EXPECTED_REVIEW_COUNT'),
  'EXPECTED_REVIEW_COUNT',
);
const BEFORE_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'before',
  accommodationId: ACCOMMODATION_ID,
});
const AFTER_PATH = buildReadModelPath({
  domain: DOMAIN,
  variant: 'after',
  accommodationId: ACCOMMODATION_ID,
});

const benchmark = createReadModelBenchmark({
  ...RUN,
  domain: DOMAIN,
  beforePath: BEFORE_PATH,
  afterPath: AFTER_PATH,
  requestName: `GET /api/${RUN.variant === 'before' ? 'v2' : 'v1'}/accommodations/{accommodationId}/reviews/summary`,
  expectedCount: EXPECTED_COUNT,
  metadata: {
    ...RUN.metadata,
    accommodation_id: ACCOMMODATION_ID,
    count_unit: 'published_reviews',
  },
});

export const options = benchmark.options;
export function setup() { return benchmark.setup(); }
export function warmup(data) { benchmark.warmup(data); }
export function measure(data) { benchmark.measure(data); }
export function handleSummary(data) { return benchmark.handleSummary(data); }

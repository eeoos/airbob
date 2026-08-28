import {
  buildReadModelTarget,
  parseReadModelEvidenceContext,
  parseReadModelRunConfig,
  parseRequiredText,
} from '../lib/read-model-benchmark.js';
import { createReadModelBenchmark } from '../lib/read-model-runner.js';

const MANIFEST_PATH = parseRequiredText(
  __ENV.BENCHMARK_DATASET_MANIFEST,
  'BENCHMARK_DATASET_MANIFEST',
);
const CONTEXT_PATH = parseRequiredText(
  __ENV.READ_MODEL_EVIDENCE_CONTEXT,
  'READ_MODEL_EVIDENCE_CONTEXT',
);
const TARGET = buildReadModelTarget(
  open(MANIFEST_PATH),
  parseRequiredText(__ENV.TARGET_ID, 'TARGET_ID'),
);
if (TARGET.domain !== 'review') {
  throw new Error('TARGET_ID must select REVIEW_SUMMARY_V1');
}
const CONTEXT = parseReadModelEvidenceContext(
  open(CONTEXT_PATH),
  TARGET,
  __ENV.VARIANT,
);
const RUN = parseReadModelRunConfig(__ENV, TARGET, CONTEXT);
const benchmark = createReadModelBenchmark({
  ...RUN,
  measurementSummary: RUN.mode === 'assemble'
    ? JSON.parse(open(parseRequiredText(
      __ENV.READ_MODEL_MEASUREMENT_SUMMARY,
      'READ_MODEL_MEASUREMENT_SUMMARY',
    )))
    : null,
});

export const options = benchmark.options;
export function assemble() { benchmark.assemble(); }
export function setup() { return benchmark.setup(); }
export function warmup(data) { benchmark.warmup(data); }
export function measure(data) { benchmark.measure(data); }
export function handleSummary(data) { return benchmark.handleSummary(data); }

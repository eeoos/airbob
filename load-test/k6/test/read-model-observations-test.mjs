import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  copyFileSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

const testDir = dirname(fileURLToPath(import.meta.url));
const aggregatorPath = resolve(
  testDir,
  '../read-model/aggregate-read-model-observations.mjs',
);
const aggregator = await import(pathToFileURL(aggregatorPath));

const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const SHA_C = 'c'.repeat(64);
const SHA_D = 'd'.repeat(64);
const SHA_E = 'e'.repeat(64);
const SHA_F = 'f'.repeat(64);

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function sourceArtifact({
  index = 1,
  design = 'READ_MODEL_AB',
  blockId = 'block-01',
  pairRole = 'BEFORE',
  variant = 'before',
  domain = 'review',
  targetClass = 'hot',
  targetId = 'review-hot',
  targetQueryKind = 'REVIEW_SUMMARY',
  candidateIndex = null,
  candidateVisible = null,
  useInvisibleIndexes = false,
  candidateInChosenPlan = false,
  cloneId = 'clone-a',
  p50 = 20,
  p95 = 30,
  p99 = 40,
} = {}) {
  const windowId = `window-${String(index).padStart(2, '0')}`;
  const eventId = `thread-7-event-${100 + index}`;
  const selectedKey = candidateInChosenPlan ? candidateIndex : 'FK_review_accommodation_id';
  const jsonRaw = JSON.stringify({
    query_block: {
      table: {
        table_name: 'review',
        possible_keys: candidateIndex ? ['FK_review_accommodation_id', candidateIndex] : [],
        key: selectedKey,
      },
    },
  });
  const treeRaw = [
    '-> Aggregate: count(0)  (cost=1.25e+3 rows=1)',
    `    -> Index lookup on review using ${selectedKey}  (cost=2.4 rows=1.20K)`,
    `       (actual time=1.5e-2..2.50E+1 rows=${index === 1 ? '1.20K' : '1.2e3'} loops=1)`,
  ].join('\n');

  return {
    schema_version: 'read-model-evidence-v1',
    metadata: {
      generated_at: `2026-08-27T00:00:${String(index).padStart(2, '0')}.000Z`,
      run_id: 'read-model-run',
      design,
      experiment_id: 'review-hot-ab',
      block_id: blockId,
      window_id: windowId,
      statement_event_id: eventId,
      domain,
      target_class: targetClass,
      variant,
      pair_role: pairRole,
      phase: 'measure',
      release_tuple: {
        release_id: 'airbob-v27-production-skew-20260827',
        dataset_version: 'benchmark-dataset-v2',
        world_version: 'world-v2',
        source_calibration_sha256: SHA_A,
        production_skew_spec_sha256: SHA_B,
        dataset_manifest_sha256: SHA_C,
        dump_sha256: SHA_D,
        schema_migration_sha256: SHA_E,
        target_fingerprint_sha256: SHA_F,
      },
      manifest_target: {
        capsule_id: 'read-model-v1',
        target_id: targetId,
        query_kind: targetQueryKind,
        parameter_hash_sha256: SHA_A,
        expected_rows: 1200,
        expected_result_hash: SHA_B,
        account_ref: null,
      },
      app_build: {
        commit_sha: '1'.repeat(40),
        image_digest: `sha256:${SHA_C}`,
        build_id: 'airbob-read-model-20260827',
        instance_count: 1,
        runtime_revision: SHA_E,
        app_instance_id: 'i-0123456789abcdef0',
        resource_fencing_token_sha256: SHA_B,
      },
      database: {
        clone_id: cloneId,
        pre_fingerprint_sha256: SHA_D,
        post_fingerprint_sha256: SHA_D,
        optimizer_snapshot_sha256: SHA_E,
        statistics_snapshot_sha256: SHA_F,
        histogram_snapshot_sha256: SHA_A,
        analyze_receipt_sha256: SHA_B,
        mysql_version: '8.0.42',
        auto_statistics_recalculation_detected: false,
      },
      treatment: {
        kind: design === 'INVISIBLE_INDEX_AB' ? 'INVISIBLE_INDEX' : 'READ_MODEL',
        candidate_index: candidateIndex,
        candidate_visible: candidateVisible,
        optimizer_switch_use_invisible_indexes: useInvisibleIndexes,
      },
    },
    validity: {
      status: 'valid',
      reasons: [],
      errors: 0,
      dropped_iterations: 0,
    },
    parity: {
      verified: true,
      expected_rows: 1200,
      observed_rows: 1200,
      expected_result_hash: SHA_B,
      before_result_hash: SHA_B,
      after_result_hash: SHA_B,
    },
    performance: {
      headline_scope: 'measure-only',
      excluded_phases: ['setup', 'login', 'analyze', 'explain'],
      requests: {
        attempted: 100,
        successful: 100,
        failed: 0,
        dropped_iterations: 0,
      },
      latency_ms: {
        count: 100,
        min: p50 / 2,
        p50,
        p95,
        p99,
        max: p99 + 10,
      },
    },
    measurement_fencing_token_sha256: SHA_C,
    runtime_assertion: {
      runtime_assertion_pre_sha256: SHA_A,
      runtime_assertion_post_sha256: SHA_B,
      pre: {
        schema_version: 1,
        run_id: 'read-model-run',
        resource_fencing_token_sha256: SHA_B,
        challenge_sha256: SHA_D,
        runtime_revision: SHA_E,
        app_instance_id: 'i-0123456789abcdef0',
        active_profiles: ['aws', 'read-model-benchmark', 'traffic-benchmark'],
        scheduler_enabled: false,
        kafka_listener_enabled: false,
        inventory_lifecycle_enabled: false,
        external_side_effects_enabled: false,
      },
      post: {
        schema_version: 1,
        run_id: 'read-model-run',
        resource_fencing_token_sha256: SHA_B,
        challenge_sha256: SHA_F,
        runtime_revision: SHA_E,
        app_instance_id: 'i-0123456789abcdef0',
        active_profiles: ['aws', 'read-model-benchmark', 'traffic-benchmark'],
        scheduler_enabled: false,
        kafka_listener_enabled: false,
        inventory_lifecycle_enabled: false,
        external_side_effects_enabled: false,
      },
    },
    mysql_evidence: {
      statement_event: {
        window_id: windowId,
        event_id: eventId,
        digest: SHA_C,
        digest_text: 'SELECT COUNT ( * ) FROM `review` WHERE `accommodation_id` = ? AND `status` = ?',
        delta: {
          calls: 100,
          timer_wait_ps: '25000000000',
          rows_examined: '120000',
          rows_sent: '100',
          errors: 0,
        },
      },
      optimizer_state: {
        snapshot_sha256: SHA_E,
        statistics_snapshot_sha256: SHA_F,
        histogram_snapshot_sha256: SHA_A,
        analyze_receipt_sha256: SHA_B,
      },
      explain: {
        json_raw: jsonRaw,
        tree_raw: treeRaw,
        candidate_in_chosen_plan: candidateInChosenPlan,
      },
    },
  };
}

function readModelPair(overrides = {}) {
  return [
    sourceArtifact({
      index: 1,
      blockId: 'block-01',
      pairRole: 'BEFORE',
      variant: 'before',
      p50: 20,
      p95: 30,
      p99: 40,
      ...overrides,
    }),
    sourceArtifact({
      index: 2,
      blockId: 'block-01',
      pairRole: 'AFTER',
      variant: 'after',
      p50: 10,
      p95: 15,
      p99: 20,
      ...overrides,
    }),
  ];
}

function invisibleIndexPair({ chosen = true, candidate = 'idx_review_status_accommodation' } = {}) {
  return [
    sourceArtifact({
      index: 1,
      design: 'INVISIBLE_INDEX_AB',
      blockId: 'block-01',
      pairRole: 'INDEX_BASELINE',
      variant: 'before',
      candidateIndex: candidate,
      candidateVisible: false,
      useInvisibleIndexes: false,
      candidateInChosenPlan: false,
      p50: 20,
      p95: 30,
      p99: 40,
    }),
    sourceArtifact({
      index: 2,
      design: 'INVISIBLE_INDEX_AB',
      blockId: 'block-01',
      pairRole: 'INDEX_CANDIDATE',
      variant: 'before',
      candidateIndex: candidate,
      candidateVisible: false,
      useInvisibleIndexes: true,
      candidateInChosenPlan: chosen,
      p50: 10,
      p95: 15,
      p99: 20,
    }),
  ];
}

test('engineering notation parser accepts exponent and SI forms', () => {
  assert.equal(aggregator.parseEngineeringNumber('1.25e+3'), 1250);
  assert.equal(aggregator.parseEngineeringNumber('2.5E-2'), 0.025);
  assert.equal(aggregator.parseEngineeringNumber('1.20K'), 1200);
  assert.equal(aggregator.parseEngineeringNumber('3M'), 3_000_000);
  assert.throws(() => aggregator.parseEngineeringNumber('1.2KiB'));
  assert.throws(() => aggregator.parseEngineeringNumber('Infinity'));
});

test('EXPLAIN ANALYZE parser preserves engineering-notation row and timing values', () => {
  const tree = [
    '-> Aggregate  (cost=1.2e3 rows=1)',
    '   (actual time=1.5e-2..2.50E+1 rows=1.20K loops=2)',
    '   -> Table scan (actual time=2ms..3ms rows=3M loops=1)',
  ].join('\n');
  const parsed = aggregator.parseExplainAnalyzeTree(tree);
  assert.deepEqual(parsed.root, {
    first_row_ms: 0.015,
    last_row_ms: 25,
    actual_rows: 1200,
    loops: 2,
  });
  assert.equal(parsed.iterators[1].actual_rows, 3_000_000);
  assert.equal(parsed.iterators[1].first_row_ms, 2);
});

test('candidate detection uses the chosen key, not possible_keys or raw substring', () => {
  const candidate = 'idx_review_status_accommodation';
  const notChosen = JSON.stringify({
    query_block: {
      note: `candidate ${candidate} exists`,
      table: { possible_keys: [candidate], key: 'PRIMARY' },
    },
  });
  assert.equal(aggregator.chosenPlanUsesCandidate(notChosen, candidate), false);
  const chosen = JSON.stringify({
    query_block: { table: { possible_keys: ['PRIMARY', candidate], key: candidate } },
  });
  assert.equal(aggregator.chosenPlanUsesCandidate(chosen, candidate), true);
});

test('read-model A/B aggregation binds provenance and preserves raw evidence', () => {
  const sources = [
    ...readModelPair(),
    ...readModelPair().map((source, offset) => {
      const copy = clone(source);
      copy.metadata.block_id = 'block-02';
      copy.metadata.window_id = `window-${offset + 3}`;
      copy.metadata.statement_event_id = `thread-7-event-${103 + offset}`;
      copy.mysql_evidence.statement_event.window_id = copy.metadata.window_id;
      copy.mysql_evidence.statement_event.event_id = copy.metadata.statement_event_id;
      return copy;
    }),
  ];
  const result = aggregator.aggregateReadModelEvidence(sources);
  assert.equal(result.schema_version, 'read-model-observations-v1');
  assert.equal(result.eligibility.status, 'valid');
  assert.equal(result.eligibility.headline_allowed, true);
  assert.equal(result.headline.paired_effects.length, 2);
  assert.equal(result.headline.median_improvement.p50, 0.5);
  assert.equal(result.observations.length, 4);
  assert.equal(result.observations[0].mysql_evidence.explain.json_raw,
    sources[0].mysql_evidence.explain.json_raw);
  assert.equal(result.observations[0].mysql_evidence.explain.tree_raw,
    sources[0].mysql_evidence.explain.tree_raw);
  assert.equal(result.observations[0].mysql_evidence.explain.parsed_tree.root.actual_rows, 1200);
  assert.deepEqual(
    result.observations.map((observation) => observation.mysql_evidence.statement_event.window_id),
    ['window-01', 'window-02', 'window-3', 'window-4'],
  );
  assert.equal(new Set(result.observations.map(
    (observation) => observation.mysql_evidence.statement_event.digest,
  )).size, 1, 'same normalized digest stays separated by window/event identity');
});

test('A/A design produces a noise envelope instead of an improvement claim', () => {
  const sources = readModelPair({ design: 'AA_NOISE' });
  sources[0].metadata.pair_role = 'AA_A';
  sources[0].metadata.variant = 'after';
  sources[1].metadata.pair_role = 'AA_B';
  sources[1].metadata.variant = 'after';
  const result = aggregator.aggregateReadModelEvidence(sources);
  assert.equal(result.headline.kind, 'AA_NOISE_ENVELOPE');
  assert.equal(result.headline.maximum_absolute_relative_delta.p50, 0.5);
});

test('candidate AA validator requires an exact reconstructable six-window artifact', () => {
  const sources = [];
  for (let block = 1; block <= 3; block += 1) {
    const pair = readModelPair({ design: 'AA_NOISE' });
    pair[0].metadata.pair_role = 'AA_A';
    pair[0].metadata.variant = 'after';
    pair[1].metadata.pair_role = 'AA_B';
    pair[1].metadata.variant = 'after';
    pair.forEach((source, offset) => {
      source.metadata.block_id = `aa-${String(block).padStart(2, '0')}`;
      source.metadata.window_id = `aa-${block}-${offset}`;
      source.metadata.statement_event_id = `aa-event-${block}-${offset}`;
      source.mysql_evidence.statement_event.window_id = source.metadata.window_id;
      source.mysql_evidence.statement_event.event_id = source.metadata.statement_event_id;
      sources.push(source);
    });
  }
  const artifact = aggregator.aggregateReadModelEvidence(sources);

  assert.equal(aggregator.validateAaObservationArtifact(artifact), artifact);

  const extra = clone(artifact);
  extra.debug = true;
  assert.throws(() => aggregator.validateAaObservationArtifact(extra), /exact aggregate/);
  const missing = clone(artifact);
  missing.observations.pop();
  assert.throws(() => aggregator.validateAaObservationArtifact(missing), /six observations/);
  const rewritten = clone(artifact);
  rewritten.observations[0].performance.latency_ms.p95 += 1;
  assert.throws(() => aggregator.validateAaObservationArtifact(rewritten), /reconstructable/);
});

test('invalid status, errors, or dropped iterations reject aggregation', () => {
  const invalid = readModelPair();
  invalid[0].validity.status = 'invalid';
  invalid[0].validity.reasons = ['scheduler-enabled'];
  assert.throws(() => aggregator.aggregateReadModelEvidence(invalid), /source validity/);

  const errors = readModelPair();
  errors[0].performance.requests.failed = 1;
  errors[0].performance.requests.successful = 99;
  errors[0].validity.errors = 1;
  assert.throws(() => aggregator.aggregateReadModelEvidence(errors), /error-free/);

  const dropped = readModelPair();
  dropped[0].performance.requests.dropped_iterations = 1;
  dropped[0].validity.dropped_iterations = 1;
  assert.throws(() => aggregator.aggregateReadModelEvidence(dropped), /dropped/);
});

test('pre/post database fingerprint drift rejects aggregation', () => {
  const sources = readModelPair();
  sources[0].metadata.database.post_fingerprint_sha256 = SHA_E;
  assert.throws(() => aggregator.aggregateReadModelEvidence(sources), /fingerprint drift/);
});

test('runtime assertions bind run, resource fence, and fresh pre/post challenges', () => {
  const wrongRun = readModelPair();
  wrongRun[0].runtime_assertion.pre.run_id = 'another-run';
  wrongRun[0].runtime_assertion.post.run_id = 'another-run';
  assert.throws(() => aggregator.aggregateReadModelEvidence(wrongRun), /runtime assertion/);

  const wrongResourceFence = readModelPair();
  wrongResourceFence[0].runtime_assertion.pre.resource_fencing_token_sha256 = SHA_C;
  wrongResourceFence[0].runtime_assertion.post.resource_fencing_token_sha256 = SHA_C;
  assert.throws(() => aggregator.aggregateReadModelEvidence(wrongResourceFence), /runtime assertion/);

  const replay = readModelPair();
  replay[0].runtime_assertion.post.challenge_sha256 =
    replay[0].runtime_assertion.pre.challenge_sha256;
  replay[0].runtime_assertion.runtime_assertion_post_sha256 =
    replay[0].runtime_assertion.runtime_assertion_pre_sha256;
  assert.throws(() => aggregator.aggregateReadModelEvidence(replay), /identity drift/);
});

test('target, clone, statistics, and histogram drift cannot be mixed', () => {
  const targetDrift = readModelPair();
  targetDrift[1].metadata.manifest_target.target_id = 'review-cold';
  assert.throws(() => aggregator.aggregateReadModelEvidence(targetDrift), /metadata does not match/);

  const cloneDrift = readModelPair();
  cloneDrift[1].metadata.database.clone_id = 'clone-b';
  assert.throws(() => aggregator.aggregateReadModelEvidence(cloneDrift), /metadata does not match/);

  const statsDrift = readModelPair();
  statsDrift[1].metadata.database.statistics_snapshot_sha256 = SHA_E;
  statsDrift[1].mysql_evidence.optimizer_state.statistics_snapshot_sha256 = SHA_E;
  assert.throws(() => aggregator.aggregateReadModelEvidence(statsDrift), /metadata does not match/);

  const histogramDrift = readModelPair();
  histogramDrift[1].metadata.database.histogram_snapshot_sha256 = SHA_E;
  histogramDrift[1].mysql_evidence.optimizer_state.histogram_snapshot_sha256 = SHA_E;
  assert.throws(() => aggregator.aggregateReadModelEvidence(histogramDrift), /metadata does not match/);
});

test('a complete isolated pair is required for every block', () => {
  assert.throws(
    () => aggregator.aggregateReadModelEvidence([readModelPair()[0]]),
    /pair contract/,
  );
  const duplicates = readModelPair();
  duplicates[1].metadata.pair_role = 'BEFORE';
  duplicates[1].metadata.variant = 'before';
  assert.throws(() => aggregator.aggregateReadModelEvidence(duplicates), /pair contract/);
});

test('invisible candidate absent from chosen plan retains raw evidence but blocks headline', () => {
  const sources = invisibleIndexPair({ chosen: false });
  const result = aggregator.aggregateReadModelEvidence(sources);
  assert.deepEqual(result.eligibility, {
    status: 'invalid',
    reasons: ['candidate-not-in-chosen-plan'],
    headline_allowed: false,
  });
  assert.equal(result.headline, null);
  assert.equal(result.observations.length, 2);
  assert.equal(
    result.observations[1].mysql_evidence.explain.candidate_in_chosen_plan,
    false,
  );
  assert.match(
    result.observations[1].mysql_evidence.explain.json_raw,
    /idx_review_status_accommodation/,
  );
});

test('invisible candidate can headline only when it is invisible and actually chosen', () => {
  const result = aggregator.aggregateReadModelEvidence(invisibleIndexPair({ chosen: true }));
  assert.equal(result.eligibility.status, 'valid');
  assert.equal(result.headline.kind, 'INVISIBLE_INDEX_IMPROVEMENT');
  assert.equal(result.headline.median_improvement.p95, 0.5);

  const visible = invisibleIndexPair({ chosen: true });
  visible[1].metadata.treatment.candidate_visible = true;
  assert.throws(() => aggregator.aggregateReadModelEvidence(visible), /invisible candidate/);

  const switchOff = invisibleIndexPair({ chosen: true });
  switchOff[1].metadata.treatment.optimizer_switch_use_invisible_indexes = false;
  assert.throws(() => aggregator.aggregateReadModelEvidence(switchOff), /optimizer switch/);
});

test('candidate_in_chosen_plan must agree with structured EXPLAIN JSON', () => {
  const sources = invisibleIndexPair({ chosen: true });
  sources[1].mysql_evidence.explain.json_raw = JSON.stringify({
    query_block: {
      table: {
        possible_keys: ['idx_review_status_accommodation'],
        key: 'PRIMARY',
      },
    },
  });
  assert.throws(() => aggregator.aggregateReadModelEvidence(sources), /chosen-plan claim/);
});

test('one aggregate cannot contain multiple invisible candidates', () => {
  const sources = invisibleIndexPair({ chosen: true });
  sources[1].metadata.treatment.candidate_index = 'idx_other_candidate';
  sources[1].mysql_evidence.explain.candidate_in_chosen_plan = false;
  assert.throws(() => aggregator.aggregateReadModelEvidence(sources), /metadata does not match/);
});

test('unknown keys and secret or PII-shaped values fail closed', () => {
  const unknown = readModelPair();
  unknown[0].metadata.debug = true;
  assert.throws(() => aggregator.aggregateReadModelEvidence(unknown), /contract/);

  const email = readModelPair();
  email[0].mysql_evidence.explain.tree_raw += '\nadmin@example.com';
  assert.throws(() => aggregator.aggregateReadModelEvidence(email), /sensitive/);

  const credential = readModelPair();
  credential[0].mysql_evidence.explain.tree_raw += '\npassword = hunter2';
  assert.throws(() => aggregator.aggregateReadModelEvidence(credential), /sensitive/);
});

function createTemporaryRepository() {
  const repository = mkdtempSync(resolve(tmpdir(), 'read-model-observations-test.'));
  const scriptDirectory = resolve(repository, 'load-test/k6/read-model');
  const artifactDirectory = resolve(repository, 'build/k6/read-model');
  mkdirSync(scriptDirectory, { recursive: true });
  mkdirSync(artifactDirectory, { recursive: true });
  copyFileSync(aggregatorPath, resolve(scriptDirectory, 'aggregate-read-model-observations.mjs'));
  return { repository, scriptDirectory, artifactDirectory };
}

function runCli(repository, args) {
  return spawnSync(
    process.execPath,
    [resolve(repository, 'load-test/k6/read-model/aggregate-read-model-observations.mjs'), ...args],
    { cwd: repository, encoding: 'utf8' },
  );
}

test('CLI publishes atomically inside the fixed artifact boundary', () => {
  const { repository, artifactDirectory } = createTemporaryRepository();
  try {
    const sources = readModelPair();
    const paths = sources.map((source, index) => {
      const relative = `build/k6/read-model/run-${index + 1}.json`;
      writeFileSync(resolve(repository, relative), `${JSON.stringify(source)}\n`);
      return relative;
    });
    const output = 'build/k6/read-model/review-hot-observations.json';
    const result = runCli(repository, ['--output', output, ...paths]);
    assert.equal(result.status, 0, result.stderr);
    const published = JSON.parse(readFileSync(resolve(repository, output), 'utf8'));
    assert.equal(published.eligibility.status, 'valid');
    assert.equal(lstatSync(resolve(repository, output)).mode & 0o777, 0o600);
    assert.deepEqual(
      lstatSync(artifactDirectory).isSymbolicLink(),
      false,
    );

    const second = runCli(repository, ['--output', output, ...paths]);
    assert.notEqual(second.status, 0);
    assert.match(second.stderr, /already exists/);
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

test('CLI rejects symlink sources, traversal output, and oversized input', () => {
  const { repository, artifactDirectory } = createTemporaryRepository();
  try {
    const sources = readModelPair();
    writeFileSync(resolve(artifactDirectory, 'real.json'), JSON.stringify(sources[0]));
    writeFileSync(resolve(artifactDirectory, 'run-2.json'), JSON.stringify(sources[1]));
    symlinkSync(resolve(artifactDirectory, 'real.json'), resolve(artifactDirectory, 'run-1.json'));
    const symlink = runCli(repository, [
      '--output',
      'build/k6/read-model/symlink-observations.json',
      'build/k6/read-model/run-1.json',
      'build/k6/read-model/run-2.json',
    ]);
    assert.notEqual(symlink.status, 0);
    assert.match(symlink.stderr, /regular non-symbolic-link/);

    const traversal = runCli(repository, [
      '--output',
      'build/k6/read-model/../outside-observations.json',
      'build/k6/read-model/real.json',
      'build/k6/read-model/run-2.json',
    ]);
    assert.notEqual(traversal.status, 0);
    assert.match(traversal.stderr, /output path/);

    writeFileSync(resolve(artifactDirectory, 'huge.json'), 'x'.repeat((1024 * 1024) + 1));
    const huge = runCli(repository, [
      '--output',
      'build/k6/read-model/huge-observations.json',
      'build/k6/read-model/huge.json',
      'build/k6/read-model/run-2.json',
    ]);
    assert.notEqual(huge.status, 0);
    assert.match(huge.stderr, /too large/);
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

test('MySQL capture SQL keeps legacy cumulative mode and adds isolated event evidence', () => {
  const sql = readFileSync(resolve(testDir, '../../mysql/capture-statement-digests.sql'), 'utf8');
  assert.match(sql, /events_statements_summary_by_digest/);
  assert.match(sql, /events_statements_history_long/);
  assert.match(sql, /@airbob_evidence_window_id/);
  assert.match(sql, /@airbob_evidence_target_id/);
  assert.match(sql, /@airbob_evidence_timer_start/);
  assert.match(sql, /@airbob_evidence_timer_end/);
  assert.match(sql, /'eventId'/);
  assert.doesNotMatch(sql, /SQL_TEXT/);
});

test('optimizer-state SQL captures fixed server, table, index, and safe histogram records', () => {
  const sql = readFileSync(resolve(testDir, '../../mysql/capture-optimizer-state.sql'), 'utf8');
  for (const recordType of ['server', 'table-stat', 'index-stat', 'index-definition', 'histogram']) {
    assert.match(sql, new RegExp(`'recordType', '${recordType}'`));
  }
  assert.match(sql, /innodb_stats_auto_recalc/);
  assert.match(sql, /innodb_table_stats/);
  assert.match(sql, /innodb_index_stats/);
  assert.match(sql, /COLUMN_STATISTICS/);
  assert.doesNotMatch(sql, /payment_key|order_id|virtual_account|client_ip|review\.content/i);
});

function createExplainHarnessRepository() {
  const repository = mkdtempSync(resolve(tmpdir(), 'read-model-explain-test.'));
  const mysqlDirectory = resolve(repository, 'load-test/mysql');
  const artifactDirectory = resolve(repository, 'build/k6/read-model');
  const binaryDirectory = resolve(repository, 'bin');
  mkdirSync(mysqlDirectory, { recursive: true });
  mkdirSync(artifactDirectory, { recursive: true });
  mkdirSync(binaryDirectory, { recursive: true });
  copyFileSync(
    resolve(testDir, '../../mysql/capture-explain-analyze.sh'),
    resolve(mysqlDirectory, 'capture-explain-analyze.sh'),
  );
  chmodSync(resolve(mysqlDirectory, 'capture-explain-analyze.sh'), 0o755);
  const fakeMysql = `#!/usr/bin/env bash
set -eu
query=''
while [[ $# -gt 0 ]]; do
  if [[ "$1" == '--execute' ]]; then
    query="$2"
    break
  fi
  shift
done
case "$query" in
  *"COUNT(*) FROM ("*) printf '%s\\n' "\${FAKE_INVISIBLE_COUNT:-0}" ;;
  *"SELECT DISTINCT INDEX_NAME"*) printf '%s\\n' "\${FAKE_CANDIDATE_INDEX:-}" ;;
  *"SELECT VERSION()"*) printf '%s\\n' '8.0.42' ;;
  *"SELECT @@SESSION.optimizer_switch"*)
    if [[ "$query" == *"use_invisible_indexes=on"* ]]; then
      printf '%s\\n' 'index_merge=on,use_invisible_indexes=on'
    else
      printf '%s\\n' 'index_merge=on,use_invisible_indexes=off'
    fi
    ;;
  *"EXPLAIN FORMAT=JSON"*)
    if [[ "\${FAKE_CHOSEN:-0}" == '1' ]]; then
      printf '{"query_block":{"table":{"possible_keys":["%s"],"key":"%s"}}}\\n' \
        "$FAKE_CANDIDATE_INDEX" "$FAKE_CANDIDATE_INDEX"
    else
      printf '{"query_block":{"table":{"possible_keys":["%s"],"key":"PRIMARY"}}}\\n' \
        "\${FAKE_CANDIDATE_INDEX:-idx_unused}"
    fi
    ;;
  *"EXPLAIN ANALYZE FORMAT=TREE"*)
    printf '%s\\n' '-> Table scan (actual time=1.5e-2..2.5E+1 rows=1.20K loops=1)'
    ;;
  *) printf 'unexpected fake mysql query: %s\\n' "$query" >&2; exit 90 ;;
esac
`;
  writeFileSync(resolve(binaryDirectory, 'mysql'), fakeMysql);
  chmodSync(resolve(binaryDirectory, 'mysql'), 0o755);
  return { repository, mysqlDirectory, artifactDirectory, binaryDirectory };
}

function runExplainHarness(repository, binaryDirectory, args, environment = {}) {
  return spawnSync(
    'bash',
    [resolve(repository, 'load-test/mysql/capture-explain-analyze.sh'), ...args],
    {
      cwd: repository,
      encoding: 'utf8',
      env: { ...process.env, ...environment, PATH: `${binaryDirectory}:${process.env.PATH}` },
    },
  );
}

test('EXPLAIN harness preserves raw output and records a chosen invisible candidate', () => {
  const { repository, artifactDirectory, binaryDirectory } = createExplainHarnessRepository();
  try {
    const query = 'build/k6/read-model/review-hot-query.sql';
    writeFileSync(resolve(repository, query), "SELECT COUNT(*) FROM review WHERE accommodation_id = 42 AND status = 'PUBLISHED'\n");
    const output = 'build/k6/read-model/review-hot-mysql-evidence.json';
    const candidate = 'idx_review_status_accommodation';
    const result = runExplainHarness(repository, binaryDirectory, [
      '--login-path', 'airbob-benchmark',
      '--clone-id', 'clone-a',
      '--target-id', 'review-hot',
      '--window-id', 'window-01',
      '--treatment', 'index-candidate',
      '--candidate-index', candidate,
      '--sql-file', query,
      '--output', output,
    ], {
      FAKE_INVISIBLE_COUNT: '1',
      FAKE_CANDIDATE_INDEX: candidate,
      FAKE_CHOSEN: '1',
    });
    assert.equal(result.status, 0, result.stderr);
    const artifact = JSON.parse(readFileSync(resolve(repository, output), 'utf8'));
    assert.equal(artifact.schema_version, 'mysql-explain-evidence-v1');
    assert.equal(artifact.metadata.candidate_visible, false);
    assert.equal(artifact.metadata.optimizer_switch_use_invisible_indexes, true);
    assert.equal(artifact.explain.candidate_in_chosen_plan, true);
    assert.match(artifact.explain.json_raw, /idx_review_status_accommodation/);
    assert.match(artifact.explain.tree_raw, /1\.20K/);
    assert.equal(lstatSync(resolve(repository, output)).mode & 0o777, 0o600);
    assert.equal(lstatSync(artifactDirectory).isSymbolicLink(), false);
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

test('EXPLAIN harness preserves a not-chosen candidate without claiming improvement', () => {
  const { repository, binaryDirectory } = createExplainHarnessRepository();
  try {
    const query = 'build/k6/read-model/review-cold-query.sql';
    writeFileSync(resolve(repository, query), "SELECT COUNT(*) FROM review WHERE accommodation_id = 7 AND status = 'PUBLISHED'\n");
    const output = 'build/k6/read-model/review-cold-mysql-evidence.json';
    const candidate = 'idx_review_status_accommodation';
    const result = runExplainHarness(repository, binaryDirectory, [
      '--login-path', 'airbob-benchmark',
      '--clone-id', 'clone-a',
      '--target-id', 'review-cold',
      '--window-id', 'window-02',
      '--treatment', 'index-candidate',
      '--candidate-index', candidate,
      '--sql-file', query,
      '--output', output,
    ], {
      FAKE_INVISIBLE_COUNT: '1',
      FAKE_CANDIDATE_INDEX: candidate,
      FAKE_CHOSEN: '0',
    });
    assert.equal(result.status, 0, result.stderr);
    const artifact = JSON.parse(readFileSync(resolve(repository, output), 'utf8'));
    assert.equal(artifact.explain.candidate_in_chosen_plan, false);
    assert.match(artifact.explain.json_raw, /possible_keys/);
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

test('EXPLAIN harness rejects PII-shaped SQL and more than one invisible candidate', () => {
  const { repository, binaryDirectory } = createExplainHarnessRepository();
  try {
    const piiQuery = 'build/k6/read-model/pii-query.sql';
    writeFileSync(resolve(repository, piiQuery), "SELECT id FROM member WHERE email = 'person@example.com'\n");
    const pii = runExplainHarness(repository, binaryDirectory, [
      '--login-path', 'airbob-benchmark',
      '--clone-id', 'clone-a',
      '--target-id', 'review-hot',
      '--window-id', 'window-01',
      '--treatment', 'read-model',
      '--candidate-index', 'none',
      '--sql-file', piiQuery,
      '--output', 'build/k6/read-model/pii-mysql-evidence.json',
    ]);
    assert.notEqual(pii.status, 0);
    assert.match(pii.stderr, /sensitive|PII/);

    const safeQuery = 'build/k6/read-model/safe-query.sql';
    writeFileSync(resolve(repository, safeQuery), 'SELECT COUNT(*) FROM review WHERE accommodation_id = 42\n');
    const multiple = runExplainHarness(repository, binaryDirectory, [
      '--login-path', 'airbob-benchmark',
      '--clone-id', 'clone-a',
      '--target-id', 'review-hot',
      '--window-id', 'window-01',
      '--treatment', 'index-baseline',
      '--candidate-index', 'idx_review_status_accommodation',
      '--sql-file', safeQuery,
      '--output', 'build/k6/read-model/multiple-mysql-evidence.json',
    ], {
      FAKE_INVISIBLE_COUNT: '2',
      FAKE_CANDIDATE_INDEX: 'idx_review_status_accommodation',
    });
    assert.notEqual(multiple.status, 0);
    assert.match(multiple.stderr, /exactly one invisible candidate/);
  } finally {
    rmSync(repository, { recursive: true, force: true });
  }
});

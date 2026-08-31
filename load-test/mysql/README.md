# MySQL benchmark evidence capture

These tools collect read-only optimizer evidence. They do not create indexes,
run `ANALYZE TABLE`, mutate benchmark data, or contribute latency to a k6
headline. Capture and measurement must use one isolated restored clone whose
database fingerprint does not change.

## Statement evidence

[`capture-statement-digests.sql`](capture-statement-digests.sql) has two
backward-compatible modes.

- With no `@airbob_evidence_window_id`, it emits the cumulative
  `events_statements_summary_by_digest` rows consumed by the existing AWS
  traffic harness.
- With all read-model variables set, it emits individual
  `events_statements_history_long` rows inside one closed timer window. Every
  row carries the public target, window, Performance Schema thread, and event
  ID. It retains normalized `DIGEST_TEXT`, never raw statement text.

Set these variables on the same mysql client session that sources the file:

```sql
SET @airbob_evidence_window_id = 'review-hot-after-r1';
SET @airbob_evidence_target_id = 'review-hot';
SET @airbob_evidence_timer_start = 1000000000;
SET @airbob_evidence_timer_end = 2000000000;
SET @airbob_evidence_thread_id = 41;
SET @airbob_evidence_event_floor = 200;
SOURCE load-test/mysql/capture-statement-digests.sql;
```

Use `mysql --batch --raw --skip-column-names`. Treat an empty event result,
history eviction, an error event, or a request-to-event count mismatch as an
invalid run. Hot and cold literals can normalize to the same digest. Never
merge them by digest alone: `(targetId, windowId, threadId, eventId)` is the
attribution identity.

The legacy AWS traffic runner still captures four cumulative snapshots without
truncating Performance Schema: idle before/after and measurement before/after.
Its aggregator rejects eviction, reset, digest-text drift, ambient SQL, and
request-to-call mismatches.

## Statistics and histograms

Run `ANALYZE TABLE` once, after the restored load and after creating the one
invisible candidate (when applicable), but before warm-up or measurement. Save
the command receipt separately and hash it. Then capture the fixed optimizer
snapshot:

```bash
snapshot_id=review-hot-baseline-r1
mysql --login-path=airbob-benchmark \
  --database=airbobdb \
  --batch --raw --skip-column-names \
  --init-command="SET @airbob_optimizer_snapshot_id='${snapshot_id}'" \
  < load-test/mysql/capture-optimizer-state.sql \
  > build/k6/read-model/review-hot-optimizer-state.jsonl
```

[`capture-optimizer-state.sql`](capture-optimizer-state.sql) emits exact JSONL
records for the MySQL patch and optimizer variables, persistent table/index
statistics, index definitions and an allowlisted histogram set. The histogram
allowlist excludes member identity, free text, payment keys, account details,
and network coordinates. Hash the bytes into all observations for the block.
Any change to the statistics or histogram hash, `last_update`, database
fingerprint, or `innodb_stats_auto_recalc` state invalidates comparison.

No `ANALYZE TABLE`, histogram update, setup request, login, or EXPLAIN command
may execute inside the headline measurement interval.

## Raw EXPLAIN evidence

Write one bounded, read-only `SELECT` to either
`build/k6/read-model/*-query.sql` or `load-test/mysql/queries/*.sql`. The query
must use already selected manifest parameters and must not select or predicate
on credentials or PII-bearing fields. Configure credentials with
`mysql_config_editor`; `MYSQL_PWD` is rejected.

Read-model evidence has no index candidate and requires a clone with no
invisible index:

```bash
load-test/mysql/capture-explain-analyze.sh \
  --login-path airbob-benchmark \
  --clone-id restored-release-clone-a \
  --target-id review-hot \
  --window-id review-hot-after-r1 \
  --treatment read-model \
  --candidate-index none \
  --sql-file build/k6/read-model/review-hot-query.sql \
  --output build/k6/read-model/review-hot-mysql-evidence.json
```

An index experiment permits exactly one physical invisible index in the clone.
Capture the baseline with `use_invisible_indexes=off`, then the candidate with
it enabled:

```bash
load-test/mysql/capture-explain-analyze.sh \
  --login-path airbob-benchmark \
  --clone-id restored-release-clone-a \
  --target-id review-hot \
  --window-id review-hot-index-candidate-r1 \
  --treatment index-candidate \
  --candidate-index idx_review_candidate \
  --sql-file build/k6/read-model/review-hot-query.sql \
  --output build/k6/read-model/review-hot-index-mysql-evidence.json
```

Use `index-baseline` for the switch-off half. The script verifies the clone's
invisible-index inventory, records the exact MySQL patch and optimizer switch,
and preserves raw `EXPLAIN FORMAT=JSON` and `EXPLAIN ANALYZE FORMAT=TREE`
strings. It determines `candidate_in_chosen_plan` only from structured chosen
index fields; a name appearing under `possible_keys` is not sufficient. A
not-chosen candidate remains useful raw evidence, but it cannot produce an
improvement headline.

Inputs are bounded, symlinks and traversal are rejected, output is mode `0600`
and published atomically without overwrite. The output intentionally omits the
login path and raw SQL.

## Aggregation contract

The runner assembles each measurement into exact
`read-model-evidence-v1` JSON and
[`aggregate-read-model-observations.mjs`](../k6/read-model/aggregate-read-model-observations.mjs)
produces `read-model-observations-v1`.

Each source binds:

- the immutable release tuple and typed manifest target (parameters are stored
  by SHA-256, and accounts by non-PII reference);
- app commit, image digest, build ID and one-instance topology;
- one clone ID, equal pre/post database fingerprints, optimizer/statistics/
  histogram snapshots and the one-time ANALYZE receipt;
- a complete before/after, A/A, or invisible-index pair;
- error-free, drop-free measure-only latency with setup, login, ANALYZE and
  EXPLAIN explicitly excluded;
- response parity, a window/event-bound statement delta, raw EXPLAIN evidence,
  and the structured chosen-plan claim.

The aggregator rejects unknown fields, secrets/PII, invalid runs, incomplete
pairs, target or release drift, fingerprint drift, mixed clones, mixed
candidates, and statistics/histogram drift. It accepts exponent and SI
engineering notation in TREE iterator metrics. For an invisible candidate not
present in the chosen plan, it preserves the raw observations in an invalid
eligibility artifact and sets `headline` to `null`.

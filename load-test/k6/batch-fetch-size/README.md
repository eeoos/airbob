# Hibernate batch-fetch-size benchmark

This harness measures Airbob's deterministic core GET APIs against the retained
`nplus1-v1` fixture. It is intentionally local-only and does not require
Prometheus or Grafana: each round scrapes the application's Prometheus endpoint
before and after k6, then calculates exact per-request query-count deltas.

The production candidates are `100`, `50`, and `20`. `0` is a diagnostic OFF
control only. Every candidate must use a fresh JVM and the same fixture, load,
warm-up, and duration.

```bash
BATCH_SIZE=100 BATCH_REPEAT_INDEX=1 \
  bash load-test/k6/batch-fetch-size/evaluate.sh
```

Useful local validation overrides:

```bash
BATCH_SIZE=100 \
BATCH_WARMUP_DURATION=5s \
BATCH_MEASURE_DURATION=5s \
  bash load-test/k6/batch-fetch-size/evaluate.sh
```

`evaluate.sh` uses a dedicated host JVM, a uniquely named ephemeral Redis, and
a temporary SELECT-only MySQL user. Elasticsearch repository index creation is
skipped because none of the measured APIs use Elasticsearch; its health check
is disabled as well. When the fixture password is unavailable,
it temporarily replaces only the isolated benchmark account's BCrypt hash. A
mode-0600 recovery marker is written first, the exact hash is restored in a
trap, and a deterministic data-only database hash is verified after cleanup.

The application declares scheduling directly, so it cannot be disabled by a
Spring Boot task property. The SELECT-only connection blocks scheduler writes,
the startup attempt finishes before warm-up, and the measurement JVM has a
270-second hard deadline so the five-minute fixed-rate task cannot run again.

The seven measured endpoints already use fetch joins, projections, or explicit
bulk queries. Equal results across candidates therefore mean that the core API
set does not depend on the global Hibernate batch-fetch safety net; they do not
prove a project-wide optimum.

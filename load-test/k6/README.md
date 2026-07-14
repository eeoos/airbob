# N+1 benchmark fixture smoke test

This smoke test verifies the benchmark fixture and the recently viewed endpoint against a matching Airbob database.

## Prerequisites and safety

- The Airbob database must already contain the dump that matches `BENCHMARK_MANIFEST`.
- Backend MySQL, Redis, and the Airbob application must all be healthy before the test starts.
- Setup mutates only the benchmark account's recently viewed Redis key.
- Do not run this test concurrently with the same benchmark account. Concurrent runs would reset and repopulate the same Redis key.
- Enter `TEST_PASSWORD` through the hidden shell prompt below. Do not place the password in the command, manifest, or repository.

Setup traffic is part of fixture validation, so setup `http_req_duration` is not a resume metric. For this isolated smoke, use `benchmark_recently_viewed_duration{phase:measure}`. Use the follow-up controlled scenarios—not this smoke-only Trend—for real p95 comparisons.

Setup deliberately does not call the recently viewed collection GET. Therefore, Micrometer/Grafana query-count and response-time samples for `GET /api/v1/members/recently-viewed` come only from the single measured request in each isolated smoke run. k6 `phase` tags are client-side metric tags and are not transmitted to the server.

## Local smoke

```bash
read -rsp 'Benchmark password: ' TEST_PASSWORD
echo
export TEST_PASSWORD

BASE_URL=http://localhost:8080 \
BENCHMARK_MANIFEST=/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json \
DATASET_SIZE=20 \
k6 run load-test/k6/nplus1-fixture-smoke.js

unset TEST_PASSWORD
```

## OCI URL substitution

Only after the matching dump has been separately approved and imported, substitute the approved API URL while keeping the same manifest and test contract:

```bash
BASE_URL=https://api.airbob.cloud \
BENCHMARK_MANIFEST=/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json \
DATASET_SIZE=20 \
k6 run load-test/k6/nplus1-fixture-smoke.js
```

This runbook does not authorize or provide SSH, database import, or production load commands.

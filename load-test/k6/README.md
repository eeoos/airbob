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

### Accepted local database provenance

The online gate may use either the retained Task 9 database together with the manifest emitted by that exact run, or a freshly regenerated deterministic ETL database/manifest pair when the retained database credentials were deliberately discarded. Never rerun a command against the retained database merely to recover or guess discarded credentials.

A regenerated pair is acceptable only when all of the following safeguards are enforced:

- use the public ETL CLI and the same `nplus1-v1` manifest contract and dataset parameters;
- use isolated, non-production ports, containers, and temporary artifact paths;
- generate credentials without logging them and remove them during cleanup;
- run the application with a read-only datasource while Flyway only validates the already migrated schema;
- prove a data-only database hash is identical before and after the full smoke matrix;
- clean up every gate-owned resource; and
- prove the retained Task 9 database/artifacts and the user's existing application process are unchanged.

The database and `BENCHMARK_MANIFEST` must always come from the same ETL run under either route.

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

## Recently viewed N+1 before/after comparison

`recently-viewed-nplus1-performance.js` compares the address lazy-loading N+1 baseline with the address fetch-join implementation.

- `VARIANT=before` calls `/api/v2/members/recently-viewed`.
- `VARIANT=after` calls `/api/v1/members/recently-viewed`.
- `BENCHMARK_MANIFEST` supplies the exact accommodation IDs and the script resets the benchmark account's Redis recently viewed key before each variant.
- Run both variants against the same application, dataset, request rate, and duration.
- The script validates every response row count and fails when requests are dropped or unsuccessful.
- With `N=100`, the before endpoint executes `N+3` SELECTs (accommodations 1 + addresses N + review summaries 1 + wishlists 1), while the after endpoint executes 3.
- Start the local application with the measurement-only profile below. It enables the before endpoint and disables Hibernate batch fetching so that the address N+1 is not masked.

```bash
SPRING_PROFILES_ACTIVE=dev,nplus1-benchmark ./gradlew bootRun
```

```bash
read -rsp 'Benchmark password: ' TEST_PASSWORD
echo
export TEST_PASSWORD
mkdir -p build/k6

BASE_URL=http://localhost:8080 \
BENCHMARK_MANIFEST=/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json \
VARIANT=before \
EXPECTED_ROWS=100 \
RATE=2 \
WARMUP_DURATION=30s \
MEASURE_DURATION=1m \
K6_RESULT_PATH=build/k6/recently-viewed-before.json \
k6 run load-test/k6/recently-viewed-nplus1-performance.js

BASE_URL=http://localhost:8080 \
BENCHMARK_MANIFEST=/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json \
VARIANT=after \
EXPECTED_ROWS=100 \
RATE=2 \
WARMUP_DURATION=30s \
MEASURE_DURATION=1m \
K6_RESULT_PATH=build/k6/recently-viewed-after.json \
k6 run load-test/k6/recently-viewed-nplus1-performance.js

unset TEST_PASSWORD
```

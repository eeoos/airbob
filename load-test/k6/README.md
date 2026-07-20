# Airbob k6 벤치마크

이 디렉터리에는 서로 독립적인 세 가지 성능 실험이 함께 있다. 먼저 측정 목적을 고른 뒤 해당 진입점과 README만 보면 된다.

| 측정 목적 | 직접 실행할 진입점 | 상세 가이드 |
|---|---|---|
| 반정규화 before/after | `read-model/*-comparison.js` 3개 | [read-model/README.md](read-model/README.md) |
| 최근 본 숙소 N+1 before/after | `nplus1-fixture-smoke.js`, `recently-viewed-nplus1-performance.js` | 이 문서의 N+1 절 |
| 쿠폰 Redisson(before)/Lua(after) 발급 | `coupon-issuance-comparison.js` | [상위 load-test README](../README.md) |

직접 실행하지 않는 파일도 있다.

- `lib/`: 로그인, fixture 준비, 응답 검증, 결과 요약처럼 여러 진입점이 호출하는 코드
- `test/`: `lib/`의 파싱과 응답 계약을 검증하는 짧은 k6 테스트. 실제 API 부하 테스트가 아니다.

read-model 스크립트는 모두 같은 순서로 동작한다.

1. `options`가 요청률, warm-up, 측정 시간과 실패 기준을 정한다.
2. `setup()`이 로그인하고 before/after 응답이 같은지 한 번 검증한다.
3. `warmup()`이 선택한 variant만 예열한다.
4. `measure()`가 같은 variant에 고정 RPS를 보낸다.
5. `handleSummary()`가 p50/p95/p99와 성공률을 JSON으로 저장한다.

## N+1 benchmark fixture smoke test

This smoke test verifies the benchmark fixture and the recently viewed endpoint against a matching Airbob database.

## Prerequisites and safety

- The Airbob database must already contain the dump that matches `BENCHMARK_MANIFEST`.
- Backend MySQL, Redis, and the Airbob application must all be healthy before the test starts.
- Start the application with `dev,nplus1-benchmark`; the fixture API does not exist in normal local or production profiles.
- On AWS, use an isolated benchmark instance or target group that receives no normal traffic. The `nplus1-benchmark` profile disables Hibernate batch fetching for the entire JVM and must not be enabled on a serving production instance.
- Set `BENCHMARK_READ_MODEL_TOKEN` on both the application and k6. The fixture and v2 before endpoints reject requests without the matching `X-Benchmark-Token`.
- Setup mutates only the benchmark account's recently viewed Redis key.
- Do not run this test concurrently with the same benchmark account. Concurrent runs would reset and repopulate the same Redis key.
- Enter `TEST_PASSWORD` through the hidden shell prompt below. Do not place the password in the command, manifest, or repository.

Setup replaces the recently viewed ZSET through one authenticated fixture PUT. Setup `http_req_duration` is not a resume metric. For this isolated smoke, use `benchmark_recently_viewed_duration{phase:measure}`. Use the follow-up controlled scenarios—not this smoke-only Trend—for real p95 comparisons.

Setup deliberately does not call the recently viewed collection GET. Therefore, Micrometer/Grafana query-count and response-time samples for `GET /api/v1/members/recently-viewed` come only from the single measured request in each isolated smoke run. k6 `phase` tags are client-side metric tags and are not transmitted to the server.
The fixture PUT appears under its own server path; exclude that setup path when comparing the before/after GET query count and latency.

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

Start the application in a separate terminal:

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev,nplus1-benchmark ./gradlew bootRun
```

```bash
read -rsp 'Benchmark password: ' TEST_PASSWORD
echo
export TEST_PASSWORD
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN

BASE_URL=http://localhost:8080 \
BENCHMARK_MANIFEST=/Users/jaehoonchoi/study/CodeSquad/etl/etl/build/benchmark-fixture.json \
DATASET_SIZE=20 \
k6 run load-test/k6/nplus1-fixture-smoke.js

unset TEST_PASSWORD BENCHMARK_READ_MODEL_TOKEN
```

## Recently viewed N+1 before/after comparison

`recently-viewed-nplus1-performance.js` compares the address lazy-loading N+1 baseline with the address fetch-join implementation.

- `VARIANT=before` calls `/api/v2/members/recently-viewed`.
- `VARIANT=after` calls `/api/v1/members/recently-viewed`.
- `BENCHMARK_MANIFEST` supplies the exact accommodation IDs and the script resets the benchmark account's Redis recently viewed key before each variant.
- The fixture PUT treats the manifest ID order as latest-first and preserves it in the following GET response.
- Run both variants against the same application, dataset, request rate, and duration.
- The script validates every response row count and fails when requests are dropped or unsuccessful.
- With `N=100`, the before endpoint executes `N+3` SELECTs (accommodations 1 + addresses N + review summaries 1 + wishlists 1), while the after endpoint executes 3.
- Use the same measurement-only profile from the local smoke section. It enables the before and fixture endpoints and disables Hibernate batch fetching so that the address N+1 is not masked.

```bash
read -rsp 'Benchmark password: ' TEST_PASSWORD
echo
export TEST_PASSWORD
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN
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

unset TEST_PASSWORD BENCHMARK_READ_MODEL_TOKEN
```

The token entered in the k6 terminal must be the same value used to start the application. The after GET itself does not require the token, but this script resets the deterministic v2 fixture before both variants, so the token is required for both runs.

The three denormalized read-model comparisons have separate scripts and a Korean execution guide at [read-model/README.md](read-model/README.md).

## N+1 측정 후 서버 teardown

앞선 `unset TEST_PASSWORD BENCHMARK_READ_MODEL_TOKEN`은 k6 클라이언트 셸의 자격 증명만 지운다. JVM에 활성화된 `nplus1-benchmark` 프로필과 서버 측 토큰은 별도로 제거해야 한다.

### 로컬

benchmark JVM을 먼저 `Ctrl-C`로 중지한다. 애플리케이션 실행 셸에서 `nplus1-benchmark`와 서버 측 `BENCHMARK_READ_MODEL_TOKEN` 바인딩을 제거하고 정상 프로필로 다시 시작한다.

```bash
# nplus1-benchmark JVM을 중지한 뒤 애플리케이션 실행 셸에서 실행
unset BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

### AWS

격리된 benchmark JVM/인스턴스를 중지하거나 target group에서 drain한다. 배포 설정에서 `nplus1-benchmark`를 제거하고 token secret 및 서버의 `BENCHMARK_READ_MODEL_TOKEN` 바인딩도 제거한 뒤 정상 프로필로 다시 배포한다. 이 단계가 Hibernate batch fetching을 정상 설정으로 되돌린다.

재배포 후 유효한 일반 회원 세션으로 대표 v2 경로가 `404`인지 확인한다. `X-Benchmark-Token`은 의도적으로 생략하므로 프로필이 남아 있으면 `403`으로 구분되고, `401`이면 세션부터 갱신해야 한다.

```bash
curl -i \
  -b "SESSION_ID=${VERIFY_SESSION_ID}" \
  "${BASE_URL}/api/v2/members/recently-viewed"
# 기대 결과: HTTP/1.1 404
```

서버 teardown을 확인한 뒤, 필요하면 클라이언트 셸 자격 증명도 다시 정리한다.

```bash
unset TEST_PASSWORD BENCHMARK_READ_MODEL_TOKEN
```

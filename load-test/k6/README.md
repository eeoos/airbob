# Airbob k6 벤치마크

이 디렉터리에는 서로 독립적인 성능 실험이 함께 있다. 먼저 측정 목적을 고른 뒤 해당 진입점과 README만 보면 된다.

| 측정 목적 | 직접 실행할 진입점 | 상세 가이드 |
|---|---|---|
| 위시리스트·매출 반정규화 before/after | `read-model/wishlist-comparison.js`, `read-model/revenue-stats-comparison.js` | [read-model/README.md](read-model/README.md) |
| 숙소 응답의 리뷰 요약 반정규화 | [HTTP 요청 모음](../http/denormalization-discovery.http) | [리뷰 요약 비교 가이드](../../docs/performance/review-summary-denormalization.md) |
| 최근 본 숙소 N+1 before/after | `nplus1-fixture-smoke.js`, `recently-viewed-nplus1-performance.js` | 이 문서의 N+1 절 |
| 쿠폰 DB 조건부 UPDATE/Lua 발급 | `coupon/run_experiments.py`, `coupon/coupon-issuance-comparison.js` | [쿠폰 실험 가이드](coupon/README.md) |
| 숙소 상세 Redis 캐시 V2(before)/V1(after) | `cache/run-local-comparison.py`, `cache/accommodation-detail-comparison.js` | [cache/README.md](cache/README.md) |

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
- Keep `nplus1-benchmark` last in the profile list. It disables SQL output as well as batch fetching; query-count metrics remain enabled independently of logging.
- On AWS, use an isolated benchmark instance or target group that receives no normal traffic. The `nplus1-benchmark` profile disables Hibernate batch fetching for the entire JVM and must not be enabled on a serving production instance.
- Set `BENCHMARK_READ_MODEL_TOKEN` on both the application and k6. The fixture and v2 before endpoints reject requests without the matching `X-Benchmark-Token`.
- Setup mutates only the benchmark account's recently viewed Redis key.
- Fixture IDs must identify existing `PUBLISHED` accommodations, each with a different existing address. An invalid fixture returns HTTP 400 (`B002`) before changing Redis.
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

`recently-viewed-nplus1-performance.js`는 주소 지연 로딩 기준선과 현재 DTO 프로젝션 구현을 비교한다.
after에는 주소 N+1 제거, 리뷰 요약 JOIN, 조회 컬럼 축소, 엔티티 로딩 제거 효과가 함께 포함된다.
기본 프로필의 batch fetch 100을 측정 프로필에서 0으로 바꾸므로, 결과에 이 재현 조건을 함께 기록한다.

| variant | GET 경로 | SELECT 구성 |
|---|---|---|
| before | `/api/v2/members/recently-viewed` | 숙소 1 + 주소 N + 리뷰 요약 1 + 찜 1 = N+3 |
| after | `/api/v1/members/recently-viewed` | 숙소·주소·리뷰 요약 projection 1 + 찜 1 = 2 |

| 최근 본 숙소 N | before | after |
|---:|---:|---:|
| 1 | 4 | 2 |
| 20 | 23 | 2 |
| 50 | 53 | 2 |
| 100 | 103 | 2 |

최근 본 기록을 수동으로 클릭해서 만들 필요는 없다. 각 k6 실행은 다음 순서로 준비하고 측정한다.

1. `BENCHMARK_MANIFEST`의 전용 계정으로 로그인한다.
2. manifest의 `recentlyViewed.accommodationIds`에서 `EXPECTED_ROWS`개를 선택한다.
3. 인증된 fixture PUT이 MySQL에서 모든 숙소의 공개 상태와 서로 다른 주소를 확인한다. DB 데이터는 변경하지 않는다.
4. 검증 후 해당 회원의 `recently_viewed:{memberId}` ZSET을 전체 교체한다. 첫 ID가 가장 최근이고 점수는 1ms 간격이며 TTL은 7일이다.
5. 같은 기록에 before와 after GET을 각각 한 번 호출한다. ID·순서·건수·조회 시각을 확인하고 주소·리뷰·찜을 포함한 전체 응답 데이터가 같은지 검사한다. 불일치하면 워밍업 전에 실행을 중단한다.
6. 선택한 variant만 워밍업하고, 종료 대기와 안정화 시간 후 고정 RPS로 측정한다. 매 응답의 ID·최근 순서·건수와 HTTP 상태를 확인한다.

**MySQL 숙소 데이터와 로그인 계정 자체는 미리 준비되어 있어야 한다.** fixture PUT은 기존 ETL 데이터로 Redis 조회 기록을 만드는 API다.
ETL fixture를 사용할 때 DB dump와 `nplus1-v1` manifest는 같은 ETL 실행에서 나온 쌍을 사용한다. 주소가 없거나 공유된 경우, 미공개·삭제·없는 숙소가 포함된 경우에는 `B002`가 발생한다.
`load-test/k6/test/fixtures/nplus1-v1.json`은 오프라인 계약 테스트용이므로 실제 DB용 manifest로 사용하지 않는다.

기존 로컬/AWS DB를 그대로 측정할 때는 이 비교 스크립트에 한해 `recently-viewed-v1` 입력도 지원한다.
현재 DB의 전용 테스트 계정과 실제로 조회해 확인한 숙소 ID로 아래 파일을 준비하고 `BENCHMARK_MANIFEST`에 지정한다.
`datasetId`에는 측정한 데이터셋 식별자를 기록한다. 비밀번호는 파일에 넣지 않고 `TEST_PASSWORD` 환경변수로 전달한다.
이 입력은 여러 기능을 점검하는 `nplus1-fixture-smoke.js`에서는 사용하지 않는다.

```json
{
  "datasetVersion": "recently-viewed-v1",
  "datasetId": "your-local-dataset-id",
  "account": { "email": "your-local-benchmark@example.test" },
  "recentlyViewed": { "maxRows": 2, "accommodationIds": [251, 252] }
}
```

위 ID와 이메일은 형식 예시이므로 실제 값으로 교체한다. 이 예시는 `EXPECTED_ROWS=2`로 실행한다.
100개를 측정하려면 서로 다른 주소의 공개 숙소 100개를 넣고 `maxRows=100`으로 지정한다.

두 variant는 같은 앱·데이터·RPS·기간으로 순차 실행하고, 같은 계정을 쓰는 다른 테스트나 화면 조작을 함께 실행하지 않는다.
아래 숨김 입력 예시는 bash 기준이다. zsh에서는 `read -rs 'TEST_PASSWORD?Benchmark password: '`처럼 입력한다.

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

N에 따른 증가도 확인하려면 위 두 명령의 `EXPECTED_ROWS`를 1, 20, 50, 100으로 바꿔 실행하고 결과 파일명에도 N을 넣는다.
manifest의 `recentlyViewed.maxRows` 이내만 사용할 수 있다. 안정적인 지연시간 비교에는 측정 시간을 늘리고 before→after와 after→before 순서를 바꿔 반복한다.

결과 JSON의 `latency_ms`는 측정 구간만의 p50/p95/p99 등을 담고, `requests`는 성공률·실제 요청률·누락을 담는다.
`expected_select_queries_per_request`는 코드상 기대값이며 실측 SQL 개수가 아니다. 실제 횟수는 아래 서버 지표나 통합 테스트로 확인한다.

### AWS에서 같은 k6 스크립트 실행

`BASE_URL`만 AWS 테스트 앱 주소로 바꾸면 같은 스크립트를 사용한다.
서버에는 `nplus1-benchmark`를 마지막 프로필로 적용하고, manifest는 해당 AWS DB의 전용 계정과 숙소 ID로 준비한다.
기존 데이터에서 manifest를 추출할 때는 [조회용 SQL](nplus1/manifest.sql)을 사용할 수 있다. 파일의 데이터셋 ID와 계정을 실제 값으로 바꿔 실행하고 단일 JSON 셀을 저장한다.
앱 기동 설정은 AWS를 켜서 실제 측정할 때 맞춘다. 기존 `isolated-read`의 `read-model-benchmark` 프로필은 최근 본 숙소 경로를 차단하므로 그대로 함께 사용하지 않는다.

`TEST_PASSWORD`와 앱과 동일한 `BENCHMARK_READ_MODEL_TOKEN`을 앞의 숨김 입력 방식으로 설정한 뒤 다음처럼 실행한다.
이 명령은 **이미 실행 중인 앱에 요청을 보내는 명령**이며 AWS 생성·배포는 수행하지 않는다.

```bash
BASE_URL="http://<AWS-테스트-앱-주소>:8080" \
BENCHMARK_MANIFEST=/absolute/path/aws-recently-viewed-manifest.json \
EXPECTED_ROWS=100 RATE=2 WARMUP_DURATION=30s MEASURE_DURATION=1m \
ROUNDS=3 RESULT_DIR=build/k6/recently-viewed-aws \
bash load-test/k6/run-recently-viewed-comparison.sh
```

위 URL은 형식 예시다. 접근 가능한 실제 HTTP/HTTPS 주소로 교체한다.
실행 도구는 로그인 → 최근 본 기록 준비 → 응답 동등성 검증 → 워밍업 → 측정을 두 경로에 동일하게 적용한다.
기본은 before→after 1회이고, `ROUNDS=3`이면 before→after / after→before / before→after로 반복한다.
실패 시 바로 중단하고, 각 결과를 `round-1-before.json`, `round-1-after.json` 등의 이름으로 저장한다.
기존 결과 디렉터리를 덮어쓰지 않는다. 로컬에서도 `BASE_URL=http://localhost:8080`으로 같은 실행 도구를 사용할 수 있다.

### SQL 로그 없이 N+1 확인하기

`application-nplus1-benchmark.yaml`은 `show_sql`과 Hibernate SQL/bind 로그를 끈다.
`SqlQueryStatementInspector` → 요청별 카운터 → Micrometer는 로그와 독립적으로 동작하므로 Grafana에서 계속 관찰할 수 있다.

- `Airbob - API Query Count` 대시보드에서 method=`GET`, query type=`SELECT`, path를 위 v2/v1 경로로 선택한다.
- `Average queries per request`에서 N=100일 때 before=103, after=2를 확인한다. 쿼리 수의 p95는 버킷 보간값이므로 정확히 103이나 2가 아닐 수 있다.
- Grafana 없이도 `/actuator/prometheus`의 `app_query_per_request_queries_sum`과 `_count`를 읽을 수 있다. 동일 경로의 요청 전후 `sum 증가량 / count 증가량`이 평균 SELECT 횟수다.
- k6의 `validation`, `warmup`, `measure` 태그는 서버에 전달되지 않는다. 이 비교 스크립트의 사전 GET과 워밍업도 서버 지표에 포함되므로 Grafana 지연시간은 측정 시간대로 제한한다. fixture PUT도 GET과 분리한다. 단일 GET만 보내는 위 smoke 스크립트는 기존 동작을 유지한다.

계측 범위와 PromQL은 [쿼리 수 모니터링 가이드](../../docs/query-count-monitoring.md)를 참고한다.
SQL 문장 자체가 필요하면 별도의 진단 실행에서만 출력을 켜고, 지연시간 측정은 기본 벤치마크 설정으로 다시 실행한다.

### 자동 검증

Docker가 실행 중인 환경에서 실제 MySQL·Redis로 N별 쿼리 수, 응답 동등성, Redis 교체·TTL·회원별 독립성, 부적합한 fixture 거절을 검증한다.
비교 사이에는 영속성 컨텍스트와 SQL 통계를 비워 앞선 조회가 다음 측정을 가리지 않게 한다.

```bash
./gradlew test --tests '*RecentlyViewed*'
K6_NO_USAGE_REPORT=true k6 run --address '' load-test/k6/test/recently-viewed-benchmark-test.js
```

위시리스트·매출 집계 비교는 [read-model/README.md](read-model/README.md), 숙소 응답의 리뷰 요약 비교는 [전용 가이드](../../docs/performance/review-summary-denormalization.md)를 참고한다. 단독 리뷰 요약 API를 호출하던 실험은 종료했다.

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

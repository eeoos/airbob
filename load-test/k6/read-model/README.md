# 반정규화 read model before/after k6 측정

세 비교는 준비 조건이 달라 별도 스크립트로 실행한다.

- 리뷰 통계: 공개 API, 숙소 ID와 실제 리뷰 수 필요
- 위시리스트 대표 이미지: 회원 세션 필요
- 일별 매출 통계: ADMIN 세션과 날짜 범위 필요

각 스크립트에서 `VARIANT=before`는 토큰으로 보호된 v2 원본 집계 API를, `VARIANT=after`는 실제 운영용 v1 반정규화 API를 측정한다. 측정 전 setup에서만 두 API를 한 번씩 호출해 전체 비즈니스 응답이 같은지 확인하고, 실제 warmup/measure 부하는 선택한 variant 하나에만 보낸다.

## 1. 측정 전 조건

애플리케이션은 before API가 활성화되도록 `read-model-benchmark` 프로필과 토큰을 사용해 실행한다.

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev,read-model-benchmark ./gradlew bootRun
```

k6를 실행하는 터미널에도 같은 `BENCHMARK_READ_MODEL_TOKEN`을 입력한다. after를 측정할 때도 setup의 before 동등성 검증에 토큰이 필요하다. 토큰은 결과 JSON에 기록되지 않는다.

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN

export BASE_URL=http://localhost:8080
export DATASET_LABEL=synthetic-read-model-v1
export APP_VERSION="$(git rev-parse --short HEAD)"
export APP_INSTANCE_COUNT=1
export RATE=5
export WARMUP_DURATION=30s
export MEASURE_DURATION=1m
mkdir -p build/k6/read-model
```

같은 스크립트를 AWS에 보낼 때는 코드 변경 없이 API origin만 바꾼다.

```bash
export BASE_URL=https://api.airbob.cloud
```

AWS에서 before(v2)를 호출하려면 배포 애플리케이션에도 `read-model-benchmark` 프로필과 `BENCHMARK_READ_MODEL_TOKEN`이 설정돼 있어야 한다. after(v1) API는 운영 경로지만, 이 k6 스크립트는 setup에서 before/after 동등성을 먼저 검사하므로 after 측정에도 before API가 필요하다.

먼저 [denormalization-discovery.http](../../http/denormalization-discovery.http)로 fixture와 계정이 올바른지 눈으로 확인한다. 부하 테스트의 `EXPECTED_*` 값도 여기서 확인한 값으로 설정한다. k6 setup은 리뷰 수·평균, 위시리스트 ID/개수/대표 이미지/페이지 정보, 매출 날짜별 금액·건수를 before와 after 사이에서 다시 완전 비교한다. 하나라도 다르면 측정을 시작하지 않는다.

고정해야 할 조건은 애플리케이션 빌드·인스턴스 수·DB 데이터·RATE·워밍업·측정 시간이다. 각 실행은 `constant-arrival-rate`를 사용하며 30초 워밍업, 5초 종료 여유, 5초 안정화 뒤 측정한다. 측정 결과에는 avg, p50, p90, p95, p99, max, 성공률, 실제 RPS와 dropped iteration이 저장된다. k6 지표에서는 setup이 제외되지만 Prometheus 요청 카운터에는 variant별 동등성 확인 요청이 1건씩 추가된다.

## 2. 리뷰 통계

`EXPECTED_REVIEW_COUNT`는 해당 숙소의 게시된 리뷰 총수다. after의 `accommodation_review_summary`가 같은 데이터로 백필되어 있어야 한다.

```bash
export REVIEW_ACCOMMODATION_ID=101
export EXPECTED_REVIEW_COUNT=10000

VARIANT=before ROUND=1 RUN_ORDER=1 \
K6_RESULT_PATH=build/k6/read-model/review-before-r1.json \
k6 run load-test/k6/read-model/review-summary-comparison.js

VARIANT=after ROUND=1 RUN_ORDER=2 \
K6_RESULT_PATH=build/k6/read-model/review-after-r1.json \
k6 run load-test/k6/read-model/review-summary-comparison.js
```

검증 계약은 `total_count == EXPECTED_REVIEW_COUNT`와 `0 <= average_rating <= 5`다.

## 3. 위시리스트 대표 숙소 이미지

로그인 계정은 측정용 회원 하나로 고정한다. `EXPECTED_ROWS`는 첫 페이지가 실제로 반환하는 위시리스트 수이며 `PAGE_SIZE`보다 클 수 없다. 대표 이미지 조회 효과를 보기 위해 fixture의 각 위시리스트에 숙소와 이미지가 연결되어 있는 편이 좋다.

```bash
export BENCHMARK_EMAIL=benchmark-nplus1@airbob.cloud
read -rsp 'Benchmark member password: ' TEST_PASSWORD
echo
export TEST_PASSWORD
export PAGE_SIZE=50
export EXPECTED_ROWS=50

VARIANT=before ROUND=1 RUN_ORDER=1 \
K6_RESULT_PATH=build/k6/read-model/wishlist-before-r1.json \
k6 run load-test/k6/read-model/wishlist-comparison.js

VARIANT=after ROUND=1 RUN_ORDER=2 \
K6_RESULT_PATH=build/k6/read-model/wishlist-after-r1.json \
k6 run load-test/k6/read-model/wishlist-comparison.js

unset TEST_PASSWORD
```

스크립트는 로그인으로 받은 `SESSION_ID`를 사용하며 세션 값과 비밀번호를 결과에 기록하지 않는다. 각 행의 `wishlist_item_count`와 `thumbnail_image_url` 타입, `page_info.current_size`도 함께 검증한다.

## 4. 일별 매출 통계

관리자 계정을 별도로 사용한다. `EXPECTED_ROWS`는 날짜 구간의 일수가 아니라 실제 집계 결과가 있는 날짜 수다. after 실행 전 같은 구간을 `/api/v1/admin/stats/revenue/recompute`로 백필해 원본 settlement와 `daily_revenue_stats`를 맞춘다.

```bash
export ADMIN_EMAIL=admin@airbob.cloud
read -rsp 'Admin password: ' ADMIN_PASSWORD
echo
export ADMIN_PASSWORD
export REVENUE_FROM=2026-01-01
export REVENUE_TO=2026-03-31
export EXPECTED_ROWS=90

VARIANT=before ROUND=1 RUN_ORDER=1 \
K6_RESULT_PATH=build/k6/read-model/revenue-before-r1.json \
k6 run load-test/k6/read-model/revenue-stats-comparison.js

VARIANT=after ROUND=1 RUN_ORDER=2 \
K6_RESULT_PATH=build/k6/read-model/revenue-after-r1.json \
k6 run load-test/k6/read-model/revenue-stats-comparison.js

unset ADMIN_PASSWORD
```

before는 응답 `source=raw`, after는 `source=stats`여야 하며 날짜 범위, 결과 행 수와 각 금액·건수 필드를 검증한다.

## 5. 반복 순서와 결과 해석

한 번의 숫자로 결론 내리지 말고 최소 3라운드를 권장한다. 순서 편향을 줄이기 위해 다음처럼 교차한다.

| 라운드 | 첫 실행 | 두 번째 실행 |
|---:|---|---|
| 1 | before (`RUN_ORDER=1`) | after (`RUN_ORDER=2`) |
| 2 | after (`RUN_ORDER=1`) | before (`RUN_ORDER=2`) |
| 3 | before (`RUN_ORDER=1`) | after (`RUN_ORDER=2`) |

비교할 핵심 값은 각 라운드의 p50/p95/p99와 실제 RPS다. `successful != attempted`, `error_rate > 0`, `dropped_iterations > 0`인 실행은 성능 결과로 사용하지 않는다. 절대 지연시간뿐 아니라 다음 서버 지표도 같은 실행 구간에서 함께 본다.

- Grafana `http.server.requests`: v1/v2 URI별 서버 p95와 요청 수
- `app.query.per_request` (`app_query_per_request_sum/count`): 요청당 SELECT 수
- HikariCP active/pending connection
- JVM CPU, GC pause, heap 사용량

k6는 사용자 관점 지연과 부하 무결성의 기준으로, Prometheus/Grafana는 쿼리 수와 서버 자원 사용의 근거로 사용한다. 포트폴리오에는 데이터셋 크기, RATE, 앱 인스턴스 수, 라운드 중앙값을 결과와 함께 적는다.

## 6. 측정 후 애플리케이션 teardown

k6 클라이언트 셸의 토큰을 지우는 것만으로는 before API가 비활성화되지 않는다. 측정이 끝나면 서버에서 benchmark 프로필과 토큰을 제거한다.

### 로컬

`read-model-benchmark` 프로필로 실행한 JVM을 먼저 중지한다. 애플리케이션 실행 셸에서 활성 프로필의 `read-model-benchmark`와 애플리케이션 측 `BENCHMARK_READ_MODEL_TOKEN`을 제거하고, 필요하면 benchmark 프로필 없이 평소 방식으로 다시 시작한다.

```bash
# read-model-benchmark JVM을 Ctrl-C로 중지한 뒤 애플리케이션 실행 셸에서 실행
unset BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

### AWS

격리된 benchmark 인스턴스의 애플리케이션 프로필에서 `read-model-benchmark`를 제거하고, 서버의 token secret과 `BENCHMARK_READ_MODEL_TOKEN` 환경 변수 바인딩도 제거한다. 그 설정으로 인스턴스를 다시 배포한 다음 v2 리뷰 benchmark 경로가 `404`인지 확인한다.

```bash
curl -i \
  -H "X-Benchmark-Token: ${BENCHMARK_READ_MODEL_TOKEN}" \
  "${BASE_URL}/api/v2/accommodations/${REVIEW_ACCOMMODATION_ID}/reviews/summary"
# 기대 결과: HTTP/1.1 404
```

마지막으로 다음 명령은 서버 teardown과 별개로 k6 클라이언트 셸의 토큰만 제거한다.

```bash
unset BENCHMARK_READ_MODEL_TOKEN
```

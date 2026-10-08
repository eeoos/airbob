# 쿠폰 단일 실행 및 운영 전환 가이드

반복 측정·최대 RPS 탐색·DB 지표 자동 수집은 [README](README.md)를 사용한다. 아래 명령은 저장소 루트에서 실행한다.

`coupon-issuance-comparison.js`는 아래 두 동기 API를 같은 부하 모델로 한 번에 하나씩 측정한다.

- `VARIANT=lua`: `POST /api/v1/coupons/{couponId}/issue` — 운영 Lua 경로
- `VARIANT=db`: `POST /api/v2/coupons/{couponId}/issue` — DB 조건부 UPDATE 벤치마크 경로

두 API 모두 MySQL 발급 트랜잭션 커밋 뒤 `201 Created`를 반환한다. 이 테스트는 처리량과 p50/p95/p99뿐 아니라 매진, 중복, 설정 오류를 분리해서 기록한다.

DB 경로는 재고·활성 상태·발급 기간·Redis 준비 이력 조건을 포함한 단일 UPDATE로 발급 수를 증가시키고, 중복 검사와 회원 쿠폰 INSERT를 같은 트랜잭션에서 처리한다. 중복 또는 INSERT 실패 시 수량 증가도 롤백한다. 발급 로직에서 Redis를 호출하지 않으며, 공통 세션 인증에는 기존 Redis 세션을 사용한다. Lua 경로도 성공 요청에서는 MySQL의 같은 쿠폰 행을 증가시키므로 성공 발급과 매진 응답의 처리량·지연을 따로 해석한다.

## 비교 전에 지켜야 할 조건

1. DB와 Lua는 할인·재고·발급 기간이 같은 **서로 다른 쿠폰 ID**로 실행한다.
2. 같은 쿠폰 ID에 두 URL을 섞지 않는다. DB의 `redis_stock_prepared_at`이 있는 Lua 쿠폰은 조건부 UPDATE가 거부한다. DB 발급 이력이 생긴 쿠폰은 Lua 준비가 거부된다.
3. 한 번 실행해 상태가 바뀐 쿠폰을 다음 측정에 재사용하지 않는다. 반복 측정마다 새 쿠폰을 만든다.
4. 워밍업은 측정용 쿠폰의 재고를 소모하므로 반드시 별도의 폐기용 쿠폰으로 실행한다.
5. 한 실행의 각 요청에는 서로 다른 회원의 유효한 `SESSION_ID`를 하나씩 사용한다. 한 회원의 세션 토큰을 여러 개 넣는 것도 중복 요청이므로 허용하지 않는다.
6. 애플리케이션 빌드, 인스턴스 수, DB/Redis 위치, RATE/DURATION, 쿠폰 수량을 두 전략에서 동일하게 유지한다.

측정 DB는 선택한 AWS dataset과 배포 이미지의 schema 계약을 만족해야 한다. 기존 performance-lab dataset은 Flyway target V27을 고정하며, 현재 앱의 기본 schema는 V28이다. V25는 예약이 0행인 상태에서만 적용할 수 있으므로, 빈 DB에 V1~V27을 먼저 적용한 뒤 ETL dataset을 적재한다.

## 애플리케이션 실행 조건

Lua 운영 경로는 일반 운영 프로필에서 그대로 사용한다. DB 조건부 UPDATE 비교 경로까지 같은 인스턴스에서 측정하려면 `coupon-benchmark` 프로필과 서버의 `BENCHMARK_READ_MODEL_TOKEN`을 함께 설정한다.

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
export BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev,coupon-benchmark ./gradlew bootRun
```

AWS에서도 db variant를 호출할 인스턴스에 `coupon-benchmark`와 같은 토큰을 설정해야 한다. 일반 사용자 트래픽과 분리된 벤치마크 인스턴스에서만 이 프로필을 활성화한다.
`SPRING_PROFILES_ACTIVE`는 `bootRun` 명령에만 적용하여 같은 셸의 후속 재시작에 `coupon-benchmark`가 남지 않게 한다. 토큰은 db k6 요청에도 필요하므로 측정 중에는 export 상태를 유지한다.

### 운영 Lua 전환 시작 가드

정상 AWS/OCI 프로필은 준비 이력이 없는데 기존 DB 발급 이력이 남은 활성 쿠폰을 발견하면 ready 상태가 되기 전에 시작을 실패시킨다. `issued_quantity > 0`과 실제 `member_coupon` 행을 모두 확인하며, 애플리케이션은 쿠폰을 비활성화하거나 데이터를 자동 변경하지 않는다. rolling deploy 전에는 구버전 발급 트래픽을 먼저 중지하고 다음 조회 결과가 0건인지 확인한다.

```sql
SELECT c.id, c.issued_quantity, c.redis_stock_prepared_at
FROM coupon c
WHERE c.is_active = TRUE
  AND c.redis_stock_prepared_at IS NULL
  AND (
    c.issued_quantity > 0
    OR EXISTS (
      SELECT 1
      FROM member_coupon mc
      WHERE mc.coupon_id = c.id
    )
  );
```

행이 나오면 배포를 진행하지 않는다. 기존 회원 발급 집합과 남은 재고를 보존하는 검증된 Lua 마이그레이션을 수행하거나, 담당자가 해당 레거시 캠페인을 운영 절차에 따라 명시적으로 해결한 뒤 다시 조회하고 재배포한다. 단순히 가드를 우회하거나 애플리케이션이 쿠폰 상태를 자동 변경하게 하지 않는다.

## 1. 고유 회원 세션 fixture 준비

ETL release의 `coupon-accounts-v1` capsule은 서로 다른 로그인 계정과 capacity를 제공한다. 측정 직전에 이 계정들로 로그인해 mode `0600` 세션 파일을 만든다. 비밀번호는 manifest에 들어가지 않으며, ETL 적재 때 사용한 값과 같은 비밀번호 파일을 전달해야 한다.

```bash
export BASE_URL="https://replace-with-isolated-coupon-lab.example.com"
export BENCHMARK_DATASET_MANIFEST="$(pwd)/build/benchmark-dataset-v1.json"
export SESSION_FIXTURE="$(pwd)/load-test/fixtures/coupon-sessions.json"

umask 077
read -rsp 'Coupon benchmark account password: ' COUPON_ACCOUNT_PASSWORD
printf '%s' "${COUPON_ACCOUNT_PASSWORD}" > build/coupon-account-password
unset COUPON_ACCOUNT_PASSWORD

node load-test/k6/coupon/prepare-coupon-sessions.js \
  --base-url "${BASE_URL}" \
  --manifest "${BENCHMARK_DATASET_MANIFEST}" \
  --password-file "$(pwd)/build/coupon-account-password" \
  --session-output "${SESSION_FIXTURE}" \
  --required-capacity 15001 \
  --concurrency 20

rm build/coupon-account-password
```

`15001`은 `RATE=500`, `DURATION=30s`일 때 필요한 `ceil(500 × 30) + 1`이다. ETL의 `--coupon-account-capacity`도 이 값 이상이어야 하며, capsule과 k6가 각각 capacity 부족을 네트워크 요청 전에 거부한다. 더 작은 기본 실행(`100 RPS × 30s`)에는 `3001`이 필요하다.

생성되는 파일 형식은 다음과 같다.

```json
{
  "datasetVersion": "coupon-issuance-v2",
  "benchmarkDatasetManifestSha256": "<benchmark-dataset-v1.json SHA-256>",
  "sessions": [
    "member-1-session-id",
    "member-2-session-id"
  ]
}
```

- 실제 파일은 `.gitignore`에 포함되어 있다. 비밀번호나 세션을 커밋하지 않는다.
- preparer는 V27 world, 여섯 observed distribution, 모든 capsule 분포 계약을 로그인 전에 검증하고, capsule의 고유 이메일과 capacity, 응답 세션 중복, 비밀번호 파일과 출력 파일의 권한을 fail-closed로 검사한다.
- 로그인 요청과 JSON 본문 읽기는 제한 시간 안에 끝나야 한다. 동시 로그인 중 하나가 실패하면 새 계정 할당을 멈추고 진행 중 요청을 기다린 뒤, 이미 발급된 세션에 logout을 best-effort로 수행하고 fixture를 남기지 않는다. logout은 HTTP 200만 성공으로 인정하며, HTTP 오류나 timeout이 있으면 실패 건수만 경고한 뒤 원래 준비 오류로 종료한다.
- 기존 `SESSION_FIXTURE`를 덮어쓰지 않으므로 새 측정 전 이전 파일을 안전하게 폐기한 뒤 실행한다. 로컬 loopback 서버에서만 준비할 때는 `AIRBOB_SESSION_PREPARATION_TEST_MODE=1`과 `http://127.0.0.1:<port>`를 함께 사용한다.
- 현재 애플리케이션 세션 TTL은 1시간이다. 기존 회원 준비/로그인 절차로 실행 직전에 새로 만들고, 전체 측정이 1시간을 넘으면 다시 발급한다.
- 한 실행에 필요한 최소 세션 수는 k6 경계 스케줄 1건을 포함한 `ceil(RATE × DURATION(초)) + 1`이다. 부족하면 네트워크 요청을 보내기 전에 실행을 중단한다.
- `SESSION_FIXTURE`에는 k6 스크립트 기준 상대 경로가 아니라 절대 경로를 넣는다.

동일한 회원 모집단은 쿠폰 ID가 다른 DB/Lua 실행에 다시 사용할 수 있다. 단, 한 실행 안에서는 회원당 요청이 한 건이어야 한다.

## 2. 쿠폰 생성과 Lua 재고 준비

각 라운드마다 다음 네 캠페인을 같은 조건으로 만든다.

- DB 워밍업 쿠폰
- Lua 워밍업 쿠폰
- DB 측정 쿠폰
- Lua 측정 쿠폰

Lua용 두 쿠폰은 발급 시작 전에 관리자 API를 한 번씩 호출한다.

```bash
curl -sS -X POST \
  -b "SESSION_ID=${ADMIN_SESSION_ID}" \
  "${BASE_URL}/api/v1/admin/coupons/${LUA_COUPON_ID}/stock/prepare"
```

준비 API는 DB의 유한·무제한 발급 한도와 활성 상태·발급 기간을 Redis로 복제하고, DB에 `redis_stock_prepared_at`을 남긴다. 발급이 이미 시작됐어도 종료 전이고 `issued_quantity` 및 실제 `member_coupon` 행이 모두 0이면 준비할 수 있다. 비활성·종료·발급 이력·이전 준비 상태는 거부한다. DB 쿠폰에는 이 API를 호출하지 않는다.

운영 API는 무제한 쿠폰도 준비하지만, DB/Lua의 재고 정합성과 처리량을 동일하게 비교하기 위해 이 벤치마크의 네 캠페인은 같은 양수 유한 수량으로 생성한다.

권장 순서는 `캠페인 생성 → Lua 캠페인 prepare → SESSION_ID 생성 → issueStartAt 도달 → k6 실행`이다. 발급 기간 경계 바로 위에서는 측정하지 않는다.

## 3. 실행

공통 값을 먼저 준비한다.

```bash
export BASE_URL="https://replace-with-isolated-coupon-lab.example.com"
export SESSION_FIXTURE="$(pwd)/load-test/fixtures/coupon-sessions.json"
export BENCHMARK_DATASET_MANIFEST="$(pwd)/build/benchmark-dataset-v1.json"
export APP_VERSION="$(git rev-parse HEAD)"
export APP_INSTANCE_COUNT=1
mkdir -p build/k6
```

```bash
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
export BENCHMARK_READ_MODEL_TOKEN
```

워밍업은 폐기용 쿠폰으로 짧게 실행한다.

```bash
VARIANT=db \
PHASE=warmup \
EXPERIMENT=capacity \
COUPON_ID="${DB_WARMUP_COUPON_ID}" \
COUPON_STOCK="${DB_WARMUP_COUPON_STOCK}" \
ROUND=1 \
RUN_ORDER=1 \
RATE=100 \
DURATION=10s \
PRE_ALLOCATED_VUS=100 \
MAX_VUS=600 \
RUN_LABEL=round-1-db-warmup \
K6_RESULT_PATH=build/k6/round-1-db-warmup.json \
k6 run load-test/k6/coupon/coupon-issuance-comparison.js
```

측정용 쿠폰은 워밍업 뒤 처음 호출한다.

```bash
VARIANT=db \
PHASE=measure \
EXPERIMENT=scarcity \
COUPON_ID="${DB_MEASURE_COUPON_ID}" \
COUPON_STOCK="${DB_MEASURE_COUPON_STOCK}" \
ROUND=1 \
RUN_ORDER=1 \
RATE=500 \
DURATION=30s \
PRE_ALLOCATED_VUS=500 \
MAX_VUS=3000 \
P99_LIMIT_MS=5000 \
RUN_LABEL=round-1-db-measure \
K6_RESULT_PATH=build/k6/round-1-db-measure.json \
k6 run load-test/k6/coupon/coupon-issuance-comparison.js
```

Lua도 `VARIANT=lua`, 별도 쿠폰 ID, 고유 `RUN_LABEL`, 실제 순서에 맞는 `RUN_ORDER`로 실행하고 부하·재고 값은 동일하게 유지한다. 순서 편향을 줄이기 위해 1라운드는 `db → lua`, 2라운드는 새 쿠폰들로 `lua → db` 순서로 교차한다.

주요 환경 변수:

| 변수 | 의미 | 기본값 |
|---|---|---:|
| `VARIANT` | `db` 또는 `lua` | 필수 |
| `BENCHMARK_READ_MODEL_TOKEN` | db v2 요청의 `X-Benchmark-Token`; lua에서는 사용하지 않음 | db에서 필수 |
| `EXPERIMENT` | `capacity`(재고 ≥ 요청 수+1) 또는 `scarcity`(재고 < 요청 수) | `scarcity` |
| `P95_LIMIT_MS` | 성공 발급 p95 실패 임계값(ms) | `1000` |
| `PHASE` | `warmup` 또는 `measure` | `measure` |
| `COUPON_ID` | 이번 실행 전용 쿠폰 ID | 필수 |
| `COUPON_STOCK` | 실행 시작 전 쿠폰 총재고 | 필수 |
| `APP_VERSION` | 배포한 Git SHA 또는 이미지 digest | 필수 |
| `APP_INSTANCE_COUNT` | ALB 뒤 애플리케이션 인스턴스 수 | 필수 |
| `ROUND` | 비교 라운드 번호 | 필수 |
| `RUN_ORDER` | 해당 라운드 안의 실행 순서 | 필수 |
| `RUN_LABEL` | 사람이 식별할 수 있는 고유 실행 이름 | 필수 |
| `RATE` | 초당 시작할 요청 수 | `100` |
| `DURATION` | `30s`, `2m`처럼 단일 단위 실행 시간 | `30s` |
| `PRE_ALLOCATED_VUS` | 미리 할당할 VU 수 | `max(50, RATE)` |
| `MAX_VUS` | 최대 VU 수 | `max(PRE_ALLOCATED_VUS, RATE×6)` |
| `REQUEST_TIMEOUT` | 개별 HTTP 제한 시간 | `10s` |
| `P99_LIMIT_MS` | p99 실패 임계값 | `5000` |
| `K6_RESULT_PATH` | JSON 결과 파일 | `build/k6/...json` |

`dropped_iterations`가 발생하면 서버 한계가 아니라 부하 발생기의 VU 부족일 수 있다. 이 스크립트는 dropped iteration이 한 건이라도 있으면 실패하므로 VU와 부하 발생기 자원을 조정한 뒤 다시 측정한다.

## 4. 결과 해석

표준 출력과 JSON에 다음을 남긴다.

- HTTP 요청 수와 실제 RPS
- 전체 HTTP RPS와 성공 발급 RPS
- 전체 동기 응답 지연과 성공 발급 전용 지연의 p50/p95/p99
- `success`, `sold_out`, `duplicate`, `not_issuable`, `unprepared`, `unexpected` 건수
- dropped iteration 수와 임계값 결과
- 쿠폰 ID·총재고, 전략, 단계, 앱 버전·인스턴스 수, 라운드·순서, RATE/DURATION, VU 설정

실제 세션 ID는 로그나 결과 JSON에 기록하지 않는다. 다음 응답은 설정 오류로 간주해 실행을 실패시킨다.

- `CP003`: 서로 다른 세션이 같은 회원이거나 기존 발급 데이터가 남음
- `CP005`: 발급 기간/활성 상태가 잘못됐거나 Lua 쿠폰을 DB URL로 호출함
- `CP011`: Lua 쿠폰 prepare 누락 또는 Redis 상태 유실
- 알 수 없는 상태·오류 코드

`CP002` 매진은 비교 결과로 집계한다. 제거된 `CP012`를 포함한 알 수 없는 오류는 실행을 실패시킨다. 애플리케이션에서는 함께 다음 Micrometer 지표를 확인한다.

- `coupon.issue.duration`
- `coupon.lua.duration`
- `coupon.database.issue.duration`
- `coupon.compensation`
- HikariCP 사용량과 DB 쿼리 지표

v2 URL은 유지하지만 전략 태그는 `lock`에서 `db`로 변경됐다. 예전 Redisson 결과와 합치지 않고 `coupon.issue.duration`, `coupon.lua.duration`, `coupon.database.issue.duration`과 k6 결과 JSON을 기준으로 한다.

## 5. 실행 후 정합성 확인

모든 요청이 끝난 뒤 MySQL에서 확인한다.

```sql
SELECT
  c.id,
  c.total_quantity,
  c.issued_quantity,
  COUNT(mc.id) AS member_coupon_count,
  COUNT(DISTINCT mc.member_id) AS distinct_member_count,
  c.redis_stock_prepared_at
FROM coupon c
LEFT JOIN member_coupon mc ON mc.coupon_id = c.id
WHERE c.id = :coupon_id
GROUP BY c.id, c.total_quantity, c.issued_quantity, c.redis_stock_prepared_at;
```

두 전략 모두 다음이 성립해야 한다.

```text
member_coupon_count == issued_quantity <= total_quantity
member_coupon_count == distinct_member_count
```

Lua 쿠폰은 Redis도 확인한다.

```bash
redis-cli HGET "coupon:{${LUA_COUPON_ID}}:meta" stock
redis-cli SCARD "coupon:{${LUA_COUPON_ID}}:issued"
```

정상 종료 뒤에는 `Redis stock + member_coupon_count == total_quantity`가 성립해야 한다.

```text
Redis SCARD(issued) == member_coupon_count
Redis stock + Redis SCARD(issued) == total_quantity
```

Lua 승인 직후 프로세스가 강제 종료되면 Redis 재고만 차감되고 DB 행이 없는 슬롯 누수가 남을 수 있다. 이번 동기 비교는 애플리케이션이 포착한 DB 실패는 보상하지만 Redis와 MySQL 사이의 분산 트랜잭션이나 강제 종료 복구까지 보장하지 않는다.

prepare 도중 Redis 쓰기 뒤 DB 준비 이력 커밋이 실패하면 Redis 키만 남을 수 있다. Lua 경로는 DB 준비 이력이 없어 영속화를 거부하고 보상을 시도한다. DB 경로는 Redis 키를 조회하지 않고 DB 준비 이력만 확인한다. 이 경우 두 URL을 섞어 측정하지 말고 새 캠페인을 사용한다.

## 6. 측정 후 서버 teardown

k6 클라이언트 셸에서 토큰을 지우는 것만으로는 v2 DB 조건부 UPDATE API가 비활성화되지 않는다. 측정 후에는 서버 프로세스와 배포 설정을 별도로 정리한다.

### DB 쿠폰 종료 처리

DB 조건부 UPDATE로 발급한 DB 워밍업·측정 쿠폰은 활성·Redis 미준비·DB 발급 이력 상태이므로 정상 AWS/OCI 시작 가드의 차단 대상이다. 결과와 정합성 증거를 먼저 저장한 뒤, benchmark JVM을 중지하기 전에 이번 실행의 **모든** DB 워밍업·측정 쿠폰에 ADMIN DELETE API를 호출해 명시적으로 비활성화한다. 라운드가 여러 개면 각 라운드의 ID에 모두 반복한다.

```bash
curl -fsS -X DELETE \
  -b "SESSION_ID=${ADMIN_SESSION_ID}" \
  "${BASE_URL}/api/v1/admin/coupons/${DB_WARMUP_COUPON_ID}"

curl -fsS -X DELETE \
  -b "SESSION_ID=${ADMIN_SESSION_ID}" \
  "${BASE_URL}/api/v1/admin/coupons/${DB_MEASURE_COUPON_ID}"
```

완전히 격리된 일회성 benchmark DB라면 이 API 처리 대신 DB 자체를 폐기해도 된다. 공유하거나 재사용할 DB에서는 쿠폰을 빠뜨리거나 가드를 우회하지 않는다. 처리 후 [운영 Lua 전환 시작 가드](#운영-lua-전환-시작-가드)의 SQL을 다시 실행해 결과가 0건인지 확인한 다음에만 정상 프로필을 재배포한다.

### 로컬

`coupon-benchmark` 프로필로 실행한 JVM을 먼저 `Ctrl-C`로 중지한다. 위 실행 예의 benchmark 프로필은 명령 범위이므로 셸에 남지 않는다. 서버 측 토큰 바인딩을 제거하고 정상 프로필로 다시 시작한다.

```bash
# coupon-benchmark JVM을 중지한 뒤 애플리케이션 실행 셸에서 실행
unset BENCHMARK_READ_MODEL_TOKEN
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

### AWS

격리된 benchmark JVM/인스턴스를 중지하거나 target group에서 drain한다. 배포 설정에서 `coupon-benchmark`를 제거하고 token secret 및 서버의 `BENCHMARK_READ_MODEL_TOKEN` 바인딩도 제거한 뒤 정상 프로필로 다시 배포한다.

재배포 후 유효한 일반 회원 세션으로 대표 v2 경로가 `404`인지 확인한다. 아래 요청은 `X-Benchmark-Token`을 의도적으로 보내지 않으므로, 프로필이 실수로 남아 있어도 쿠폰을 발급하지 않고 `403`으로 드러난다. `401`이면 세션부터 갱신한다.

```bash
curl -i -X POST \
  -b "SESSION_ID=${VERIFY_SESSION_ID}" \
  "${BASE_URL}/api/v2/coupons/${DB_MEASURE_COUPON_ID}/issue"
# 기대 결과: HTTP/1.1 404
```

마지막 명령은 위 서버 teardown과 별개로 부하 발생기 또는 k6 클라이언트 셸의 자격 증명만 지운다.

```bash
unset BENCHMARK_READ_MODEL_TOKEN
```

기존 N+1 측정 도구의 별도 사용법은 [k6 README](../README.md)에 있고, 반정규화 read model 비교는 [read-model README](../read-model/README.md)에 있다.

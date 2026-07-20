# 쿠폰 분산 락·Lua 비교 실행 가이드

`coupon-issuance-comparison.js`는 아래 두 동기 API를 같은 부하 모델로 한 번에 하나씩 측정한다.

- `VARIANT=lock`: `POST /api/v1/coupons/{couponId}/issue/lock`
- `VARIANT=lua`: `POST /api/v1/coupons/{couponId}/issue/lua`

두 API 모두 MySQL 발급 트랜잭션 커밋 뒤 `201 Created`를 반환한다. 이 테스트는 처리량과 p50/p95/p99뿐 아니라 매진, 중복, 설정 오류, 락 타임아웃을 분리해서 기록한다.

## 비교 전에 지켜야 할 조건

1. 락과 Lua는 할인·재고·발급 기간이 같은 **서로 다른 쿠폰 ID**로 실행한다.
2. 같은 쿠폰 ID에 두 URL을 섞지 않는다. Redis 준비가 DB에 기록됐거나 비정상 종료로 Redis 키만 남은 Lua 쿠폰은 서버도 락 경로를 거부한다.
3. 한 번 실행해 상태가 바뀐 쿠폰을 다음 측정에 재사용하지 않는다. 반복 측정마다 새 쿠폰을 만든다.
4. 워밍업은 측정용 쿠폰의 재고를 소모하므로 반드시 별도의 폐기용 쿠폰으로 실행한다.
5. 한 실행의 각 요청에는 서로 다른 회원의 유효한 `SESSION_ID`를 하나씩 사용한다. 한 회원의 세션 토큰을 여러 개 넣는 것도 중복 요청이므로 허용하지 않는다.
6. 애플리케이션 빌드, 인스턴스 수, DB/Redis 위치, RATE/DURATION, 쿠폰 수량을 두 전략에서 동일하게 유지한다.

이 브랜치는 과거 V1~V33을 하나의 V1로 압축한 Flyway baseline 계보를 사용한다. 측정 DB는 현재 브랜치의 schema history와 일치해야 한다. 압축 전 `flyway_schema_history`가 남은 RDS에 별도 이전 계획 없이 연결하지 않는다.

## 1. 고유 회원 세션 fixture 준비

예제 파일을 복사한 뒤 실제 세션으로 교체한다.

```bash
cp load-test/fixtures/coupon-sessions.example.json \
  load-test/fixtures/coupon-sessions.json
```

형식은 다음과 같다.

```json
{
  "datasetVersion": "coupon-issuance-v1",
  "sessions": [
    "member-1-session-id",
    "member-2-session-id"
  ]
}
```

- 실제 파일은 `.gitignore`에 포함되어 있다. 비밀번호나 세션을 커밋하지 않는다.
- 각 값은 서로 다른 회원에 연결되어야 한다. 스크립트는 토큰 문자열 중복은 검사하지만 토큰이 같은 회원을 가리키는지는 알 수 없다.
- 현재 애플리케이션 세션 TTL은 1시간이다. 기존 회원 준비/로그인 절차로 실행 직전에 새로 만들고, 전체 측정이 1시간을 넘으면 다시 발급한다.
- 한 실행에 필요한 최소 세션 수는 k6 경계 스케줄 1건을 포함한 `ceil(RATE × DURATION(초)) + 1`이다. 부족하면 네트워크 요청을 보내기 전에 실행을 중단한다.
- `SESSION_FIXTURE`에는 k6 스크립트 기준 상대 경로가 아니라 절대 경로를 넣는다.

동일한 회원 모집단은 쿠폰 ID가 다른 락/Lua 실행에 다시 사용할 수 있다. 단, 한 실행 안에서는 회원당 요청이 한 건이어야 한다.

## 2. 쿠폰 생성과 Lua 재고 준비

각 라운드마다 다음 네 캠페인을 같은 조건으로 만든다.

- 락 워밍업 쿠폰
- Lua 워밍업 쿠폰
- 락 측정 쿠폰
- Lua 측정 쿠폰

Lua용 두 쿠폰은 발급 시작 전에 관리자 API를 한 번씩 호출한다.

```bash
curl -sS -X POST \
  -b "SESSION_ID=${ADMIN_SESSION_ID}" \
  "${BASE_URL}/api/v1/admin/coupons/${LUA_COUPON_ID}/stock/prepare"
```

준비 API는 DB의 유한 재고·활성 상태·발급 기간을 Redis로 복제하고, DB에 `redis_stock_prepared_at`을 남긴다. 이미 시작됐거나 발급 이력이 있거나 준비된 쿠폰은 거부한다. 락 쿠폰에는 이 API를 호출하지 않는다.

권장 순서는 `캠페인 생성 → Lua 캠페인 prepare → SESSION_ID 생성 → issueStartAt 도달 → k6 실행`이다. 발급 기간 경계 바로 위에서는 측정하지 않는다.

## 3. 실행

공통 값을 먼저 준비한다.

```bash
export BASE_URL=http://localhost:8080
export SESSION_FIXTURE="$(pwd)/load-test/fixtures/coupon-sessions.json"
export APP_VERSION="$(git rev-parse --short HEAD)"
export APP_INSTANCE_COUNT=1
mkdir -p build/k6
```

워밍업은 폐기용 쿠폰으로 짧게 실행한다.

```bash
VARIANT=lock \
PHASE=warmup \
COUPON_ID="${LOCK_WARMUP_COUPON_ID}" \
COUPON_STOCK="${LOCK_WARMUP_COUPON_STOCK}" \
ROUND=1 \
RUN_ORDER=1 \
RATE=100 \
DURATION=10s \
PRE_ALLOCATED_VUS=100 \
MAX_VUS=600 \
RUN_LABEL=round-1-lock-warmup \
K6_RESULT_PATH=build/k6/round-1-lock-warmup.json \
k6 run load-test/k6/coupon-issuance-comparison.js
```

측정용 쿠폰은 워밍업 뒤 처음 호출한다.

```bash
VARIANT=lock \
PHASE=measure \
COUPON_ID="${LOCK_MEASURE_COUPON_ID}" \
COUPON_STOCK="${LOCK_MEASURE_COUPON_STOCK}" \
ROUND=1 \
RUN_ORDER=1 \
RATE=500 \
DURATION=30s \
PRE_ALLOCATED_VUS=500 \
MAX_VUS=3000 \
P99_LIMIT_MS=5000 \
RUN_LABEL=round-1-lock-measure \
K6_RESULT_PATH=build/k6/round-1-lock-measure.json \
k6 run load-test/k6/coupon-issuance-comparison.js
```

Lua도 `VARIANT=lua`, 별도 쿠폰 ID, 고유 `RUN_LABEL`, 실제 순서에 맞는 `RUN_ORDER`로 실행하고 부하·재고 값은 동일하게 유지한다. 순서 편향을 줄이기 위해 1라운드는 `lock → lua`, 2라운드는 새 쿠폰들로 `lua → lock` 순서로 교차한다.

주요 환경 변수:

| 변수 | 의미 | 기본값 |
|---|---|---:|
| `VARIANT` | `lock` 또는 `lua` | 필수 |
| `PHASE` | `warmup` 또는 `measure` | `measure` |
| `COUPON_ID` | 이번 실행 전용 쿠폰 ID | 필수 |
| `COUPON_STOCK` | 실행 시작 전 쿠폰 총재고 | 필수 |
| `APP_VERSION` | 배포한 Git SHA 또는 이미지 태그 | 필수 |
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
- `success`, `sold_out`, `duplicate`, `not_issuable`, `unprepared`, `lock_timeout`, `unexpected` 건수
- dropped iteration 수와 임계값 결과
- 쿠폰 ID·총재고, 전략, 단계, 앱 버전·인스턴스 수, 라운드·순서, RATE/DURATION, VU 설정

실제 세션 ID는 로그나 결과 JSON에 기록하지 않는다. 다음 응답은 설정 오류로 간주해 실행을 실패시킨다.

- `CP003`: 서로 다른 세션이 같은 회원이거나 기존 발급 데이터가 남음
- `CP005`: 발급 기간/활성 상태가 잘못됐거나 Lua 쿠폰을 락 URL로 호출함
- `CP011`: Lua 쿠폰 prepare 누락 또는 Redis 상태 유실
- 알 수 없는 상태·오류 코드

`CP002` 매진과 `CP012` 락 타임아웃은 비교 결과로 집계한다. 애플리케이션에서는 함께 다음 Micrometer 지표를 확인한다.

- `coupon.issue.duration`
- `coupon.lock.wait.duration`, `coupon.lock.timeout`
- `coupon.lua.duration`
- `coupon.database.issue.duration`
- `coupon.compensation`
- HikariCP 사용량과 DB 쿼리 지표

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

prepare 도중 Redis 쓰기 뒤 DB 준비 이력 커밋이 실패하면 Redis 키만 남아 해당 쿠폰이 fail-closed 상태가 될 수 있다. 이 경우 락 URL로 우회하지 말고 캠페인을 새로 만들거나 별도의 검증된 운영 복구 절차를 사용한다. 락 경로의 측정값에는 이 상태를 차단하기 위한 Redis 키 존재 확인 1회가 포함된다.

기존 N+1 측정 도구의 별도 사용법은 [k6/README.md](k6/README.md)에 있고, 반정규화 read model 비교는 [k6/read-model/README.md](k6/read-model/README.md)에 있다.

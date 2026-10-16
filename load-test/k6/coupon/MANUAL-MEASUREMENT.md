> 과거 부하 테스트를 설명하기 위한 보관 문서다. 기준 소스는 `8b3cc82b`이며, 현재 앱에서는 비교 API와 실험 프로필을 제거했다.
> 아래 명령과 조건은 측정 당시 버전에 해당한다. 재현할 때는 결과에 기록된 앱 커밋·이미지·데이터셋을 함께 사용한다.
> [당시 문서 원문](https://github.com/eeoos/airbob/blob/8b3cc82b923347a0c6d781e3f0f755eb177e8b14/load-test/k6/coupon/MANUAL.md) · [전체 보관 안내](../../README.md)

# 쿠폰 실험 수동 실행

반복 실험은 [실행기](MEASUREMENT.md)를 우선 사용한다. 이 문서는 한 번의 k6 실행과 사후 점검 계약이다.
운영 발급은 V1 SKIP LOCKED, 비교 발급은 토큰으로 보호되는 V2이다.
V2의 variant는 `db`, `skip_surrogate_rr`, `skip_surrogate_rc`, `skip_composite_rr`, `skip_composite_rc`다.
Redis Lua와 무제한 쿠폰은 새 생성·실험에서 지원하지 않는다.

## 1. 고유 회원 세션 fixture 준비

실제 ACTIVE 회원 목록을 V30 소스 manifest에 연결하는 방법은 [AWS 가이드](AWS-MEASUREMENT.md)를 따른다.
관리자와 실험 회원을 분리하고, 토큰·비밀번호·세션은 0600 파일로 보관한다.

```bash
node load-test/k6/coupon/prepare-coupon-sessions.js \
  --base-url https://your-lab.example.com \
  --manifest /absolute/coupon-accounts.json \
  --password-file /absolute/coupon-account-password \
  --session-output /absolute/coupon-sessions.json \
  --required-capacity 15001 --concurrency 20
```

필요 세션은 RPS × 시간 + 1개다. 세션 TTL은 1시간이므로 로그인·대기·측정 시간을 함께 고려한다.
기존 출력은 덮어쓰지 않으며, 파일 시각을 갱신해 오래된 세션을 새 것으로 취급하지 않는다.
부분 로그인 실패는 fixture를 남기지 않고 이미 만든 세션의 로그아웃을 시도한다.
로컬 HTTP 서버에서는 `AIRBOB_SESSION_PREPARATION_TEST_MODE=1`과 loopback 주소를 함께 써야 한다.

## 2. 새 쿠폰 준비

fixture API에 `run_id`, `label`, `variant`, `stock`, `lifetime_seconds`를 보낸다.
응답의 `coupon_id`, `issue_start_at`, `issue_end_at`을 기록한다.
SKIP unit 생성은 이 요청의 트랜잭션에 포함되고 완료 후 2초 뒤 발급을 열도록 예약한다.
발급 시작 시각은 UTC `Z`가 포함된 ISO 8601 instant이다. 준비 상태 확인 후 해당 시각을 기다린다.
실행기는 시간대 없는 응답을 거부하며 실행 컴퓨터의 시간대로 재해석하지 않는다.
stock/prepare를 또 호출하거나 같은 쿠폰에 다른 전략을 섞지 않는다.

운영 관리에서는 발급 전 양의 총수량 증감과 폐기만 허용한다. 준비 완료 후 증량은 뒤 번호 추가,
감량은 목표 수량보다 큰 번호 삭제다. 시작 후에는 첫 발급 여부와 무관하게 변경·폐기를 거부한다.
운영 폐기는 아직 발급하지 않은 쿠폰·unit의 삭제이고, 실험 종료 API는 이력 보존을 위한 비활성화다.

## 3. 한 번의 k6 실행

세션·토큰을 출력하지 않도록 주의한다. `BENCHMARK_READ_MODEL_TOKEN`은 V2 호출에 쓰는 토큰이다. 아래처럼 개인 파일에서 읽고 출력하지 않는다.
환경 변수 이름과 필수 계약은 스크립트가 네트워크 요청 전에 검증한다.

```bash
read -r BENCHMARK_READ_MODEL_TOKEN < /absolute/benchmark-token
export BENCHMARK_READ_MODEL_TOKEN
BASE_URL=https://your-lab.example.com \
BENCHMARK_DATASET_MANIFEST=/absolute/coupon-accounts.json \
SESSION_FIXTURE=/absolute/coupon-sessions.json \
APP_VERSION=0123456789abcdef0123456789abcdef01234567 \
APP_INSTANCE_COUNT=1 VARIANT=skip_surrogate_rr PHASE=measure \
EXPERIMENT=capacity COUPON_ID=123 COUPON_STOCK=15001 \
ROUND=1 RUN_ORDER=1 RATE=500 DURATION=30s \
PRE_ALLOCATED_VUS=500 MAX_VUS=500 P95_LIMIT_MS=500 P99_LIMIT_MS=1000 \
RUN_LABEL=round-1-surrogate-rr K6_RESULT_PATH=build/k6/round-1-surrogate-rr.json \
k6 run load-test/k6/coupon/coupon-issuance-comparison.js
```

상수 예시 ID·버전은 실제 값으로 바꾼다. 워밍업은 별도 쿠폰과 `PHASE=warmup`으로 실행한다.
scarcity는 전체 요청보다 적은 동일 재고를 준비하고 성공/품절 응답 지연을 분리한다.
한 번의 결과로 키나 격리 수준의 우위를 결정하지 않는다.

## 4. 응답과 재고 검증

| 결과 | 응답 |
|---|---|
| 커밋 완료 | 201 |
| 이미 발급 | 409 / CP003 |
| 실제 품절 | 409 / CP002 |
| 발급 불가 | 409 / CP005 |
| 준비 안 됨 | 503 / CP011 |
| 재시도 후 경합 유지 | 503 / CP012 |

정확한 상태·코드 쌍으로 집계한다. CP012를 품절로 계산하지 않는다.
현재 값의 최종 계약은 ErrorCode 및 k6 분류 함수다.
모든 요청 종료 후 fixture GET으로 회원 쿠폰 수·고유 회원 수·성공 응답 수의 일치를 검사한다.

```sql
SELECT c.id, c.issue_strategy, c.total_quantity, c.stock_prepared_at,
       c.issued_quantity AS legacy_issued_quantity,
       (SELECT COUNT(*) FROM member_coupon mc WHERE mc.coupon_id=c.id) AS actual_issued,
       (SELECT COUNT(DISTINCT mc.member_id) FROM member_coupon mc WHERE mc.coupon_id=c.id) AS distinct_members
FROM coupon c WHERE c.id = ?;

SELECT COUNT(*) AS remaining FROM coupon_issue_unit WHERE coupon_id = ?;
-- 복합 키 비교군이면 coupon_issue_unit_composite를 조회한다.
```

A는 legacy 카운터 = actual_issued, 준비 마커 NULL, unit 없음이어야 한다.
SKIP은 실제 발급 + 남은 unit = 총수량, 준비 마커 존재가 불변식이다.
SKIP의 legacy 카운터는 새 발급으로 바뀌지 않는다.

## 5. 관측

`coupon.issue.duration`, `coupon.database.issue.duration`, `coupon.issue.retryable`을 기록한다.
재시도 사유는 empty_unit/deadlock/lock_timeout/policy_lock_busy의 제한된 값이다.
회원 ID·쿠폰 ID는 metric tag에 넣지 않는다.
발급 API의 Hibernate SQL TOTAL sum/count 전후 차이를 비교하고 k6 요청 수와 맞춘다.
MySQL의 data_locks/data_lock_waits, 실제 격리 수준, deadlock wait graph는 별도 관측한다.
일관된 테이블 순서가 모든 중복 키 잠금 전환 교착 상태를 없앤다고 가정하지 않는다.

## 6. 측정 후 서버 teardown

원본 지표를 먼저 보존한 뒤 모든 전략의 fixture를 runId와 함께 DELETE하여 비활성화한다.
회원 쿠폰과 소비 이력은 보존한다. 재고 초기화나 공용 Redis flush는 하지 않는다.
응답을 잃은 생성 요청과 정리 실패는 보고서의 label·runId로 찾아 종료한다.
V29 이후 구버전 Lua 바이너리를 그대로 재가동하지 않는다. 운영 전환은 아래 절차를 따른다.

## V29 전환

구버전 쿠폰 writer와 진행 중 발급·보상을 종료한 뒤 새 마이그레이션을 적용한다.
V29는 Redis 준비 시각이 하나라도 남아 있으면 중단하며, 자동 재준비·수량 추정·발급 이력 삭제를 하지 않는다.
해당 캠페인의 상태와 기존 Redis 준비 시각을 외부 전환 기록에 보존하고 이관 또는 종료 정책을 먼저 결정한다.
기존 활성 캠페인을 단순히 마커만 지우고 재준비하지 않는다. 준비 서비스도 카운터·실제 발급 이력을 검사한다.

과거 NULL 수량·발급 이력은 그대로 보존한다. 새 발급에는 양의 유한 수량과 unit 준비가 필요하다.
실험 단계에는 기존 total_quantity nullable과 A 카운터를 유지하며 양수 CHECK를 추가하지 않는다.
V29 실패 뒤에는 부분 적용 여부를 조사한 뒤 운영자가 Flyway 복구 절차를 수행한다.
보존된 V28 원본을 덮어쓰거나 원본 manifest의 버전 숫자만 바꾸지 않는다.

## V30 UTC 전환

쿠폰 업무 DATETIME과 사용 시각은 UTC로 저장·비교하고, API는 UTC `Z`를 반환한다.
기존 서울 업무 값만 9시간 빼며 이미 UTC인 감사 시각과 재고·발급 이력은 보존한다.
쿠폰을 사용하는 예약·결제 경로까지 구버전 reader/writer를 중지하고, UTC 응답을 한국 시간으로
표시하는 프론트와 함께 전환한다. [쿠폰 시간 계약](../../../docs/coupon-time-contract.md)을 따른다.
V30 이후 V29 애플리케이션을 재가동하지 않는다. 과거 V29 측정 자료는 해당 바이너리·실행기를 그대로 보존한다.

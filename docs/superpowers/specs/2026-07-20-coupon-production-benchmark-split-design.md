# 쿠폰 운영 Lua·Redisson 벤치마크 분리 설계

## 배경

현재 쿠폰 발급 컨트롤러는 Redisson 분산 락과 Redis Lua 구현을 모두 v1 운영 API로 노출한다.

- `POST /api/v1/coupons/{couponId}/issue/lock`
- `POST /api/v1/coupons/{couponId}/issue/lua`

두 경로는 구현 비교에는 유용하지만 운영 API가 동시성 제어 전략을 직접 노출하고, 운영 환경에서도 Redisson 비교 경로를 호출할 수 있게 한다. 실제 운영 전략은 Lua로 확정됐으므로 운영 경로는 전략 중립적인 API 하나만 제공하고 Redisson은 명시적인 벤치마크 환경에서만 활성화한다.

별도로, Hibernate batch fetch 크기 실험은 탐색 목적으로 완료됐으며 더 이상 유지하지 않는다.

## 목표

- 운영 v1 쿠폰 발급은 Lua 구현만 호출한다.
- Redisson 구현은 `coupon-benchmark` 프로필에서만 생성되고 v2 벤치마크 API로만 접근한다.
- 벤치마크 API는 회원 세션과 `X-Benchmark-Token`을 모두 요구한다.
- 기존 쿠폰 오류 응답, 동시성 불변식, 쿠폰 커스텀 메트릭과 k6 결과 형식을 유지한다.
- 사용하지 않는 batch-fetch-size 하네스와 문서 참조를 제거한다.

## 비목표

- Redis Lua 스크립트, DB 보상 로직 또는 Redisson 락 알고리즘을 변경하지 않는다.
- 쿠폰 테이블이나 Flyway 스키마를 변경하지 않는다.
- Lua 재고 준비 관리자 API나 준비 절차를 변경하지 않는다.
- 쿠폰 세션 fixture 형식과 결과 JSON 스키마를 변경하지 않는다.
- 기존 `benchmark.read-model` 설정 이름을 이번 작업에서 일반화하지 않는다.
- 과거 설계 문서의 당시 URL을 소급해 수정하지 않는다. 이 문서가 이후 운영 계약을 정의한다.

## API 계약

| 용도 | 메서드와 경로 | 구현 | 활성 조건 | 접근 조건 |
|---|---|---|---|---|
| 운영 발급 | `POST /api/v1/coupons/{couponId}/issue` | Redis Lua | 모든 정상 프로필 | 회원 세션 |
| 비교 발급 | `POST /api/v2/coupons/{couponId}/issue` | Redisson 분산 락 | `coupon-benchmark` 프로필과 enabled property | 회원 세션 + benchmark token |

기존 `/issue/lua`와 `/issue/lock` 경로는 호환 alias 없이 제거한다. 저장소 내부 호출자는 테스트와 부하 테스트뿐이며 함께 새 계약으로 변경한다.

두 경로 모두 성공 시 기존과 동일하게 `201 Created`와 `ApiResponse.success()`를 반환한다. 쿠폰 도메인 오류 코드와 HTTP 상태도 변경하지 않는다.

## 애플리케이션 구조

### 운영 경로

`CouponController`는 발급 가능한 쿠폰 조회와 운영 발급만 담당한다. 발급 메서드는 `CouponLuaIssueService`만 주입받아 호출하며 컨트롤러에서 전략 선택을 허용하지 않는다.

Lua 운영에 필요한 다음 구성요소는 모든 정상 프로필에서 계속 생성한다.

- `CouponLuaIssueService`
- `CouponRedisStockManager`
- `CouponStockPreparationService`
- `CouponIssueTransactionService`
- `POST /api/v1/admin/coupons/{couponId}/stock/prepare`

관리자는 쿠폰 발급 시작 전에 Redis 재고를 준비해야 한다. 준비되지 않은 쿠폰의 운영 발급은 기존처럼 `CP011`로 실패한다.
`coupon-benchmark` 프로필에서도 이 운영 경로와 Lua 구성요소는 그대로 활성화해, 같은 애플리케이션 설정에서 v1 Lua와 v2 Redisson을 각각 측정할 수 있게 한다.

### 벤치마크 경로

신규 `CouponBenchmarkController`는 다음 조건을 모두 만족할 때만 등록한다.

- `@Profile("coupon-benchmark")`
- `@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")`

컨트롤러는 `BenchmarkAccessGuard`로 토큰을 검증한 뒤 `UserContext`의 회원 ID와 쿠폰 ID를 `CouponLockIssueService`에 전달한다.

`CouponLockIssueService`와 `CouponLockManager`도 `coupon-benchmark` 프로필에서만 생성한다. 공유 트랜잭션 경계인 `CouponIssueTransactionService`는 Lua 운영에서도 필요하므로 프로필로 제한하지 않는다. 그 안의 `issueUnderLock` 메서드는 프로필로 제한된 Redisson 서비스에서만 호출된다.

### 설정

`application-coupon-benchmark.yaml`은 기존 벤치마크 토큰 계약을 재사용한다.

```yaml
benchmark:
  read-model:
    enabled: true
    token: ${BENCHMARK_READ_MODEL_TOKEN}
```

`BenchmarkAccessGuard`의 활성 프로필에 `coupon-benchmark`를 추가한다. `BENCHMARK_READ_MODEL_TOKEN`이 없으면 프로필 시작 시 즉시 실패해 토큰 없이 벤치마크 API가 열리지 않게 한다.

기존 read-model benchmark 패턴과 같이 profile은 Redisson 발급 빈의 생성 범위를 제한하고 enabled property는 HTTP 컨트롤러 노출을 제어한다. 따라서 `coupon-benchmark` 프로필만 활성화하고 enabled를 명시적으로 끄면 Redisson 서비스와 락 매니저는 생성되지만 v2 API는 등록되지 않는다.

## 인증 흐름

운영 v1 요청은 기존처럼 `SessionAuthFilter`를 통과한다.

v2 쿠폰 경로도 회원 식별이 필요하므로 세션 필터 등록 패턴에 `/api/v2/coupons/*`를 추가한다. `/api/v2/*` 전체를 추가하면 공개 검색 API까지 인증 대상으로 바뀌므로 사용하지 않는다.

벤치마크 요청의 처리 순서는 다음과 같다.

1. `SessionAuthFilter`가 `SESSION_ID`를 검증하고 `UserContext`를 설정한다.
2. `CouponBenchmarkController`가 `X-Benchmark-Token`을 검증한다.
3. `CouponLockIssueService`가 Redisson 락을 얻고 기존 DB 발급 트랜잭션을 실행한다.
4. 응답 완료 후 기존 필터가 `UserContext`를 정리한다.

토큰은 회원 세션을 대체하지 않는다. 회원별 중복 발급과 고유 세션 비교 조건을 보존하기 위해 두 자격 증명이 모두 필요하다.

## k6 비교 흐름

`VARIANT` 값은 `lock`과 `lua`를 유지해 기존 메트릭 태그와 결과 해석을 보존한다.

| variant | 대상 경로 | 요청 자격 증명 |
|---|---|---|
| `lua` | `/api/v1/coupons/{couponId}/issue` | `SESSION_ID` |
| `lock` | `/api/v2/coupons/{couponId}/issue` | `SESSION_ID`, `X-Benchmark-Token` |

공통 helper가 variant별 경로와 헤더를 구성한다. lock 실행에서 `BENCHMARK_READ_MODEL_TOKEN`이 없거나 공백이면 네트워크 요청 전에 실패한다. Lua 실행은 benchmark token을 요구하거나 전송하지 않는다. 토큰과 세션 값은 결과 artifact에 기록하지 않는다.

각 전략은 기존처럼 별도 쿠폰 ID, 같은 부하 조건과 고유 회원 세션 집합을 사용한다. Lua 쿠폰만 관리자 prepare를 수행한다. `201`, `CP002`, `CP003`, `CP005`, `CP011`, `CP012` 분류와 지표 이름은 유지한다. 인증 실패인 `401`, `403`, `B001`은 `unexpected`로 분류돼 실행을 실패시킨다.

## batch-fetch-size 제거

다음 하네스 파일을 삭제한다.

- `load-test/k6/batch-fetch-size/.gitignore`
- `load-test/k6/batch-fetch-size/README.md`
- `load-test/k6/batch-fetch-size/analyze.py`
- `load-test/k6/batch-fetch-size/core-get.js`
- `load-test/k6/batch-fetch-size/evaluate.sh`
- `load-test/k6/batch-fetch-size/test_analyze.py`

`load-test/k6/README.md`의 실험 수와 표, 내부 파일 설명에서 batch-fetch-size를 제거한다. 최근 본 숙소 N+1 재현에 필요한 `application-nplus1-benchmark.yaml`의 `default_batch_fetch_size: 0`은 별도 기능이므로 유지한다.

살아 있는 실행 문서인 `load-test/README.md`도 새 v1/v2 경로, `coupon-benchmark` 프로필과 lock variant의 benchmark token 요구사항에 맞게 갱신한다.

## 오류 처리와 안전성

- 운영 Lua 경로의 승인, DB 영속화, Redis 보상 순서는 변경하지 않는다.
- Redisson 경로의 락 획득 제한 시간, watchdog과 락 해제 순서는 변경하지 않는다.
- 정상 프로필에는 Redisson 컨트롤러와 Redisson 발급 빈이 존재하지 않는다.
- `coupon-benchmark`는 일반 사용자 트래픽을 받지 않는 격리 인스턴스에서만 활성화한다.
- 기존 Lua 재고 준비 여부 검사로 같은 쿠폰 ID에 Lua와 lock을 혼용하지 못하게 한다.
- 경로 변경으로 Spring HTTP 메트릭의 `uri` 태그는 달라진다. 전후 비교의 연속성은 `coupon.issue.duration`, `coupon.lock.*`, `coupon.lua.duration`, `coupon.database.issue.duration` 등의 쿠폰 커스텀 메트릭과 k6 artifact를 기준으로 유지한다.

## 테스트 전략

구현은 테스트 우선으로 진행한다.

1. 운영 컨트롤러 테스트를 `/api/v1/coupons/{id}/issue`가 Lua 서비스만 호출하도록 변경하고 먼저 실패를 확인한다.
2. 벤치마크 컨트롤러의 프로필·property 조건, 토큰 검증과 Redisson 서비스 호출 테스트를 추가하고 먼저 실패를 확인한다. profile과 property 중 하나만 만족할 때는 컨트롤러가 등록되지 않아야 한다.
3. v2 쿠폰 경로가 세션 필터 대상인지 검증한다. 세션이 없으면 `401`, 정상 세션에서 토큰이 없거나 틀리면 `403/B001`이고 Redisson 서비스는 호출되지 않아야 한다.
4. `coupon-benchmark` 프로필 설정이 공통 enabled/token 계약을 제공하는지 검증한다.
5. k6 helper 테스트에 Lua v1 경로, lock v2 경로, lock token 필수와 헤더 생성 계약을 추가한다.
6. 최소 구현 후 관련 Spring 테스트와 k6 helper 테스트를 모두 통과시킨다.
7. 쿠폰 동시성 테스트는 `test,coupon-benchmark` 프로필과 테스트 컨텍스트에 명시한 `BENCHMARK_READ_MODEL_TOKEN=test-token`을 사용해 lock과 Lua 서비스를 계속 직접 비교한다.
8. 정상 프로필의 MVC 테스트에서 기존 `/issue/lua`, `/issue/lock`과 v2 경로가 노출되지 않는지 확인한다.

## 완료 조건

- 정상 프로필에서 v1 전략 중립 API만 노출되고 Lua 서비스를 호출한다.
- 정상 프로필에서 기존 suffix 경로와 v2 Redisson 경로는 존재하지 않는다.
- `coupon-benchmark` 프로필에서는 인증된 회원이 올바른 토큰으로만 v2 Redisson 경로를 호출할 수 있다.
- k6가 새 경로와 자격 증명으로 lock/Lua 비교를 수행하고 기존 결과 계약을 유지한다.
- batch-fetch-size 하네스와 살아 있는 문서 참조가 모두 제거된다.
- 관련 Java 및 k6 테스트가 통과한다.

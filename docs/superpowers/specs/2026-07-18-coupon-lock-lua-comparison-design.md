# 선착순 쿠폰 분산 락·Lua 비교 설계

## 목표

오전 10시에 열리는 유한 수량 숙박 쿠폰을 대상으로 Redisson 분산 락과 Redis Lua 원자 처리의 성능과 동시성 특성을 공정하게 비교한다. 두 경로는 동일한 발급 규칙을 제공하고, `201 Created`를 반환하기 전에 MySQL의 `member_coupon` 저장과 `coupon.issued_quantity` 증가를 완료한다.

이번 단계는 동기 발급 비교까지만 구현한다. Redis Stream, Kafka, Outbox 기반 비동기 발급은 포함하지 않는다.

## 범위

### 포함

- 분산 락 발급 API와 Lua 발급 API 분리
- 발급 기간과 쿠폰 사용 기간 분리
- Lua가 사용할 Redis 재고·발급 메타데이터 준비 API
- Lua의 시간, 활성 상태, 중복, 재고 검증과 원자적 재고 차감
- DB 저장 실패 시 멱등한 Redis 보상
- 두 경로의 기능·동시성·성능 측정 환경
- 기존 무락 발급 구현과 확률적인 초과 발급 테스트 제거

### 제외

- Redis Stream과 비동기 `202 Accepted` 발급
- Kafka 또는 Transactional Outbox를 통한 쿠폰 발급 처리
- 이벤트 진행 중 발급 수량이나 발급 기간 변경
- Redis와 MySQL 사이의 분산 트랜잭션
- 기존 `event` 도메인 제거

## API 계약

### 사용자 발급 API

```http
POST /api/v1/coupons/{couponId}/issue/lock
POST /api/v1/coupons/{couponId}/issue/lua
```

두 API의 성공 응답은 동일하다.

```text
201 Created = Redis 또는 락 처리뿐 아니라 DB 발급 트랜잭션까지 커밋됨
```

발급 전략을 enum, 요청 필드 또는 공통 Strategy 인터페이스로 선택하지 않는다. URL과 서비스 클래스를 분리해 측정 종료 후 분산 락 구현을 독립적으로 삭제할 수 있게 한다.

### Redis 준비 API

```http
POST /api/v1/admin/coupons/{couponId}/stock/prepare
```

이 API는 Lua 경로에만 필요하다. 이벤트 시작 전에 DB 쿠폰 설정을 Redis로 복제하며, 다음 조건을 모두 만족해야 한다.

- `totalQuantity`가 존재하는 유한 수량 쿠폰이다.
- 쿠폰이 활성 상태다.
- 발급 시작 전이다.
- `issuedQuantity`와 실제 `member_coupon` 발급 수가 모두 0이다.
- 동일 쿠폰의 Redis 메타데이터나 발급자 Set이 존재하지 않는다.

준비 작업은 기존 Redis 값을 덮어쓰거나 발급자 Set을 삭제하지 않는다. 이미 준비됐거나 일부 발급된 쿠폰에 다시 호출하면 `409 Conflict`를 반환한다. 이 단계에서는 매일 새로운 쿠폰 캠페인을 생성하고 한 번만 준비하는 운영 모델을 사용한다.

## 기간 모델

현재 `Coupon.startDate`와 `endDate`가 발급 가능 기간과 사용 가능 기간을 동시에 표현한다. 오전 10시에 짧게 배포하고 이후 여러 날 사용할 수 있도록 다음 의미로 분리한다.

- `issueStartAt`, `issueEndAt`: 회원이 쿠폰을 발급받을 수 있는 기간
- `usableFrom`, `usableUntil`: 발급된 쿠폰을 예약에 적용할 수 있는 기간

기존 데이터는 현재 `startDate`, `endDate` 값을 두 기간에 모두 복사해 기존 동작을 유지한다. `Coupon.isIssuable()`은 활성 상태, 발급 기간, DB 재고를 검사하고 `Coupon.isUsable()`은 활성 상태와 사용 기간만 검사한다.

모든 기간은 시작 시각을 포함하고 종료 시각을 포함하지 않는 `[start, end)`로 판정한다. 경계 시각이 아닌 일반적인 부하 구간에서 성능을 측정하고, 경계 동작은 기능 테스트로 별도 검증한다.

Redis 준비가 끝난 뒤에는 `totalQuantity`, 활성 상태, 발급 기간을 수정할 수 없다. 이벤트 종료 후 새 쿠폰을 만드는 방식으로 변경한다. 긴급 중단과 실행 중 설정 변경은 별도 후속 설계 대상으로 둔다.

## 컴포넌트 구조

### `CouponLockIssueService`

분산 락 수명주기만 책임진다.

1. `coupon:lock:{couponId}` Redisson 락을 획득한다.
2. `CouponIssueTransactionService.issueUnderLock()`을 호출한다.
3. DB 트랜잭션 커밋 이후 락을 해제한다.

락은 lease time을 지정하지 않고 Redisson WatchDog 갱신을 사용한다. 제한 시간 내 획득하지 못하면 매진과 구별되는 서비스 가용성 오류로 반환하고 별도 지표로 집계한다.

### `CouponLuaIssueService`

Lua 승인과 승인된 발급의 DB 저장을 조정한다.

1. 발급 Lua를 호출한다.
2. Lua 반환 코드를 도메인 결과로 변환한다.
3. 승인된 요청만 `CouponIssueTransactionService.persistApprovedIssue()`로 저장한다.
4. DB 저장 중 잡힌 예외가 발생하면 보상 Lua를 호출한 뒤 원래 예외를 다시 던진다.

### `CouponIssueTransactionService`

DB 트랜잭션 경계를 제공한다.

- `issueUnderLock()`: DB에서 활성 상태, 발급 기간, 재고, 회원 중복을 검증한 뒤 발급한다.
- `persistApprovedIssue()`: Redis에서 이미 검증된 당첨 요청의 `issuedQuantity`를 증가시키고 `MemberCoupon`을 저장한다.

두 쓰기 경로는 `coupon.issued_quantity` 증가와 `member_coupon` 삽입을 하나의 트랜잭션으로 처리한다. DB의 `UNIQUE(member_id, coupon_id)`는 모든 경로의 최종 중복 방어선이다.

두 경로의 업무 검증 순서는 `활성·발급 기간 → 회원 중복 → 재고`로 통일한다. 따라서 이미 쿠폰을 받은 회원이 매진 후 다시 요청했을 때도 두 경로 모두 중복 발급 결과를 반환한다.

### `CouponRedisStockManager`

Redis 키 생성, 준비, 발급 Lua 실행, 보상 Lua 실행을 담당한다. 컨트롤러와 DB 트랜잭션 서비스는 Redis 키와 Lua 반환값을 직접 다루지 않는다.

기존의 여러 발급 방식을 한 클래스에 섞은 `CouponIssueService`는 위 책임별 컴포넌트로 분리한다.

## Redis 데이터와 Lua 계약

Redis Cluster 전환 가능성을 고려해 같은 쿠폰의 키는 동일한 hash tag를 사용한다.

```text
coupon:{couponId}:meta     Hash
  stock                    남은 발급 수
  issueStartAt             epoch milliseconds
  issueEndAt               epoch milliseconds
  active                   1 또는 0
  expiresAt                Redis 정리 시각

coupon:{couponId}:issued   Set<memberId>
```

`meta`와 `issued`는 발급 종료 이후 유예 기간까지 유지한 뒤 만료한다. 최초 발급으로 `issued` Set이 생성될 때도 같은 만료 시각을 적용한다.

### 발급 Lua

Lua는 애플리케이션 인스턴스 시계 대신 Redis `TIME`을 사용한다. 아래 작업 전체가 하나의 스크립트에서 실행된다.

1. `meta` 존재 여부 확인
2. 활성 상태 확인
3. 발급 시작·종료 시각 확인
4. `issued` Set으로 회원 중복 확인
5. 남은 재고 확인
6. 회원을 `issued` Set에 추가
7. `stock` 1 감소

반환 계약은 다음과 같다.

| 반환값 | 의미 |
|---:|---|
| `0` 이상 | 승인 후 남은 재고 |
| `-1` | 매진 |
| `-2` | 이미 발급받은 회원 |
| `-3` | 발급 시작 전 |
| `-4` | 발급 종료 후 |
| `-5` | Redis 준비 안 됨 |
| `-6` | 비활성 쿠폰 |

### 보상 Lua

DB 발급 트랜잭션이 실패하면 회원을 `issued` Set에서 제거한다. `SREM` 결과가 1일 때만 재고를 1 증가시킨다. 이미 보상됐거나 승인되지 않은 회원이면 아무것도 변경하지 않아 중복 보상으로 재고가 증가하지 않게 한다.

Redis 메타데이터가 사라져 보상할 수 없는 경우에는 원래 DB 오류와 별개로 보상 실패 로그와 카운터를 남긴다.

## 오류 계약

두 발급 경로는 같은 업무 결과에 같은 HTTP 상태와 쿠폰 오류 코드를 사용한다.

- 매진: `409 Conflict`
- 중복 발급: `409 Conflict`
- 시작 전, 종료 후, 비활성: `409 Conflict`
- Redis 미준비: `503 Service Unavailable`
- 분산 락 획득 타임아웃: `503 Service Unavailable`
- 예상하지 못한 Redis 또는 DB 장애: `500 Internal Server Error`

분산 락 타임아웃은 현재의 일반 발급 실패 오류와 분리해 성능 결과에서 매진으로 오인하지 않게 한다.

## 정합성 보장과 한계

모든 요청과 보상이 끝나 진행 중인 발급이 없는 시점에, 정상 실행과 애플리케이션이 포착한 DB 실패에 대해 다음 불변식을 보장한다.

```text
member_coupon 수 == coupon.issued_quantity
coupon.issued_quantity <= coupon.total_quantity
회원별 동일 쿠폰 발급 수 <= 1
Lua 경로: Redis stock + DB 발급 수 == coupon.total_quantity
```

Lua 승인 직후부터 DB 커밋 또는 보상 전까지 Redis와 MySQL은 일시적으로 다른 상태일 수 있다. 이 사이에 프로세스가 강제 종료되면 Redis 재고가 감소했지만 DB 발급이 없는 슬롯 누수가 남을 수 있다. 1차 구현은 이 이중 쓰기 장애를 완전히 해결했다고 주장하지 않는다.

pending ZSet과 복구 스케줄러는 이후 Redis Stream으로 교체될 중간 구조이므로 이번 범위에 추가하지 않는다. 2차 단계에서 재고 차감과 `XADD`를 하나의 Lua로 실행하고 비동기 Consumer가 DB를 저장해 이 장애 구간을 제거한다.

## 무락 구현 제거

다음 재현용 코드를 제거한다.

- `CouponIssueService.issueWithoutLock()`
- 무락 초과 발급을 기대하는 동시성 테스트
- 세 가지 구현을 설명하는 주석과 무락 전용 결과 집계

무락 테스트는 원하는 동작을 보호하지 않고, 스케줄링에 따라 초과 발급이 재현되지 않을 수 있는 확률적 테스트다. 이미 확인한 초과 발급 문제와 이전 실행 결과는 설계·성능 보고서와 Git 이력으로 보존한다. 운영 코드나 CI에 의도적으로 잘못된 경로를 남기지 않는다.

## 관측 지표

쿠폰 ID나 회원 ID처럼 cardinality가 커지는 값은 metric tag로 사용하지 않는다.

- 발급 전체 지연: strategy=`lock|lua`, result=`success|sold_out|duplicate|not_issuable|timeout|error`
- 분산 락 획득 대기 시간과 타임아웃 수
- Lua 실행 시간과 반환 결과별 요청 수
- DB 발급 트랜잭션 시간과 실패 수
- Redis 보상 성공·실패 수
- HTTP RPS와 p50, p95, p99
- DB 커넥션 풀 사용량과 쿼리 수

HTTP 지연은 `201`을 반환할 때까지의 DB 완료 시간을 포함한다. 이후 비동기 방식의 `202` 접수 지연과 직접 비교하지 않는다.

## 성능 비교 규칙

- 동일한 애플리케이션 빌드와 인프라에서 한 전략씩 측정한다.
- 동일한 할인 규칙, 발급·사용 기간, 재고를 가진 별도 쿠폰 ID를 사용한다.
- 같은 쿠폰에 락 요청과 Lua 요청을 섞지 않는다.
- 같은 회원 모집단과 부하 패턴을 사용한다.
- 각 실행 전 DB와 Redis 상태를 초기화하고 Lua 쿠폰만 준비 API를 호출한다.
- 연결과 JIT 워밍업 이후 측정하며, 실행 순서를 교차해 순서 편향을 줄인다.
- 성공, 매진, 중복, 락 타임아웃, 시스템 오류를 별도로 집계한다.
- 처리량뿐 아니라 p99, 정합성 불변식, DB 부하와 락 경합을 함께 비교한다.

재고보다 요청 수가 큰 선착순 부하에서 분산 락은 매진 요청도 락 획득 후 DB를 확인하지만, Lua는 Redis에서 즉시 거절한다. 이는 비교에서 제거할 잡음이 아니라 두 동시성 제어 방식의 핵심 특성이다.

## 검증 계획

### 단위 및 통합 테스트

- 발급 기간과 사용 기간이 독립적으로 적용된다.
- 준비 API는 신규 유한 수량 쿠폰을 한 번만 준비한다.
- 준비 API 재호출과 진행 중 재초기화를 거부한다.
- Lua는 미준비, 비활성, 시작 전, 종료 후, 중복, 매진을 구분한다.
- DB 저장 실패 시 Redis 회원과 재고가 한 번만 복구된다.
- 보상 Lua를 반복 호출해도 재고가 추가 증가하지 않는다.
- 분산 락과 Lua 각각에서 동시 요청 후 발급 수가 한도를 넘지 않는다.
- 같은 회원의 동시 중복 요청은 한 건만 발급된다.
- `member_coupon`, `issued_quantity`, Redis 재고의 불변식을 검증한다.
- 두 사용자 API가 DB 커밋 이후에만 `201 Created`를 반환한다.

### 부하 테스트

분산 락과 Lua URL을 별도 시나리오로 실행하되 부하 모델과 임계값은 공유한다. 결과에는 요청 수, 재고, 애플리케이션 인스턴스 수, DB·Redis 위치, 워밍업 시간과 측정 시간을 함께 기록해 재현 가능하게 한다.

## 측정 후 정리

성능 비교가 끝나고 Lua를 운영 방식으로 확정하면 다음을 제거한다.

- `/issue/lock` API
- `CouponLockIssueService`
- `CouponLockManager`
- 분산 락 전용 테스트와 지표

`/issue/lua`는 `/issue`로 변경하고 Lua 발급 서비스는 운영 기본 구현으로 남긴다. Redis Stream 비동기화는 동기 Lua 측정에서 DB 저장이 실제 병목으로 확인된 뒤 별도 설계와 측정으로 진행한다.

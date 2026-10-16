# 쿠폰 발급 구조와 비교 기준

발급 방식은 MySQL unit 재고와 `REPEATABLE_READ`를 사용하는 대리 키 방식으로 확정했다.
V31부터 조건부 UPDATE·RC·복합 키 발급 코드와 발급 비교 API를 제거한다.
[현재 발급 구조와 V31 전환](../coupon-issuance-operations.md),
[AWS 비교 결과](results/coupon-aws-2026-10-11/README.md),
[로컬 검증 요약](coupon-skip-locked-local-results.md)을 참고한다.

## 발급과 재고

- 새 쿠폰은 양의 유한 수량만 허용한다. 과거 nullable 수량은 마이그레이션 호환용이다.
- `coupon_issue_unit`의 PK는 `id`, `(coupon_id, unit_no)`는 UNIQUE다.
- 쿠폰을 `FOR SHARE NOWAIT`로 읽고 준비·활성·발급 기간을 확인한다.
- `FOR UPDATE SKIP LOCKED`로 unit을 확보한 뒤, unit DELETE와 `member_coupon` INSERT를 한 트랜잭션으로 커밋한다.
- 공유 발급 카운터 없이 재고 행을 소비하며, V31에서 `issued_quantity` 컬럼을 제거한다.
- `UNIQUE(member_id, coupon_id)`와 전체 롤백으로 중복 발급·재고 유실을 막는다.
- 빈 SKIP LOCKED 결과는 새 스냅샷에서 확인한다. 재고 없음은 `CP002`, 재고가 남은 경합은 재시도 가능한 `CP012`로 구분한다.

## 준비·수량 변경

관리 작업은 쿠폰 행을 먼저 `FOR UPDATE`로 잠근다. 시작 전이며 발급 내역이 없을 때만 허용한다.
최초 준비는 unit 생성과 `stock_prepared_at` 기록을 함께 커밋한다.
시작 후 증감·재입고를 허용하지 않고, 준비 재호출로 소비된 재고를 복원하지 않는다.

| 상태 | 목표 수량 M으로 변경 |
|---|---|
| 미준비 | 총수량만 변경 |
| 준비 완료·증량 | N+1~M번 unit 추가와 총수량 변경을 함께 커밋 |
| 준비 완료·감량 | `unit_no > M` 삭제와 총수량 변경을 함께 커밋 |
| 폐기 | 시작 전·발급 내역 없음을 확인하고 미발급 unit 정리 |

변경 건수가 기대와 다르거나 작업이 실패하면 전체를 롤백한다.

## 종료된 비교 실험

아래 계약은 V30 당시의 발급 비교 실험에 해당한다. 재현은
[과거 실행 가이드](../../load-test/k6/coupon/MEASUREMENT.md)에 기록된 소스·이미지와 별도 V30 DB를 사용한다.
현재 앱에서는 아래 전략별 발급 API와 조회 비교 API, 실험 전용 프로필을 모두 제거했다.

| 식별자 | 재고 방식 | 격리 수준 |
|---|---|---|
| `db` | 조건부 UPDATE·공유 카운터 | RR |
| `skip_surrogate_rr` | 대리 키 unit | RR |
| `skip_surrogate_rc` | 대리 키 unit | RC |
| `skip_composite_rr` | 복합 키 unit | RR |
| `skip_composite_rc` | 복합 키 unit | RC |

- 쿠폰 생성 시 전략을 고정하고 `/api/v2/coupons/{couponId}/issue/{strategy}`에서 같은 전략만 허용한다.
- 토큰 검증은 업무 트랜잭션 밖에서 수행한다. 전략 일치는 별도 라우팅 조회 없이 업무 조회 결과로 검증한다.
- 조건부 UPDATE 기준선의 첫 DB 연산은 UPDATE다. SKIP LOCKED의 공유 잠금을 공통 전처리로 추가하지 않는다.
- 비교할 때 앱·데이터·부하 조건을 고정하고 후보별 실제 풀 격리 수준을 기록한다.
- 준비는 bounded JDBC 다중 INSERT, 발급 unit 조회·삭제는 Spring Data JPA native query를 사용한다.

## 스키마 전환

V29는 MySQL unit과 불변 발급 전략을 도입하고 준비 시각 컬럼을 변경한다.
구 Lua writer를 중단하고 기존 Redis 준비값·회원 쿠폰·캠페인 상태를 확인한 뒤 전환한다.
기존 Redis 준비 시각만으로 MySQL 재고 준비를 판정하거나 발급 중인 캠페인을 자동 재준비하지 않는다.
V29 이후 Lua 바이너리로 되돌리지 않는다.

V30은 쿠폰 업무 시각을 UTC로 변환한다. 이전 reader/writer를 중단하고 함께 전환하는 절차는
[쿠폰 시간 계약](../coupon-time-contract.md)에 정리했다. 기존 마이그레이션은 수정하지 않는다.

V31은 구버전 reader/writer 정지 후 남은 복합 키 재고만 대리 키 테이블로 옮기고, 복합 키 테이블과
`issue_strategy`·`issued_quantity` 컬럼을 제거한다. 활성 조건부 UPDATE 캠페인은 먼저 종료해야 한다.
소비된 재고를 재생성하지 않으며, 전환 후 이전 바이너리로 되돌리지 않는다.

## 검증 근거

실제 MySQL 테스트로 동시 발급, 마지막 재고 경합, 중복 요청, 준비·수량 변경의 롤백을 검증한다.
성능 수치의 환경·무효 실행·판정 변경은 각 결과 보고서에 남긴다.
이전 구현 계획과 실행 기록은 [보관본](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/coupon-skip-locked-benchmark-plan.md)에서 확인할 수 있다.

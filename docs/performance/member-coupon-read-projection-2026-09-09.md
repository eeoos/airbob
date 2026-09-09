# 내 쿠폰 목록 조회 컬럼 축소

## 대상과 판단

`GET /api/v1/members/me/coupons`에서 `MemberCoupon`과 `Coupon` 엔티티 전체를
읽던 조회를 11개 필드의 `MemberCouponProjection`으로 바꿨다. 한 번의 SQL과
기존 응답 JSON은 유지한다.

프론트는 지정된 `codex/editable-booking-review` 작업트리에서 확인했다.
원격 main을 다시 fetch한 결과 `f80bc095`이며 작업트리 HEAD `6542dd7a`에
이미 포함되어 있었다. 프론트 수정은 필요하지 않았다.

[앞선 쿠폰 계약 정렬](coupon-read-contract-alignment-2026-09-08.md)에서
발급 캠페인과 보유 쿠폰을 구분하는 화면 동작을 맞췄다. 이번에는 그때 유지했던
보유 목록의 엔티티 로딩을 줄였다. 캠페인 조회 SQL과 두 API의 분리는 유지한다.
숙소 상세는 두 목록을 조립하고, 예약 내용 수정에서는 보유 쿠폰만 필요하다.
캠페인의 매진·발급 종료가 이미 보유한 쿠폰의 사용 가능 여부를 결정하지 않는다.

## 프론트 소비와 선택 필드

| 정보 | 사용처 및 판단 |
|---|---|
| coupon_id | 카드 선택, 캠페인·보유 목록의 중복 제거, 견적에 적용할 쿠폰 식별 |
| name | 쿠폰 이름, 접근성 이름, 예약의 할인 내역 |
| discount_type·discount_value | 정액·정률 할인 표시와 예상 할인액 계산 |
| min_payment_price·max_discount_amount | 적용 가능 금액과 정률 할인 상한 계산 |
| usable_from·usable_until | 한국 시간 기준 사용 기간 표시 |
| status | 사용 가능 여부, 버튼 활성화, 사용 예정·완료·만료·불가 표시 |
| description | API 어댑터와 모델이 보존하지만 현재 쿠폰 카드에 직접 표시하지 않음 |

응답용 값 9개와 상태 계산에 필요한 `used`, `is_active` 2개를 선택한다.
`description`은 기존 응답 계약을 유지하기 위해 포함했으며 화면 필수 표시
정보라고 판단한 것은 아니다. 상태는 별도 컬럼이 아니라 읽은 값과 한 번 구한
`CouponTimeProvider.now()`로 계산한다. 보유 목록에서 Redis 재고 조회는 하지 않는다.

`member_coupon.coupon_id`는 NOT NULL 외래 키이므로 쿠폰을 JOIN해 한 번에
읽을 수 있다. 회원 ID로 제한하고 보유 행의 `created_at DESC, id DESC` 순서를
유지한다. 정렬 키와 소유권 키는 SQL에서 사용하며 응답용으로 선택할 필요는 없다.
쿠폰 ID와 보유 행 ID를 혼동하지 않도록 서로 다른 순서의 fixture로 검증했다.

## 상태 및 변경 경계

상태 판정을 `MemberCouponStatus.resolve`에 모아 엔티티의 기존 판정과 새 DTO
변환이 같은 규칙을 사용하게 했다. 우선순위는 다음과 같다.

1. 이미 사용했으면 `USED`.
2. 사용 종료 시각에 도달했으면 `EXPIRED`.
3. 비활성 쿠폰이면 `UNAVAILABLE`.
4. 사용 시작 전이면 `UPCOMING`.
5. 그 외에는 `AVAILABLE`.

사용 시작은 포함하고 종료는 제외한다. 엔티티의 `USED` 판정은 기존처럼 쿠폰
정책의 지연 로딩을 요구하지 않는다. 목록의 기간·상태 조건으로 보유 행을
걸러내지 않으며, 발급 기간·수량·Redis 준비 여부를 사용 상태에 섞지 않는다.

견적과 checkout의 할인 검증, 쿠폰 정책 잠금, 사용·복원·재사용 UPDATE는
변경하지 않았다. 인덱스·스키마·ES 검색도 이번 범위에 포함하지 않는다.

## 측정과 검증

MySQL 8.4.11 Testcontainers에서 실제 서비스와 저장소를 호출했다. 조회 전에
영속성 컨텍스트와 Hibernate 통계, SQL 기록을 비웠다. 인증 필터의 DB 읽기는
측정에 포함하지 않는다.

| 측정 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 선택 컬럼 | 29 | 11 |
| 보유 6건 fixture의 엔티티 로딩 | 12 | 0 |
| SQL 횟수 | 1 | 1 |
| 보유 목록이 비어 있을 때 SQL 횟수 | 1 | 1 |

변경 전 12개 엔티티는 보유 행 6개와 쿠폰 6개다. 새 통합 테스트 14개는 변경 전에도
응답·상태·정렬·소유권 검증을 통과했다. 변경 후에는 SQL 1회, 11개 컬럼, 엔티티
0개도 검증한다. 프론트 공유 JSON, 다섯 상태, 마이크로초 단위 기간 경계와 상태
우선순위, 회원별 소유권과 빈 목록, 동일 발급 시각 정렬, 사용→복원→재사용 및
최신 할인 정책 재조회를 포함한다.

- 백엔드: `./gradlew test --tests '*MemberCouponReadIntegrationTest' --tests '*CouponQueryRepositoryTest' --tests '*CouponResponseJsonTest' --tests '*CouponQueryServiceTest' --tests '*CouponControllerTest' --tests '*MemberCouponTest' --tests '*CouponTest' --tests '*CouponUsageServiceTest' --tests '*CouponRestoreTest'`
  — 46개 통과, 실패·오류·건너뛰기 없음.
- 프론트: `npm run test:ci -- src/features/accommodations/detail src/screens/accommodation-detail src/screens/reservation-confirm`
  — 32개 파일, 255개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/reservation-payment-characterization.spec.ts --grep "coupon"`
  — 4개 통과. 보유 쿠폰 적용, 매진 보유 쿠폰의 사용, CP003 이후 상태 재확인,
  저장 전 만료 시 기존 견적 보존을 확인했다.
- 두 저장소의 `member-coupons.json`, `coupon-campaigns.json` 내용 일치 확인.

브라우저 검증은 공유 계약의 모의 HTTP 응답을 사용하며 실제 백엔드 연결이나
실제 결제를 검증한 것은 아니다. 측정한 개선은 선택 컬럼과 엔티티 로딩 감소다.
운영 응답 시간이나 실행계획 개선 수치는 아직 측정하지 않았다.

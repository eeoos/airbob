# 발급 쿠폰 목록 조회 컬럼 축소

## 범위와 판단

`GET /api/v1/coupons`의 전체 `Coupon` 엔티티 조회를 13개 필드의
`CouponCampaignProjection`으로 바꿨다. SQL 1회와 응답 JSON은 유지한다.
[내 쿠폰 목록 개선](member-coupon-read-projection-2026-09-09.md)에 이어
별도 발급 목록에 남아 있던 엔티티 로딩을 정리한 단계다.

프론트는 `codex/editable-booking-review` 작업트리에서 확인했다. 원격 main을
다시 fetch한 결과 `f80bc095`였으며 작업트리 HEAD `6542dd7a`에 이미 포함되어
있었다. 현재 프론트 소비와 계약이 맞아 프론트 코드는 수정하지 않았다.

| 응답 정보 | 현재 프론트 사용 |
|---|---|
| id·name | 발급 대상 식별, 보유 목록과 중복 제거, 카드 이름·접근성 이름 |
| discount_type·discount_value | 할인 표시 및 예상 할인액 계산 |
| min_payment_price·max_discount_amount | 금액 조건과 정률 할인 상한 계산 |
| issue_start_at·issue_end_at | 한국 시간 기준 발급 기간 표시 |
| usable_from·usable_until | 발급 기간과 구분한 사용 기간 표시 |
| total_quantity·issued_quantity | 유한 수량일 때 남은 수량 표시 |
| issuance_status | 발급받기·발급 예정·매진 버튼과 활성 여부 |
| description | API 어댑터와 모델에서 보존하며 현재 카드에는 직접 표시하지 않음 |

`description`은 기존 응답 계약을 유지하기 위해 포함했다. 상태를 제외한
13개 값만 선택하며 상태는 조회한 값과 요청 기준 시각으로 계산한다.
활성 여부와 Redis 재고 준비 시각은 필터에만 필요하고 감사 컬럼은 필요하지 않아
결과로 읽지 않는다. 수량 표시는 기존처럼 MySQL의 총수량·발급수 기반이다.

## 보존한 조회·판정 규칙

- 활성 상태, Redis 재고 준비 이력 있음, 발급 종료 시각이 아직 지나지 않음이라는
  세 조건을 유지한다. 발급 예정·매진 쿠폰도 목록에 포함한다.
- `issue_start_at DESC, id DESC` 정렬을 유지한다. 사용 기간으로 발급 목록을
  추가 필터링하지 않는다.
- `CouponRedisStockManager.currentEpochMillis()`를 요청마다 한 번 호출하고,
  실제 `CouponTimeProvider`가 이를 한국 시간으로 변환한다. 이 시각을 SQL의
  종료 조건과 모든 행의 발급 상태 계산에 함께 사용한다.
- 발급 시작 전이면 `UPCOMING`이 우선한다. 시작 후 유한 수량의 발급수가 한도
  이상이면 `SOLD_OUT`, 나머지는 `OPEN`이다. 총수량 null은 무제한이며 0과 구분한다.
- 시작 시각을 포함하고 종료 시각에 도달한 캠페인은 SQL에서 제외한다.
  기존 판정을 `CouponIssuanceStatus.resolve`로 모아 엔티티와 DTO 변환이 공유한다.

발급 목록과 보유 쿠폰 목록은 서로 다른 상태를 제공하므로 분리를 유지한다.
실제 발급의 Lua 재고 처리와 DB 트랜잭션, 쿠폰 정책 잠금, 보유 쿠폰 사용·복원,
인덱스·스키마·ES 검색은 변경하지 않았다.

## 측정과 검증

MySQL 8.4.11 Testcontainers에서 실제 서비스와 저장소를 호출하고, 조회 전에
영속성 컨텍스트·Hibernate 통계·SQL 기록을 비웠다. Redis 시각 반환값은 경계
검증을 위해 고정하고 시간대 변환은 실제 `CouponTimeProvider`를 사용했다.
따라서 아래 SQL 횟수는 인증 필터와 Redis 통신을 포함하지 않는다.

| 측정 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 선택 컬럼 | 19 | 13 |
| 캠페인 3건 fixture의 엔티티 로딩 | 3 | 0 |
| SQL 횟수 | 1 | 1 |
| 빈 목록의 SQL 횟수 | 1 | 1 |
| 서비스의 Redis 시각 조회 호출 수 | 1 | 1 |

새 통합 테스트 12개는 변경 전 응답과 동작을 먼저 검증했다. 변경 후에는
13개 컬럼·엔티티 0개도 검증한다. 세 상태, 발급 예정과 매진의 우선순위,
무제한·0·한도 초과 수량, 활성·준비·종료 필터, 시작·종료의 밀리초 경계,
시작시각 동점의 ID 역순, 빈 목록, 발급 수 증가 및 할인 정보 재조회를 포함한다.

공유 JSON fixture는 유지했다. 기존 fixture의 같은 시작시각인 ID 11·12 두 행은
통합 테스트에서 기존 SQL 정렬인 12→11 순서로 기대값을 구성하며,
각 응답 필드와 실제 조회 순서를 모두 확인한다.

- 백엔드: `./gradlew test --tests '*CouponCampaignReadIntegrationTest' --tests '*MemberCouponReadIntegrationTest' --tests '*CouponQueryRepositoryTest' --tests '*CouponResponseJsonTest' --tests '*CouponQueryServiceTest' --tests '*CouponControllerTest' --tests '*CouponTest' --tests '*CouponTimeProviderTest' --tests '*CouponIssueTransactionServiceTest' --tests '*CouponLuaIssueServiceTest'`
  — 73개 통과, 실패·오류·건너뛰기 없음. 기존 보유 목록과 발급 서비스도 함께 검증했다.
- 프론트: `npm run test:ci -- src/features/accommodations/detail src/screens/accommodation-detail src/screens/reservation-confirm`
  — 32개 파일, 255개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/reservation-payment-characterization.spec.ts --grep "coupon"`
  — 4개 통과. 발급 예정·매진 표시, 보유 쿠폰 적용, CP003 뒤 상태 재확인,
  만료 시 기존 견적 보존을 확인했다.
- 두 저장소의 `coupon-campaigns.json`, `member-coupons.json` 내용 일치 확인.

브라우저는 공유 계약의 모의 HTTP 응답을 사용하며 실제 백엔드·Redis·결제와
연결한 종단 검증은 아니다. 확인한 개선은 선택 컬럼과 엔티티 로딩 감소이며,
운영 응답 시간과 실행계획 개선은 별도로 측정해야 한다.

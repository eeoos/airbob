# 쿠폰 조회·소비 계약 정렬

대상은 GET /api/v1/coupons, GET /api/v1/members/me/coupons와 두 응답을 사용하는
숙소 상세 및 예약 내용 수정 화면이다. 프론트는 codex/editable-booking-review에서 작업했다.

## 계약과 수정

기존 프론트는 발급 캠페인을 사용 가능한 쿠폰으로 취급하고 start_date/end_date를
읽었다. 백엔드는 발급 기간과 사용 기간을 따로 반환하며, 매진/발급 예정 캠페인도
노출한다. 이미 발급했다는 CP003 응답 역시 아직 사용 가능하다는 뜻은 아니다.

프론트는 캠페인과 보유 쿠폰을 별도 조회·캐시한다. 같은 coupon ID가 있으면 보유
쿠폰을 우선 표시하여 중복 카드를 없앤다. 캠페인이 매진되거나 발급 종료로 목록에서
사라져도 보유 쿠폰의 사용 가능 여부는 유지한다.

| 입력 | 화면 동작 |
|---|---|
| 캠페인 OPEN | 발급 가능. 발급 성공 또는 CP003 이후 보유 상태를 다시 조회 |
| 캠페인 UPCOMING / SOLD_OUT | 발급 예정 / 매진 표시, 발급 버튼 비활성 |
| 보유 AVAILABLE | 최소 금액과 할인 상한을 반영해 적용 가능. 재발급 POST 없음 |
| 보유 UPCOMING / UNAVAILABLE / USED / EXPIRED | 사용 예정 / 사용 불가 / 사용 완료 / 기간 만료 표시, 적용 비활성 |
| 적용 버튼 클릭 시 상태가 바뀜 | 보유 쿠폰 재조회 결과로 확인하고 적용 차단 |
| 예약 내용 수정 중 상태가 바뀜 | 저장 직전 보유 상태를 다시 조회. 실패하면 기존 확정 견적을 보존 |

사용 가능 여부를 브라우저 날짜로 다시 계산하지 않는다. 기간은 백엔드
CouponTimeProvider의 Asia/Seoul 기준으로 표시하고 상태는 서버 판정을 사용한다.
금액 계산은 화면 예비 계산이며 실제 견적·checkout 검증을 대체하지 않는다.
계정 전환이나 이전 화면의 늦은 응답은 쿠폰 선택에 반영하지 않는다.

## 조회 구조

두 API는 발급 캠페인과 개인 소유권·사용 상태라는 서로 다른 정보를 제공하므로
분리를 유지한다. 숙소 상세에서는 두 목록을 조립하고, 예약 내용 수정에서는 보유
쿠폰만 읽는다. 적용 시 보유 상태 재조회, 발급 후 캠페인/보유 목록 갱신, 화면
재진입 및 명시적 새로고침을 사용한다. 주기적 polling은 추가하지 않는다.

백엔드의 조회 SQL·DTO·인덱스는 변경하지 않았다. 대신 다음을 검증했다.

- [JSON 계약 테스트](../../src/test/java/kr/kro/airbob/domain/coupon/dto/CouponResponseJsonTest.java):
  캠페인 3상태와 보유 5상태, 사용 시작 포함/종료 제외 경계, 매진·발급 종료와 사용 가능 여부의 독립성.
- [MySQL 조회 테스트](../../src/test/java/kr/kro/airbob/domain/coupon/repository/CouponQueryRepositoryTest.java):
  매진 캠페인은 노출되고 발급 종료 후에도 보유 목록은 조회됨. 기존 소유권/정렬 검증 유지.
- 프론트 API/상태/금액/캐시/비동기 명령 테스트 및 Chromium 사용자 흐름 테스트.

공유 fixture는 [캠페인](../../src/test/resources/contracts/coupon-campaigns.json)과
[보유 쿠폰](../../src/test/resources/contracts/member-coupons.json)이다. 프론트
src/features/accommodations/detail/api/__fixtures__와 같은 내용을 유지한다.

브라우저 검증은 실제 프로덕션 빌드와 합성 API 응답으로 수행한다. 구동 중인
백엔드에 연결한 HTTP E2E나 실제 쿠폰 발급·결제를 수행한 결과는 아니다.

## 검증 결과

- 백엔드: JSON 계약, 실제 MySQL 조회, 서비스, 컨트롤러 테스트 11개 통과, 건너뛴 테스트 없음.
- 프론트: 숙소 상세·예약 검토 관련 테스트 31개 파일, 257개 통과.
- Chromium: 보유 쿠폰 적용, CP003 이후 재확인, 만료 시 기존 견적 보존 등 4개 시나리오 통과.
- 앱·테스트·E2E 타입 검사, 변경 파일 lint와 포맷 검사 통과.
- 두 저장소의 쿠폰 JSON fixture 내용 일치 확인.

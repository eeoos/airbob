# 프론트엔드에서 호출하지 않는 백엔드 API

분석일: 2026-09-25. 백엔드 `7ffbad65`, 프론트엔드 `bd75ae81`을 기반으로 한 현재 작업 트리 기준이며, 백엔드의 미커밋 변경도 포함했다.

## 범위와 판정 기준

- 백엔드 `src/main/java`의 컨트롤러에 선언된 HTTP 메서드와 경로를 수집했다. 프레임워크가 제공하는 Actuator, Swagger, 기본 오류 처리 경로는 집계하지 않았다.
- 프론트엔드 `src`의 실제 요청 코드와 화면·쿼리·워크플로 연결을 대조했다. API 설명 문서, 테스트, fixture에만 등장하는 경로는 화면에서의 사용으로 집계하지 않았다.
- 프론트엔드 공통 API 주소 `/api/v1`을 반영했고, 예약 조회의 동적 `guest`/`host` 경로를 각각 펼쳐 비교했다. 경로 변수 이름이 달라도 HTTP 메서드와 경로 구조가 같으면 같은 API로 처리했다.
- 결과는 정적 코드 분석이다. 운영 요청 로그나 별도 클라이언트의 사용 여부를 확인한 결과는 아니다. 프론트 호출이 없다는 사실만으로 삭제 가능하다고 판단하지 않는다.

| 구분 | 백엔드 API | 프론트 호출 코드 있음 | 프론트 호출 코드 없음 |
| --- | ---: | ---: | ---: |
| 일반 서비스 V1 | 55 | 43 | 12 |
| 관리자 V1 | 20 | 0 | 20 |
| 성능시험 V2 | 12 | 0 | 12 |
| 합계 | 87 | 43 | 44 |

## 일반 서비스 API: 12개

아래 경로에는 공통 접두사 `/api/v1`을 붙인다.

| 메서드 | 경로 | 기능 | 현재 프론트 상태 | 백엔드 정의 |
| --- | --- | --- | --- | --- |
| POST | `/reservations/{reservationUid}` | 예약 취소 요청 | 취소 요청 API 및 화면 연결 없음. 사용 중인 `DELETE /reservations/{reservationUid}/hold`와 별개 | [ReservationController.java](../src/main/java/kr/kro/airbob/domain/reservation/api/ReservationController.java#L82) |
| PATCH | `/reviews/{reviewId}` | 리뷰 내용 수정 | 리뷰 조회·작성·이미지 업로드만 연결 | [ReviewController.java](../src/main/java/kr/kro/airbob/domain/review/api/ReviewController.java#L48) |
| DELETE | `/reviews/{reviewId}` | 리뷰 삭제 | 삭제 요청 코드 없음 | ReviewController.java:59 |
| DELETE | `/reviews/{reviewId}/images/{imageId}` | 업로드된 리뷰 이미지 삭제 | 서버 이미지 삭제 요청 코드 없음 | ReviewController.java:79 |
| GET | `/accommodations/{accommodationId}/reviews/summary` | 리뷰 개수·평균 평점 조회 | 숙소 상세 응답의 `review_summary`를 사용 | ReviewController.java:103 |
| PATCH | `/members/wishlists/{wishlistId}` | 위시리스트 이름 수정 | 이름 수정 요청 없음. 숙소 메모 수정 API는 사용 중 | [WishlistController.java](../src/main/java/kr/kro/airbob/domain/wishlist/api/WishlistController.java#L44) |
| GET | `/payments/{paymentKey}` | 결제 키로 결제 조회 | 해당 조회 요청 없음. 결제 흐름은 결제 작업 상태를 조회 | [PaymentController.java](../src/main/java/kr/kro/airbob/domain/payment/api/PaymentController.java#L38) |
| GET | `/payments/orders/{orderId}` | 주문 번호로 결제 조회 | 해당 조회 요청 없음. 예약 상세 화면의 결제 정보는 예약 상세 응답을 사용 | PaymentController.java:45 |
| GET | `/profile/host/settlements` | 호스트 정산 목록 | 정산 API 연결과 정산 화면 없음 | [SettlementController.java](../src/main/java/kr/kro/airbob/domain/settlement/api/SettlementController.java#L31) |
| GET | `/profile/host/settlements/summary` | 호스트 정산 요약 | 동일 | SettlementController.java:41 |
| GET | `/profile/host/settlements/{settlementId}` | 호스트 정산 상세 | 동일 | SettlementController.java:48 |
| GET | `/common-codes/{group}` | 공통 코드 조회 | API 연결 없음. 예를 들어 숙소 유형 선택지는 프론트 상수로 관리 | [CommonCodeController.java](../src/main/java/kr/kro/airbob/domain/commoncode/api/CommonCodeController.java#L27) |

프론트 판단 근거:

- [paymentApi.ts](../../airbob-front/src/features/reservations/payment/api/paymentApi.ts): 결제 시도 발급, HOLD 해제, 결제 승인 요청, 결제 작업 상태 조회를 제공한다. 예약 취소나 결제 키·주문 번호 기반 결제 조회는 제공하지 않는다.
- [reservationReadApi.ts](../../airbob-front/src/features/reservations/api/reservationReadApi.ts), [reservationDetailViewModel.ts](../../airbob-front/src/features/reservations/lib/reservationDetailViewModel.ts): 게스트·호스트 예약 목록과 상세 조회가 연결되어 있으며, 게스트 결제 정보는 예약 상세 응답의 `payment`에서 가져온다.
- [reviewApi.ts](../../airbob-front/src/features/reviews/api/reviewApi.ts): 리뷰 목록, 작성, 이미지 업로드의 세 요청만 제공한다.
- [숙소 상세 응답 변환](../../airbob-front/src/features/accommodations/detail/api/mappers.ts): `review_summary.total_count`, `review_summary.average_rating`을 화면 모델로 변환한다.
- [wishlistApi.ts](../../airbob-front/src/features/wishlist/api/wishlistApi.ts), [wishlistMembershipTransport.ts](../../airbob-front/src/workflows/wishlist-membership/wishlistMembershipTransport.ts): 위시리스트 생성·삭제·조회, 숙소 추가·제거·조회, 메모 수정, 멤버십 확인은 연결되어 있다. 위시리스트 이름 수정은 없다.
- [editorOptions.ts](../../airbob-front/src/screens/accommodation-edit/components/editorOptions.ts): 숙소 유형 코드를 상수로 정의한다.
- [화면 경로 정의](../../airbob-front/src/app/router/definitions.ts): 정산 화면과 관리자 화면이 등록되어 있지 않다.

리뷰 요약 API는 프론트에서는 호출하지 않지만 [게스트 조회 부하 테스트](../load-test/k6/traffic/guest-read.js#L81) 및 [read-model 성능 비교](../load-test/k6/lib/read-model-benchmark.js#L150)에서 사용한다.

## 관리자 API: 20개

현재 프론트에는 `/api/v1/admin/**` 요청 코드가 없다. 아래 경로에는 `/api/v1/admin`을 붙인다.

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| GET | `/common-code-groups` | 공통 코드 그룹 조회 |
| POST | `/common-code-groups` | 공통 코드 그룹 생성 |
| PATCH | `/common-code-groups/{groupCode}` | 공통 코드 그룹 수정 |
| GET | `/common-codes/{group}` | 그룹별 공통 코드 조회 |
| POST | `/common-codes/{group}` | 공통 코드 생성 |
| PATCH | `/common-codes/{group}/{code}` | 공통 코드 수정 |
| POST | `/coupons` | 쿠폰 생성 |
| PATCH | `/coupons/{couponId}` | 쿠폰 수정 |
| DELETE | `/coupons/{couponId}` | 쿠폰 삭제 |
| POST | `/coupons/{couponId}/stock/prepare` | 쿠폰 재고 준비 |
| PATCH | `/members/{memberId}/role` | 회원 권한 변경 |
| GET | `/payment-operations/manual-review` | 수동 확인이 필요한 결제 작업 조회 |
| POST | `/payment-operations/{operationId}/reconciliation` | 결제 작업 재확인 요청 |
| POST | `/payment-operations/{operationId}/mark-not-paid` | 미결제 확정 처리 |
| GET | `/settlements` | 관리자 정산 목록 |
| POST | `/settlements/generate` | 월 정산 생성·재집계 |
| POST | `/settlements/backfill` | 월 구간 정산 백필 |
| POST | `/settlements/{settlementId}/pay` | 정산 지급 처리 |
| GET | `/stats/revenue` | 매출 통계 조회 |
| POST | `/stats/revenue/recompute` | 매출 통계 재집계 |

정의: [CommonCodeAdminController](../src/main/java/kr/kro/airbob/domain/commoncode/api/CommonCodeAdminController.java), [CouponAdminController](../src/main/java/kr/kro/airbob/domain/coupon/api/CouponAdminController.java), [MemberAdminController](../src/main/java/kr/kro/airbob/domain/member/api/MemberAdminController.java), [PaymentOperationAdminController](../src/main/java/kr/kro/airbob/domain/payment/api/PaymentOperationAdminController.java), [SettlementController](../src/main/java/kr/kro/airbob/domain/settlement/api/SettlementController.java), [RevenueStatsController](../src/main/java/kr/kro/airbob/domain/statistics/api/RevenueStatsController.java).

결제 작업 관리자 API는 [결제 운영 문서](payment-operation-runbook.md#L204)에 수동 해결 절차로 명시되어 있다. 관리자 API의 필요성은 프론트 연결 여부와 운영 용도를 함께 검토해야 한다.

## 성능시험 API: 12개

모두 시험용 프로파일에서 활성화되는 API이며 현재 프론트에서 호출하지 않는다. 아래 경로에는 `/api/v2`를 붙인다.

| 메서드 | 경로 | 활성화 프로파일 |
| --- | --- | --- |
| GET | `/accommodations/{accommodationId}` | `read-model-benchmark` 또는 `cache-benchmark` |
| GET | `/accommodations/{accommodationId}/reviews/summary` | `read-model-benchmark` |
| GET | `/members/wishlists` | `read-model-benchmark` |
| GET | `/admin/stats/revenue` | `read-model-benchmark` |
| GET | `/members/recently-viewed` | `nplus1-benchmark` |
| PUT | `/members/recently-viewed/fixture` | `nplus1-benchmark` |
| POST | `/coupons/{couponId}/issue` | `coupon-benchmark` |
| POST | `/admin/benchmarks/bulk-write/wishlist-delete` | `bulk-write-benchmark` |
| POST | `/admin/benchmarks/bulk-write/reservation-history-insert` | `bulk-write-benchmark` |
| POST | `/admin/benchmarks/bulk-write/accommodation-amenity-delete` | `bulk-write-benchmark` |
| GET | `/benchmark/cache-runtime` | `cache-benchmark` |
| POST | `/benchmark/read-model/runtime-assertion` | `read-model-benchmark`와 `traffic-benchmark` 동시 활성화 |

프로파일 외에 대부분의 컨트롤러에는 `benchmark.read-model.enabled` 또는 `benchmark.bulk-write.enabled` 조건도 있다. `cache-runtime` 컨트롤러는 프로파일 조건을 사용한다.

프론트 외 사용 근거: [read-model 비교](../load-test/k6/lib/read-model-benchmark.js), [recently-viewed 비교](../load-test/k6/recently-viewed-nplus1-performance.js), [fixture 준비](../load-test/k6/lib/benchmark-fixture.js), [쿠폰 비교](../load-test/k6/coupon/coupon-benchmark-fixture.js), [bulk-write 비교](../load-test/k6/lib/bulk-write-benchmark.js), [캐시 시험 문서](../load-test/k6/cache/README.md), [runtime assertion 호출](../load-test/k6/read-model/run-aws-read-model-discovery.sh).

## 후속 검토 방향

- **화면 연결 여부를 결정할 8개:** 예약 취소 1개, 리뷰 수정·삭제·이미지 삭제 3개, 위시리스트 이름 수정 1개, 호스트 정산 3개.
- **현재 조회 방식과 역할을 정리할 3개:** 결제 조회 2개와 리뷰 요약 조회 1개. 리뷰 요약은 기존 성능시험 소비자가 있으므로 그 용도를 함께 기록한다.
- **데이터 관리 방식을 결정할 1개:** 공통 코드 조회. 프론트 상수를 유지할지 서버 공통 코드를 받아올지 결정한다.
- **운영·시험 용도로 분류할 32개:** 관리자 20개와 성능시험 12개. 프론트 미사용 목록에는 포함하되, 서비스 화면 미구현 항목과 구분한다.

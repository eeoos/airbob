# 호스트 예약 상세 결제 조회 축소

## 범위와 소비 근거

- 대상: `GET /api/v1/profile/host/reservations/{reservationUid}`의 결제 하위 응답.
- 프론트 작업트리: `codex/editable-booking-review`. 작업 시작 때 원격 main을 다시 가져왔으며,
  `origin/main`의 `f80bc095`는 이미 작업 브랜치에 포함되어 있었다.
- `hostReservationDetailViewModel.ts`는 결제 존재 여부와 `totalAmount`만 사용한다.
  `HostReservationDetailScreen.tsx`는 숙박 일수와 **최초 결제 금액**을 표시한다.
- 호스트 화면은 결제 키, 주문 번호, 결제 수단·상태·시각, 잔액, 취소 내역을 소비하지 않는다.

## 변경

`HostDetail.payment`를 `PaymentResponse.HostPaymentInfo`로 분리했다.
결제 응답은 `{ "total_amount": 100001 }` 또는 `null`이다.
프론트의 호스트 wire 타입, 화면 모델, mapper, 공유 계약 fixture와 브라우저 fixture를 맞췄다.
게스트의 `PaymentInfo` 및 취소 내역 계약은 유지한다.

호스트 소유 조건으로 예약을 조회한 후 그 예약의 내부 ID로 `Payment.amount`만 조회한다.
기존 결제 엔티티 전체 조회와 취소 거래 원장 조회를 이 경로에서 제거했다.
결제 조회가 예약 UID를 다시 찾기 위한 reservation 조인도 하지 않는다.

`amount`는 최초 결제 금액이다. 부분·전액 취소 후에도 `balanceAmount`나 예약 금액으로 대체하지 않는다.
결제가 없으면 `null`, 기록된 0원 결제이면 `{ "total_amount": 0 }`으로 구분한다.
타인의 예약은 결제를 조회하기 전에 기존의 조회 불가 응답으로 끝난다.

## 측정

MySQL 8.4.11 Testcontainers에서 영속성 컨텍스트와 Hibernate 통계를 초기화한 뒤,
`ReservationTransactionService` 호출 중 실행된 SQL을 측정했다.
수정 전 `b2d1275e` 구현에 동일한 전액 취소 fixture를 넣어 기준값을 확인했다.

| 항목 | 수정 전 | 수정 후 |
| --- | ---: | ---: |
| 결제가 존재하는 호스트 상세 SQL 수 | 3 | 2 |
| 결제 조회의 SELECT 컬럼 수 | 14 | 1 |
| 호스트 상세의 취소 원장 SQL 수 | 1 | 0 |
| 결제가 없는 호스트 상세 SQL 수 | 2 | 2 |
| 소유자가 아닌 사용자의 호스트 상세 SQL 수 | 1 | 1 |

수정 후에는 `Payment`와 `PaymentTransaction` 엔티티를 적재하지 않는다.
게스트 상세는 결제·취소 이력이 있는 경우 기존처럼 SQL 3회로 전체 취소 내역을 반환한다.
이 수치는 서비스 조회 SQL 기준이며 HTTP 인증 조회, 운영 지연 시간, 처리량 측정은 포함하지 않는다.

## 검증

- 백엔드 57개 통과: `ReservationPaymentReadQueryIntegrationTest` 11개,
  `ReservationResponseTest` 7개, 예약 저장소·트랜잭션 관련 테스트 39개.
- 결제 완료, 부분 취소, 전액 취소, 결제 없음, 기록된 0원 결제, 소유권 거부를 검증했다.
  금액 한 컬럼 조회, 추가 예약 조인 및 거래 원장 조회 없음도 실제 SQL로 검증했다.
- 게스트의 최초 금액·잔액·취소 내역 순서와 과거 원장 보존 회귀 검사를 통과했다.
- 프론트 예약 기능·상세 화면 테스트 292개, 관련 브라우저 테스트 9개 통과.
  서울·뉴욕 브라우저 시간대의 숙박 일수와 최초 금액, 데스크톱·모바일 기존 스냅샷을 확인했다.
- 프론트 타입 검사, E2E 타입 검사, strict lint, E2E lint, 미사용 코드 검사 통과.
- 백엔드와 프론트의 `host-reservation-stay-payment.json` 내용이 일치한다.
  브라우저 검사는 해당 응답 fixture를 사용하며, 실행 중인 백엔드와 연결한 E2E는 아니다.

## 쿼리 분리 판단과 남은 범위

이번 단계에서는 화면에서 쓰지 않는 취소 내역 조회를 제거하고 호스트 결제 계약을 확정했다.
예약 본문은 기존 엔티티 fetch join 조회를 사용하므로, 필요한 컬럼만 읽는 작업은 남아 있다.
결제 금액은 예약당 최대 한 건이어서 예약 본문의 DTO projection을 도입할 때
선택적 결제 LEFT JOIN으로 SQL 한 번에 합치는 것이 다음 검토 대상이다.
현재의 두 쿼리 분리가 더 빠르거나 최종적으로 적절하다고 결론 낸 것은 아니다.

게스트의 취소 내역은 여러 건이므로 본문과 무조건 합치면 예약·결제 컬럼이 반복된다.
호스트에서 사용하지 않는다는 이유만으로 게스트 내역을 삭제하거나 같은 형태로 축소하지 않는다.
결제 쓰기·취소 처리, DB 스키마·인덱스, ES 검색은 이번 변경 대상이 아니다.

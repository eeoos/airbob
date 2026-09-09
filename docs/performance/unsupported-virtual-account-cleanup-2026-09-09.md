# 미지원 가상계좌 경로와 예약 상세의 불필요한 조회 정리

현재 사용자 결제 경로는 Toss Payments V2에 `method: "CARD"`를 요청한다.
가상계좌 발급 서비스에는 운영 호출자가 없었고, 이를 노출하는 API나 입금 완료를
예약에 반영하는 사용자 흐름도 연결되어 있지 않았다. 프론트 main을 다시 fetch했으며
기준은 기존에 병합한 `f80bc09`와 동일했다.

이전 감사의 F08은 원장 전체 조회에서 최신 발급 한 건만 고르는 최적화 후보였다.
기능 지원 여부를 확인한 결과, 이번에는 미사용 발급 경로와 그 경로를 위한 조회를
제거하는 것으로 범위를 정정했다.

## 백엔드 변경

- `VirtualAccountService`, 전용 발급 요청 DTO·오류 코드, Toss 어댑터의 발급 메서드와
  관련 상수를 제거했다. 다른 승인·조회·취소 메서드와 오류 분류는 유지한다.
- 신규 발급 원장을 만드는 `PaymentTransaction.virtualIssued`와 그 전용 변환 함수를
  제거했다. 사용하지 않게 된 서비스 테스트도 제거하고, 원장 시각 보존 테스트는
  현재 PG 승인 메타데이터 저장 경로를 검증하도록 바꿨다.
- 게스트 예약 상세에서 `Payment`가 없으면 `payment: null`을 반환한다.
  PAYMENT_PENDING/PAYMENT_PROCESSING 상태에서 원장을 추가로 조회하던 분기와
  해당 전용 repository 메서드를 제거했다.
- `PaymentResponse.PaymentInfo`의 가상계좌 필드·DTO와 발급 원장 기반 변환을 제거했다.
  확정 결제의 금액·잔액·수단·상태·시각과 전체/부분 취소 이력은 그대로 반환한다.

카드 결제 응답에서 `virtual_account`는 원래 null이며 NON_NULL 직렬화 정책으로
생략되었으므로 이 필드 제거로 기존 카드 결제 JSON이 바뀌지는 않는다. 과거 발급 원장만
있는 결제 대기 예약은 이제 입금 계좌 대신 `payment: null`을 반환한다.

## 프론트 변경

`codex/editable-booking-review` 작업트리의 예약 상세에서 가상계좌 모델·변환·은행명
매핑·입금 안내 영역과 전용 스타일을 제거했다. 남아 있는 과거 응답의 추가
`virtual_account` 값도 화면 모델로 복사하지 않는 테스트를 추가했다.

브라우저 검증 데이터는 지원하는 카드 결제 완료 상태로 맞췄다. 모바일 결제 정보
스냅샷 한 장을 갱신하고 카드 수단·금액·승인 시각의 줄바꿈과 표시를 직접 확인했다.
결제 정보가 없는 예약의 기존 대체 화면과 호스트 결제 표시는 유지한다.

## 데이터 보존 범위

운영 DB의 과거 가상계좌 데이터 존재 여부는 직접 조회하지 않았다. 기존 행을 안전하게
읽고 보존할 수 있도록 다음 항목을 유지했다.

- 기존 원장 컬럼과 `VIRTUAL_ISSUED`, `VIRTUAL_ACCOUNT`, `WAITING_FOR_DEPOSIT` 값.
- PG 응답의 상태·수단 해석과 승인 결과에 포함된 가상계좌 메타데이터의 원장 저장.
- 결제·취소 원장, 정산 입력, 모든 마이그레이션과 인덱스.

데이터 삭제나 스키마 변경은 수행하지 않았다. 가상계좌 이력이 있다는 가정의 실제
MySQL 테스트에서도 조회 후 행과 저장 값을 그대로 읽을 수 있는지 확인한다.

## 조회 측정과 검증

현재 저장소의 MySQL 8.4.11 Testcontainers 설정으로 수정 전·후를 측정했다.
영속성 컨텍스트와 Hibernate 통계를 초기화한 게스트 예약 상세 기준이다.

| 조건 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 결제 없는 PAYMENT_PENDING | 예약 상세 + 결제 + 발급 후보 원장: SQL 3회 | 예약 상세 + 결제: SQL 2회 |
| 결제 없는 PAYMENT_PROCESSING | SQL 3회 | SQL 2회 |
| 확정 결제와 취소 이력 | 예약 상세 + 결제 + 취소 이력 | 동일하게 SQL 3회 |

조회 수 감소를 확인했으며 운영 지연 시간이나 처리량 개선율을 측정한 것은 아니다.

- 백엔드: `./gradlew test --tests '*ReservationPaymentReadQueryIntegrationTest' --tests '*ReservationTransactionServiceTest' --tests '*PaymentResponseTest' --tests '*PaymentTransactionTimeTest' --tests '*TossPaymentsAdapterTest' --tests '*PaymentQueryServiceTest' --tests '*PaymentOperationFinalizerIntegrationTest'`
  — 104개 통과, 실패·건너뛰기 없음. 새 MySQL 테스트 5개는 대기/처리 중 조회, 과거 원장
  보존, 전체·부분 취소 내역과 금액 보존, 타인 예약 접근 거부를 확인한다.
- 프론트: `npm run test:ci -- src/features/reservations src/screens/reservation-detail`
  — 25개 파일, 290개 통과.
- 브라우저: `npm run test:e2e:characterization -- reservation-payment-characterization.spec.ts host-reservation-characterization.spec.ts`
  — 34개 통과. 모의 HTTP 응답으로 카드 결제·복구·예약 상세·호스트 표시를 검증한다.
- 운영·테스트·E2E TypeScript, ESLint, 변경 스타일, 미사용 코드 검사를 통과했다.

별도로 진행 중인 MySQL 업그레이드 및 실험 환경 변경은 이 작업의 커밋에 포함하지 않는다.

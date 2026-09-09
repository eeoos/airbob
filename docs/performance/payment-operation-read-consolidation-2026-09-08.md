# 결제 작업 상태 조회 통합

대상은 `GET /api/v1/payment-operations/{operationId}`다. 최신 프론트 main
`f80bc09`를 `codex/editable-booking-review` 작업트리에 충돌 없이 병합했다
(병합 커밋 `bedcb94`). 그 상태에서 응답 소비와 결제 복구 흐름을 대조했다.

## 프론트 소비 판단

현재 프론트는 결제 접수 응답의 작업 ID로 상태를 조회하고, 상태·다음 행동·재조회
간격에 따라 결과 화면과 복구 기록을 갱신한다. 이번 조회 개선을 위해 화면 로직을
변경할 필요는 없었다.

| 응답 값 | 실제 소비와 유지 이유 |
| --- | --- |
| `operation_id`, `order_id` | 요청한 작업·예약과 정확히 일치하는지 검증하고 복구 기록과 연결 |
| `status`, `next_action` | 처리 중·성공·실패·수동 검토 상태 및 새 예약 시작·문의 안내 판단 |
| `retry_after_seconds` | 다음 상태 조회 예약과 재시도 간격 계산 |
| `updated_at`, `server_time` | 시각 형식·순서 검증, 복구 기록의 오래된 응답 배제 |
| `user_failure_code` | 안정된 공개 오류 값으로 프론트 문구와 실패 동작 결정 |
| `failure_code`, `user_message` | 현재 프론트는 의도적으로 폐기. 기존 HTTP 계약은 유지하며 추가 SQL을 유발하지 않음 |

근거는 프론트 `features/reservations/payment/api/mappers.ts`,
`workflows/booking-payment/transaction/recovery/paymentRecovery.ts`,
`workflows/booking-payment/journal/recoveryRecordsValidation.ts`,
`screens/payment-result/PaymentResultController.tsx`다. 프론트 결제 결과 흐름은
승인 작업을 소비하며, 백엔드의 취소 작업 응답도 회귀 테스트로 보존했다.

## 쿼리를 합친 이유와 범위

기존 `PaymentOperationQueryService`는 작업 엔티티를 읽고 요청자를 확인한 뒤,
응답을 생성하면서 지연 로딩된 예약 엔티티를 추가로 읽었다. 예약 UID는 항상
응답에 필요하고, 예약 상태·체크인 시각·hold 만료 시각은 실패 후 다음 행동을
결정하는 데 필요하다.

작업의 `reservation_id`는 NOT NULL 외래 키이며 예약 PK 하나에 연결된다.
따라서 to-one JOIN은 결과 행을 늘리지 않는다. 조회 전용 `PaymentOperationDetailRow`에
작업 7개와 예약 4개 컬럼만 함께 투영하도록 변경했다.

- 작업: UID, 요청 회원 ID, 유형, 상태, 실패 코드, 수정 시각, 다음 시도 시각.
- 예약: UID, 상태, 체크인 시각, hold 만료 시각.

작업 UID로 먼저 조회한 뒤 **작업 요청 회원**을 검사한다. 예약 게스트로 권한 기준을
바꾸거나 소유권을 WHERE 조건에 섞어 미존재와 접근 거부를 같은 오류로 만들지 않았다.
API 경로, 공개 응답 필드, 상태·안내 문구·재조회 간격은 유지했다.

예약의 논리 만료 계산은 기존 `Reservation.effectiveStatus`의 조건을 정적 오버로드로
추출하고 엔티티와 조회 결과 양쪽에서 재사용한다. PAYMENT_PENDING만 만료 시각부터
EXPIRED로 보이며, 이 계산은 저장 상태를 변경하지 않는다. 승인 실패의 재예약 안내와
취소 실패의 체크인 전후 안내도 기존 조건을 유지한다.

작업 실행·잠금·재시도 복구에 쓰는 기존 엔티티 조회 메서드는 유지했다. 이번 변경은
조회 서비스만 새 projection을 사용하며 결제 처리, 재고 소유권, 원장, 관리자 작업,
인덱스 및 마이그레이션을 변경하지 않는다.

## 실제 MySQL 측정

MySQL 8.0.33 Testcontainers에서 같은 작업·예약 데이터로 측정했다. 각 측정 전에
영속성 컨텍스트와 Hibernate 통계를 비웠고 StatementInspector로 SELECT 컬럼 수를
확인했다. 먼저 수정 전 서비스에서 기준 수치를 확인한 뒤, 최종 테스트에서도 기존
엔티티 조회 방식과 projection 응답의 동등성을 비교한다.

| 소유자의 작업 상태 조회 | 기존 | 변경 후 |
| --- | --- | --- |
| SELECT 수 | 2회 | 1회 |
| 선택 컬럼 수 합계 | 작업 30 + 예약 25 = 55개 | 11개 |
| 엔티티 로딩 | 2건 | 0건 |

새 SELECT에는 payment key, provider idempotency key, 실패 원문, FOR UPDATE가
포함되지 않는다. 타인 및 미존재 요청도 SELECT 한 번으로 기존 예외를 반환한다.
위 수치는 조회 구조와 로딩 비용 비교이며 운영 지연 시간·처리량 개선율이나 인덱스
효과를 측정한 결과는 아니다.

## 검증 결과

- 백엔드: `./gradlew test --tests '*PaymentOperationReadQueryIntegrationTest' --tests '*PaymentOperationQueryServiceTest' --tests '*PaymentOperationControllerTest' --tests '*ReservationEffectiveStateContractTest' --tests '*ReservationTest' --tests '*ReservationResponseTest'`
  — 85개 통과, 실패·건너뛰기 없음.
- 새 MySQL 통합 테스트 23개: 승인·취소 × 6개 작업 상태의 응답 동등성, 컬럼·SQL·
  엔티티 로딩 수, 게스트와 작업 요청자가 다른 권한 사례, 미존재, nullable 재시도 시각,
  hold 만료·체크인 시각의 직전·동일·직후 1마이크로초 경계를 검증했다.
- 프론트: `npm run test:ci -- src/features/reservations/payment src/screens/payment-result src/screens/reservation-confirm src/shared/styles/tokens.test.ts src/workflows/booking-payment`
  — 19개 파일, 503개 통과. 최신 main의 예약 확인 테스트도 포함한다.
- 브라우저: `npm run test:e2e:characterization -- reservation-payment-characterization.spec.ts responsive-reflow.spec.ts wishlist-characterization.spec.ts auth-session-characterization.spec.ts`
  — 64개 통과. 최신 main의 반응형 변경과 기존 위시리스트 조회 개선의 병합 결과도
  확인했다. 모의 HTTP 응답 기반이며 실제 PG나 실행 중인 백엔드에 연결한 테스트는 아니다.
- TypeScript와 변경 프론트 테스트의 ESLint·Prettier 검사 통과.
- `contracts/payment-operation-read.json`의 승인 작업 6개 상태 응답은 실제 MySQL
  서비스 결과와 비교하고, 프론트에도 같은 JSON을 두어 API 변환기가 모두 수용하는지
  확인했다.

결제 단독 조회와 가상계좌 원장 후보 조회는 이번 단계의 변경 대상이 아니다.
전체 비-ES 조회의 검토 및 인덱스 튜닝은 아직 완료되지 않았다.

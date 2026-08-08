# ReservationHistory JDBC Batch 전환 설계

- 작성일: 2026-07-21
- 상태: 승인됨
- 대상: 결제 대기 예약 만료 스케줄러의 `ReservationHistory` 저장 경로

## 1. 배경

현재 만료 스케줄러는 하나의 트랜잭션에서 만료된 `PAYMENT_PENDING` 예약을 조회한 뒤, 예약마다 다음 작업을 반복한다.

1. 예약 상태를 `EXPIRED`로 변경한다.
2. `ReservationHistory`를 JPA `save()`로 저장한다.
3. Redis의 예약 hold를 제거한다.

`ReservationHistory.id`가 `IDENTITY` 전략이므로 Hibernate는 각 `save()`에서 생성된 ID를 즉시 받아야 한다. 따라서 대상이 N건이면 history INSERT도 N번 개별 실행된다. Before 측정에서 N=2,000일 때 `SELECT 1 + INSERT 2,000 + UPDATE 2,000`, 총 4,001개 Hibernate statement가 확인됐다.

이번 변경은 만료 스케줄러의 history INSERT만 `JdbcTemplate.batchUpdate()`로 전환한다. 예약 조회와 JPA dirty checking 기반 UPDATE는 유지한다.

## 2. 목표

- 만료 처리의 `ReservationHistory` INSERT N건을 설정 가능한 크기의 JDBC batch로 제출한다.
- 기존 method-wide 단일 DB 트랜잭션과 성공 경로의 비즈니스 결과를 보존한다.
- 기존 `ReservationHistory` 스냅샷 필드와 감사 필드 의미를 보존한다.
- history batch가 모두 성공한 뒤에만 Redis hold 제거를 시작한다.
- 기존 JPA 구현을 benchmark profile 전용 Before 경로에 동결해 같은 fixture로 Before/After를 비교한다.
- Hibernate statement와 JDBC batch 통계를 구분해 기록한다.

## 3. 비목표

- 예약 UPDATE를 JDBC batch 또는 set-based UPDATE로 바꾸지 않는다.
- `INSERT ... SELECT`, `FOR UPDATE SKIP LOCKED`, scheduler claim, chunk별 트랜잭션을 도입하지 않는다.
- Redis 삭제를 after-commit/outbox로 재설계하지 않는다.
- Kafka 단건 만료, 취소, 결제 등 다른 `ReservationHistoryRepository.save()` 경로를 바꾸지 않는다.
- `reservation_history` 스키마에 현재 없는 `discountAmount`를 추가하지 않는다.
- 이번 결과를 스케줄러 전체 확장성 개선으로 표현하지 않는다.

## 4. 검토한 접근법

### 4.1 전용 cleanup service와 JDBC batch writer — 채택

스케줄러는 트리거 역할만 하고, 별도 transactional service가 만료 처리 전체를 수행한다. 스케줄러 전용 writer만 raw JDBC를 사용하므로 다른 이력 경로에 미치는 영향이 가장 작고 Before 구현도 명확하게 격리할 수 있다.

### 4.2 기존 JPA Repository에 batch 기능 추가 — 제외

범용 `ReservationHistoryRepository`에 스케줄러 전용 SQL과 감사 규칙이 섞인다. 다른 단건 저장 경로에서 잘못 사용할 가능성이 생기며 JPA 저장소와 raw JDBC의 책임도 불분명해진다.

### 4.3 `INSERT ... SELECT` 기반 set 처리 — 제외

DB 왕복을 더 줄일 수 있지만 예약 상태 갱신, 실제 갱신된 행과 history 행의 일치, 동시 실행 제어까지 함께 설계해야 한다. IDENTITY INSERT 비교라는 현재 범위를 넘어선다.

## 5. 구조

### 5.1 운영 경로

`ReservationScheduler`는 스케줄 실행과 로그만 담당하고 `ExpiredReservationCleanupService.cleanup()`을 호출한다. `ExpiredReservationCleanupService`의 public 메서드가 Spring `@Transactional` 프록시 경계가 된다.

처리 순서는 다음과 같다.

1. 실행 시각을 cutoff로 한 번 캡처한다.
2. cutoff 이전에 만료된 `PAYMENT_PENDING` 예약을 조회한다.
3. 조회된 모든 관리 엔티티에 `expire()`를 호출한다.
4. 각 예약에서 `ReservationHistory.ofSystem(..., "BATCH")`로 기존과 동일한 스냅샷을 만든다.
5. `ReservationHistoryBatchWriter`가 history를 설정된 batch 크기로 나눠 같은 트랜잭션 connection에서 INSERT한다.
6. 모든 batch가 성공한 경우에만 각 예약의 Redis hold를 제거한다.
7. 메서드 종료 시 JPA dirty checking으로 예약 UPDATE가 flush되고 트랜잭션이 commit된다.

### 5.2 Before 벤치마크 경로

현재 스케줄러의 JPA 반복 로직을 `bulk-write-benchmark` profile 전용 Before service로 이동해 그대로 보존한다. 벤치마크 orchestrator는 요청 variant에 따라 다음 경로를 선택한다.

- `BEFORE`: 동결된 JPA 반복 저장 service
- `AFTER`: 운영 `ExpiredReservationCleanupService`

운영 코드에는 Before/After 선택기가 들어가지 않는다. 자동 scheduler는 benchmark profile에서 계속 비활성화한다.

### 5.3 JDBC writer

`ReservationHistoryBatchWriter`는 다음 책임만 가진다.

- AUTO_INCREMENT `id`를 제외한 `reservation_history` 21개 컬럼 바인딩
- 입력을 `reservation.expiration.history-batch-size` 단위로 분할
- 각 chunk에 대해 `JdbcTemplate.batchUpdate()` 한 번 호출
- 활성화된 `BulkOperationContext`가 있으면 chunk별 제출 행 수, 설정 batch 크기, 알 수 있는 affected rows 기록

스냅샷 값은 기존 `ReservationHistory.ofSystem()`이 만든 객체의 getter에서 읽어 중복 매핑을 피한다. JPA Auditing이 실행되지 않으므로 writer는 다음 값을 명시적으로 바인딩한다.

- `history_created_at`: cleanup 실행 중 캡처한 non-null 시각
- `history_created_by`: `null`
- `change_type`: `STATUS_CHANGE`
- `change_reason`: `결제 시간 초과`
- `source_system`: `BATCH`
- `client_ip`: `null`

`SUCCESS_NO_INFO`가 하나라도 반환되면 affected rows는 `null`로 기록한다. 최종 저장 행 수는 별도 fixture 검증 결과를 근거로 삼는다.

## 6. 트랜잭션과 실패 의미

사용자가 승인한 실패 정책은 다음과 같다.

- history batch 중 하나라도 실패하면 예외를 전파한다.
- 같은 Spring 트랜잭션에 참여한 이전 chunk INSERT와 예약 상태 변경은 모두 rollback된다.
- batch가 모두 성공하기 전에는 Redis hold를 제거하지 않으므로 batch 실패 시 Redis 호출은 0회다.
- Redis hold 제거 실패를 로깅하고 삼키는 기존 `ReservationHoldService` 정책은 유지한다.
- Redis는 DB 트랜잭션 자원이 아니므로 hold 제거 이후 DB commit 자체가 실패하는 불일치 가능성은 이번 범위에서 해결하지 않는다. 기존 TTL을 안전망으로 유지하고 후속 과제로 명시한다.

## 7. 설정

- 기본 history batch 크기: `100`
- 설정 키: `reservation.expiration.history-batch-size`
- 환경 변수 override: `RESERVATION_HISTORY_BATCH_SIZE`
- 0 이하의 batch 크기는 writer 생성자에서 거부해 애플리케이션 시작을 실패시킨다.
- MySQL Connector/J의 `rewriteBatchedStatements`는 batch 크기와 별도 설정이다.
- 각 datasource profile은 Hikari의
  `spring.datasource.hikari.data-source-properties.rewriteBatchedStatements`를
  `JDBC_REWRITE_BATCHED_STATEMENTS` 환경 변수로 설정한다.
- benchmark artifact에는 batch 크기와 rewrite 설정을 함께 기록한다.
- rewrite on/off 측정은 서로 다른 실험으로 취급하며 JDBC batch 호출 수를 실제 네트워크 왕복 횟수라고 표현하지 않는다.

## 8. 모니터링과 비교 기준

After 측정에서도 `server_operation_ms`는 SELECT, history batch, 예약 UPDATE, flush/commit을 포함한 cleanup 트랜잭션 전체 시간이다. history INSERT만의 실행 시간으로 표현하지 않는다.

After의 기대 지표는 다음과 같다.

- Hibernate SELECT: 1
- Hibernate INSERT: 0
- Hibernate UPDATE: N
- JDBC submitted rows: N
- JDBC batch calls: `ceil(N / configuredBatchSize)`
- verified persisted history rows: N
- Redis hold calls: N, 모든 history batch 성공 이후 시작

Before와 After는 동일한 애플리케이션 commit, JVM, MySQL, fixture 규모, warm-up/측정 횟수, datasource 설정에서 다시 측정한다. 벤치마크 orchestrator가 transactional service의 Spring proxy 호출 전체를 monitor로 감싸 commit까지 측정한다.

## 9. 테스트 전략

구현은 TDD로 진행한다.

1. Before service가 기존 SQL 수와 현재 실패 시 hold 호출 의미를 보존하는 테스트
2. writer 입력 `0`, `1`, `batchSize-1`, `batchSize`, `batchSize+1`, 여러 chunk 테스트
3. 기존 history 스냅샷 필드 21개의 의미상 동등성과 감사 필드 테스트
4. After 성공 시 Hibernate INSERT 0, JDBC batch call 수, submitted rows 테스트
5. 두 번째 chunk 실패 시 history와 예약 상태 전체 rollback 및 Redis 호출 0 테스트
6. Redis 장애가 DB 성공을 막지 않는 기존 정책 회귀 테스트
7. scheduler가 cleanup service만 호출하는 얇은 트리거 테스트
8. `AFTER` API validation, 응답, k6 contract와 artifact 테스트
9. 전체 테스트 실행 후 격리된 MySQL/Redis 환경에서 Before/After 재측정

## 10. 완료 조건

- 모든 신규·기존 테스트가 통과한다.
- 운영 scheduler가 After service만 사용하고 Before service는 benchmark profile 밖에서 생성되지 않는다.
- 기존 `ReservationHistory` 스냅샷 필드와 감사 의미가 보존된다.
- 주입한 batch 실패에서 DB 전체 rollback과 Redis 호출 0이 증명된다.
- After artifact가 Hibernate statement와 JDBC batch 수치를 분리한다.
- N=0/100/1,000/2,000 Before/After 원시 결과와 재현 조건을 보관한다.
- 이력서에는 측정된 수치와 허용된 범위의 결론만 사용한다.

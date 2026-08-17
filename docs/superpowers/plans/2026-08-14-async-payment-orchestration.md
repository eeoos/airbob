# Async Payment Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy payment-confirmation choreography with an authenticated, asynchronous, durable `PaymentOperation` workflow that converges after duplicate delivery, worker crashes, Toss response loss, and Kafka quarantine.

**Architecture:** The confirmation API locks and validates a reservation, then commits `PaymentOperation` plus one outbox command. A Kafka worker claims a fenced database lease, calls Toss outside every database transaction, and atomically applies the provider result to payment, ledger, reservation, history, coupon, operation, and terminal outbox facts. A database scheduler republishes stale or due work independently of Kafka retries.

**Tech Stack:** Java 21, Spring Boot 3.5.8, Spring Data JPA/Hibernate, MySQL 8.0, Flyway, Spring Kafka retry topics, Debezium transactional outbox, Spring `RestClient`, JUnit 5, Mockito, AssertJ, Testcontainers, Embedded Kafka.

## Global Constraints

- Implement on branch `refactor/payment-orchestration-foundation`; do not introduce a `codex/` branch.
- Treat the database, outbox, and Kafka topics as empty: use a clean cutover with no backfill, dual publishing, compatibility reader, or feature flag.
- Leave V16 unchanged; add all new schema in `V17__add_payment_operation.sql`.
- Keep `POST /api/v1/payments/confirm` asynchronous and return `202 Accepted` with operation ID, API status, and polling URL.
- Require `@CurrentMemberId Long memberId` on confirmation creation and operation reads; only the reservation guest may create or read an operation.
- Check the reservation payment deadline only when accepting the operation. Once accepted, a correlated Toss approval is valid even when provider latency makes `approvedAt` later than `expiresAt`.
- Publish only `PaymentExecutionRequestedV1(operationUid, reservationUid)` to Kafka; `getId()` returns `reservationUid` so it is the partition key.
- Use topic `PAYMENT_OPERATION.events`, group `payment-operation-execution-group`, retry suffix `.RETRY`, and DLT `PAYMENT_OPERATION.events.DLT`.
- Use a 30-second lease, 10-second scheduler delay, batch size 100, five automatic attempts, 10-second initial retry delay, five-minute maximum retry delay, and 10-second recovery-publication interval.
- Use Toss connect timeout 2 seconds and response timeout 10 seconds; both remain shorter than the 30-second lease.
- Never hold a database transaction, row lock, or Redis lock while calling Toss.
- Use provider idempotency key `airbob-confirm-{operationUid}` for every confirmation retry of one operation.
- Treat only an explicit end-user-decline allow-list as `DECLINED`; response loss, parsing failure, already-processed, unknown 4xx, and unrecognized status become `OUTCOME_UNKNOWN`.
- An `OUTCOME_UNKNOWN` or expired `EXECUTING` operation performs inquiry before any further confirmation call.
- Kafka acknowledgment is allowed only after reaching a terminal state, observing an already terminal/leased operation, or durably saving the next recovery state.
- Do not put a payment key, complete Toss DTO, raw provider body, or virtual-account customer data in Kafka, DLT alerts, Slack messages, or the operation status API.
- Preserve cancellation behavior and its tests; cancellation/refund orchestration, zero-price confirmation, reservation nightly claims, and Elasticsearch redesign are separate projects.

---

## File Structure and Responsibilities

### New production files

- `src/main/resources/db/migration/V17__add_payment_operation.sql`: operation table, operation-linked ledger uniqueness, and coupon reservation lookup uniqueness.
- `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java`: workflow state, request data, lease fencing, retry metadata, and terminal transitions.
- `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperationStatus.java`: `READY`, `EXECUTING`, `RETRY_WAIT`, `OUTCOME_UNKNOWN`, `APPLIED`, `DECLINED`, `MANUAL_REVIEW`.
- `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperationType.java`: `CONFIRM`.
- `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentOperationRepository.java`: identity/deduplication locks and recoverable-row claim query.
- `src/main/java/kr/kro/airbob/domain/payment/event/PaymentOperationEvent.java`: identifier-only `PaymentExecutionRequestedV1` payload.
- `src/main/java/kr/kro/airbob/domain/payment/dto/PaymentOperationResponse.java`: accepted/detail API contracts and internal-to-public status mapping.
- `src/main/java/kr/kro/airbob/domain/payment/api/PaymentOperationController.java`: owner-only polling endpoint.
- `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationNotFoundException.java`: public operation 404.
- `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationConflictException.java`: conflicting replay 409.
- `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationInvariantException.java`: internal state/correlation failure.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationCommandService.java`: atomic reservation authorization, operation creation, and first outbox command.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationQueryService.java`: owner-only operation status lookup.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseService.java`: short transactions for lease acquisition and nonterminal outcome persistence.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationFinalizer.java`: atomic success/final-decline local effects.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutor.java`: nontransactional Toss orchestration.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryService.java`: locked due-work scan, republish, and manual-review transition.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationAlertService.java`: sanitized quarantine/manual-review Slack alerts.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecution.java`: immutable claimed operation data and lease owner.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecutionMode.java`: `CONFIRM` or `INQUIRE`.
- `src/main/java/kr/kro/airbob/domain/payment/service/PaymentRetryBackoff.java`: capped exponential retry calculation.
- `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationGateway.java`: normalized confirmation/inquiry port.
- `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationCommand.java`: operation-scoped provider command.
- `src/main/java/kr/kro/airbob/domain/payment/service/gateway/ConfirmedPayment.java`: normalized approved result, including optional normalized virtual-account fields.
- `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentGatewayResult.java`: sealed approved/declined/retryable/unknown/not-found outcomes.
- `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationFailureClassifier.java`: explicit terminal-decline allow-list.
- `src/main/java/kr/kro/airbob/domain/payment/config/PaymentOperationProperties.java`: lease, retry, scheduler, and publication policy.
- `src/main/java/kr/kro/airbob/domain/payment/config/TossPaymentClientProperties.java`: secret, base URL, and HTTP deadlines.
- `src/main/java/kr/kro/airbob/domain/payment/config/PaymentOperationConfiguration.java`: configuration-property registration and policy validation.
- `src/main/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoveryScheduler.java`: thin scheduled adapter and post-transaction alerts.
- `src/main/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumer.java`: dedicated retry-topic consumer and DLT handler.

### Modified production files

- `src/main/java/kr/kro/airbob/domain/payment/api/PaymentController.java`: route confirmation through the command service and return acceptance data.
- `src/main/java/kr/kro/airbob/domain/payment/dto/PaymentRequest.java`: keep `Confirm` as an HTTP DTO, then remove its legacy `EventPayload` role at cutover.
- `src/main/java/kr/kro/airbob/domain/payment/entity/Payment.java`: construct confirmation state from `ConfirmedPayment`, not `TossPaymentResponse`.
- `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentTransaction.java`: link confirmation/failure rows to one operation and construct from normalized data.
- `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentRepository.java`: add reservation-ID pessimistic lookup.
- `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentTransactionRepository.java`: add operation-effect lookup/count.
- `src/main/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapter.java`: implement the new port with explicit idempotency and typed outcomes; keep cancellation/query behavior.
- `src/main/java/kr/kro/airbob/domain/reservation/entity/Reservation.java`: centralize guest ownership and strict final-decline transition.
- `src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepository.java`: add reservation-ID pessimistic lookup.
- `src/main/java/kr/kro/airbob/common/exception/ErrorCode.java`: add operation not-found/conflict codes.
- `src/main/java/kr/kro/airbob/config/RestClientConfig.java`: apply Toss connect/read deadlines.
- `src/main/java/kr/kro/airbob/outbox/EventType.java`: add the versioned operation command, then remove legacy confirmation constants.
- `src/main/resources/application.yaml`: declare exact operation and Toss timeout defaults.

### Clean-cutover deletions and focused renames

- Delete `PaymentApprovalService`, `PaymentConfirmationProcessor`, `PaymentCompensationService`, and their tests.
- Rename `PaymentTransactionService` to `PaymentCancellationTransactionService` after confirmation methods are removed.
- Rename `PaymentGatewayWorker` to `PaymentCancellationGatewayWorker` after PG-confirm handling is removed.
- Rename `PaymentEventsConsumer` to `PaymentCancellationEventsConsumer` after confirmation outcomes are removed.
- Rename `PaymentEventTranslator` to `PaymentCancellationEventTranslator` after confirmation translation is removed.
- Remove legacy confirmation records/constants/branches from `PaymentEvent`, `ReservationService`, `ReservationTransactionService`, `ReservationEventsConsumer`, and `DlqConsumer`.

### New primary tests

- `src/test/java/kr/kro/airbob/migration/PaymentOperationMigrationIntegrationTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/entity/PaymentOperationTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationCommandServiceTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationQueryServiceTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/api/PaymentControllerTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/api/PaymentOperationControllerTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationFailureClassifierTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseServiceTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationFinalizerIntegrationTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutorTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryServiceIntegrationTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoverySchedulerTest.java`
- `src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumerTest.java`
- `src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationKafkaIntegrationTest.java`
- `src/test/java/kr/kro/airbob/domain/payment/PaymentOperationFlowIntegrationTest.java`

---

### Task 1: Add the durable payment-operation schema and domain state

**Files:**
- Create: `src/main/resources/db/migration/V17__add_payment_operation.sql`
- Create: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperationStatus.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperationType.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentOperationRepository.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentTransaction.java`
- Create: `src/test/java/kr/kro/airbob/migration/PaymentOperationMigrationIntegrationTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/entity/PaymentOperationTest.java`

**Interfaces:**
- Produces: `PaymentOperation.createConfirmation(Reservation, Long, String, long, Instant)`.
- Produces: `PaymentOperation.matchesConfirmation(String, long)`, `isRequestedBy(Long)`, `recordEnqueued(Instant)`, and terminal/lease methods extended in Tasks 4–5.
- Produces: `PaymentOperationRepository.findByOperationUid(UUID)`, `findByOperationUidWithLock(UUID)`, and `findByDeduplicationKey(String)`.
- Produces: nullable unique `PaymentTransaction.paymentOperationId` for one ledger effect per operation.

- [ ] **Step 1: Write the failing Flyway schema test**

```java
@Test
void v17CreatesOperationAndUniqueLedgerLink() throws SQLException {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection connection = connection()) {
        assertThat(tableExists(connection, "payment_operation")).isTrue();
        assertThat(columnNullable(connection, "payment_operation", "payment_key")).isFalse();
        assertThat(indexIsUnique(connection, "payment_operation", "uk_payment_operation_uid")).isTrue();
        assertThat(indexIsUnique(connection, "payment_operation", "uk_payment_operation_deduplication_key")).isTrue();
        assertThat(indexIsUnique(connection, "payment_transaction", "uk_payment_transaction_operation_id")).isTrue();
        assertThat(indexIsUnique(connection, "member_coupon", "uk_member_coupon_reservation_id")).isTrue();
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails before V17 exists**

Run: `./gradlew test --tests "kr.kro.airbob.migration.PaymentOperationMigrationIntegrationTest"`

Expected: FAIL because `payment_operation` and `payment_transaction.payment_operation_id` do not exist.

- [ ] **Step 3: Add the complete V17 schema**

```sql
CREATE TABLE payment_operation (
  id bigint NOT NULL AUTO_INCREMENT,
  operation_uid binary(16) NOT NULL,
  reservation_id bigint NOT NULL,
  requester_member_id bigint NOT NULL,
  operation_type varchar(30) NOT NULL,
  status varchar(30) NOT NULL,
  payment_key varchar(200) NOT NULL,
  expected_amount bigint NOT NULL,
  provider_idempotency_key varchar(100) NOT NULL,
  deduplication_key varchar(100) NOT NULL,
  attempt_count int NOT NULL DEFAULT 0,
  next_attempt_at datetime(6) DEFAULT NULL,
  last_enqueued_at datetime(6) NOT NULL,
  lease_owner varchar(100) DEFAULT NULL,
  lease_expires_at datetime(6) DEFAULT NULL,
  failure_code varchar(100) DEFAULT NULL,
  failure_message varchar(512) DEFAULT NULL,
  completed_at datetime(6) DEFAULT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at datetime(6) NOT NULL,
  updated_at datetime(6) NOT NULL,
  created_by bigint DEFAULT NULL,
  updated_by bigint DEFAULT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_payment_operation_uid UNIQUE (operation_uid),
  CONSTRAINT uk_payment_operation_provider_key UNIQUE (provider_idempotency_key),
  CONSTRAINT uk_payment_operation_deduplication_key UNIQUE (deduplication_key),
  CONSTRAINT chk_payment_operation_amount CHECK (expected_amount > 0),
  KEY idx_payment_operation_recovery (status, next_attempt_at, last_enqueued_at),
  KEY idx_payment_operation_lease (lease_expires_at),
  CONSTRAINT fk_payment_operation_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT fk_payment_operation_requester FOREIGN KEY (requester_member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE payment_transaction
  ADD COLUMN payment_operation_id bigint DEFAULT NULL,
  ADD CONSTRAINT uk_payment_transaction_operation_id UNIQUE (payment_operation_id),
  ADD CONSTRAINT fk_payment_transaction_operation
    FOREIGN KEY (payment_operation_id) REFERENCES payment_operation (id);

ALTER TABLE member_coupon
  ADD CONSTRAINT uk_member_coupon_reservation_id UNIQUE (reservation_id);
```

- [ ] **Step 4: Write failing domain tests for identity, deduplication, privacy boundaries, and basic terminal states**

```java
@Test
void createsStableOperationIdentityFromReservation() {
    PaymentOperation operation = PaymentOperation.createConfirmation(
        reservation, 7L, "secret-payment-key", 100_000L, NOW);

    assertThat(operation.getOperationUid()).isNotNull();
    assertThat(operation.getProviderIdempotencyKey())
        .isEqualTo("airbob-confirm-" + operation.getOperationUid());
    assertThat(operation.getDeduplicationKey())
        .isEqualTo("CONFIRM:" + reservation.getReservationUid());
    assertThat(operation.getStatus()).isEqualTo(PaymentOperationStatus.READY);
    assertThat(operation.getAttemptCount()).isZero();
    assertThat(operation.getLastEnqueuedAt()).isEqualTo(NOW);
}

@Test
void identicalAndConflictingReplaysAreDistinguishedWithoutLoggingSecrets() {
    PaymentOperation operation = operation("pk-one", 100_000L);

    assertThat(operation.matchesConfirmation("pk-one", 100_000L)).isTrue();
    assertThat(operation.matchesConfirmation("pk-two", 100_000L)).isFalse();
    assertThat(operation.matchesConfirmation("pk-one", 90_000L)).isFalse();
}
```

- [ ] **Step 5: Implement the initial entity and repository mapping**

Use these exact enum values and identity rules:

```java
public enum PaymentOperationStatus {
    READY, EXECUTING, RETRY_WAIT, OUTCOME_UNKNOWN, APPLIED, DECLINED, MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == APPLIED || this == DECLINED || this == MANUAL_REVIEW;
    }
}

public enum PaymentOperationType { CONFIRM }
```

`PaymentOperation` extends `BaseEntity`, maps UUID as `BINARY(16)`, maps `reservation` as lazy `@ManyToOne`, stores `requesterMemberId` as a scalar FK value, uses `@Version long version`, bounds secrets/errors to the V17 lengths, and creates identity as follows:

```java
public static PaymentOperation createConfirmation(
    Reservation reservation, Long requesterMemberId, String paymentKey, long amount, Instant now
) {
    UUID operationUid = UUID.randomUUID();
    return PaymentOperation.builder()
        .operationUid(operationUid)
        .reservation(reservation)
        .requesterMemberId(requesterMemberId)
        .operationType(PaymentOperationType.CONFIRM)
        .status(PaymentOperationStatus.READY)
        .paymentKey(paymentKey)
        .expectedAmount(amount)
        .providerIdempotencyKey("airbob-confirm-" + operationUid)
        .deduplicationKey("CONFIRM:" + reservation.getReservationUid())
        .attemptCount(0)
        .nextAttemptAt(now)
        .lastEnqueuedAt(now)
        .build();
}
```

Repository lock signatures:

```java
Optional<PaymentOperation> findByOperationUid(UUID operationUid);
Optional<PaymentOperation> findByDeduplicationKey(String deduplicationKey);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select po from PaymentOperation po where po.operationUid = :operationUid")
Optional<PaymentOperation> findByOperationUidWithLock(UUID operationUid);
```

- [ ] **Step 6: Run schema and entity tests**

Run: `./gradlew test --tests "kr.kro.airbob.migration.PaymentOperationMigrationIntegrationTest" --tests "kr.kro.airbob.domain.payment.entity.PaymentOperationTest"`

Expected: PASS.

- [ ] **Step 7: Commit the durable model**

```bash
git add src/main/resources/db/migration/V17__add_payment_operation.sql src/main/java/kr/kro/airbob/domain/payment/entity src/main/java/kr/kro/airbob/domain/payment/repository/PaymentOperationRepository.java src/main/java/kr/kro/airbob/domain/payment/entity/PaymentTransaction.java src/test/java/kr/kro/airbob/migration/PaymentOperationMigrationIntegrationTest.java src/test/java/kr/kro/airbob/domain/payment/entity/PaymentOperationTest.java
git commit -m "feat: add durable payment operation model"
```

---

### Task 2: Accept and expose authenticated payment operations

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/event/PaymentOperationEvent.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/dto/PaymentOperationResponse.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/api/PaymentOperationController.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationNotFoundException.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationConflictException.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationCommandService.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationQueryService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/api/PaymentController.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/entity/Reservation.java`
- Modify: `src/main/java/kr/kro/airbob/common/exception/ErrorCode.java`
- Modify: `src/main/java/kr/kro/airbob/outbox/EventType.java`
- Modify: `src/test/java/kr/kro/airbob/domain/auth/api/CurrentMemberIdControllerContractTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/reservation/ReservationConcurrencyTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationCommandServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationQueryServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/api/PaymentControllerTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/api/PaymentOperationControllerTest.java`

**Interfaces:**
- Produces: `PaymentOperationResponse.Accepted requestConfirmation(PaymentRequest.Confirm, Long memberId)`.
- Produces: `PaymentOperationResponse.Detail find(UUID operationUid, Long memberId)`.
- Produces: `PaymentExecutionRequestedV1(UUID operationUid, UUID reservationUid)` with reservation UID as `EventPayload#getId()`.
- Consumes: reservation pessimistic lock and `PaymentOperation` identity from Task 1.

- [ ] **Step 1: Write failing command-service tests for authorization, validation, idempotency, and atomic publication**

```java
@Test
void ownerCreatesOperationAndCommandInOneTransaction() {
    given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
        .willReturn(Optional.of(pendingReservation));
    given(paymentOperationRepository.findByDeduplicationKey("CONFIRM:" + RESERVATION_UID))
        .willReturn(Optional.empty());

    Accepted accepted = service.requestConfirmation(request(), GUEST_ID);

    assertThat(pendingReservation.getStatus()).isEqualTo(PAYMENT_PROCESSING);
    then(paymentOperationRepository).should().save(operationCaptor.capture());
    then(outboxEventPublisher).should().save(
        eq(PAYMENT_EXECUTION_REQUESTED_V1),
        eq(new PaymentExecutionRequestedV1(accepted.operationId(), RESERVATION_UID))
    );
}

@Test
void nonOwnerCreatesNeitherOperationNorOutbox() {
    given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
        .willReturn(Optional.of(pendingReservation));

    assertThatThrownBy(() -> service.requestConfirmation(request(), 999L))
        .isInstanceOf(PaymentAccessDeniedException.class);
    then(paymentOperationRepository).shouldHaveNoInteractions();
    then(outboxEventPublisher).shouldHaveNoInteractions();
}

@Test
void identicalReplayReturnsSameOperationButConflictReturns409() {
    givenExistingOperation("pk-one", 100_000L);
    assertThat(service.requestConfirmation(request("pk-one", 100_000), GUEST_ID).operationId())
        .isEqualTo(EXISTING_OPERATION_UID);
    assertThatThrownBy(() -> service.requestConfirmation(request("pk-two", 100_000), GUEST_ID))
        .isInstanceOf(PaymentOperationConflictException.class);
}
```

- [ ] **Step 1b: Run the command tests and verify the new service is missing**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationCommandServiceTest"`

Expected: FAIL because `PaymentOperationCommandService` and `PaymentExecutionRequestedV1` do not exist.

- [ ] **Step 2: Implement the command payload, errors, and atomic command service**

```java
public record PaymentExecutionRequestedV1(
    UUID operationUid,
    UUID reservationUid
) implements EventPayload {
    @Override public String getId() { return reservationUid.toString(); }
}
```

Add `PAYMENT_EXECUTION_REQUESTED_V1("PAYMENT_OPERATION", "payment-operation-events")` to `EventType`. Add `PAYMENT_OPERATION_NOT_FOUND` as `P005/404` and `PAYMENT_OPERATION_CONFLICT` as `P006/409`.

The transaction must use this order:

```java
@Transactional
public Accepted requestConfirmation(PaymentRequest.Confirm request, Long memberId) {
    UUID reservationUid = parseReservationUid(request.orderId());
    Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUid)
        .orElseThrow(ReservationNotFoundException::new);
    if (!reservation.belongsToGuest(memberId)) throw new PaymentAccessDeniedException();
    if (!reservation.matchesPaymentRequest(request.orderId(), request.amount().longValue())) {
        throw new InvalidInputException("결제 승인 요청이 예약 정보와 일치하지 않습니다.");
    }

    String deduplicationKey = "CONFIRM:" + reservationUid;
    Optional<PaymentOperation> existing = paymentOperationRepository.findByDeduplicationKey(deduplicationKey);
    if (existing.isPresent()) return replayOrConflict(existing.get(), request);

    Instant now = clock.instant();
    if (!reservation.startPayment(now)) throw new ExpiredReservationConfirmationException();
    PaymentOperation operation = PaymentOperation.createConfirmation(
        reservation, memberId, request.paymentKey(), request.amount(), now);
    paymentOperationRepository.save(operation);
    historyRepository.save(ReservationHistory.ofSystem(
        reservation, ChangeType.STATUS_CHANGE, "결제 승인 처리 시작", "PAYMENT_OPERATION"));
    outboxEventPublisher.save(PAYMENT_EXECUTION_REQUESTED_V1,
        new PaymentExecutionRequestedV1(operation.getOperationUid(), reservationUid));
    return Accepted.from(operation);
}
```

- [ ] **Step 3: Write and implement public response mapping and owner-only query**

```java
public enum Status { PENDING, PROCESSING, SUCCEEDED, FAILED, REQUIRES_REVIEW }

public record Accepted(UUID operationId, Status status, String statusUrl) {}
public record Detail(
    UUID operationId, UUID orderId, Status status, String failureCode, Instant updatedAt
) {}
```

Map `READY/RETRY_WAIT -> PENDING`, `EXECUTING/OUTCOME_UNKNOWN -> PROCESSING`, `APPLIED -> SUCCEEDED`, `DECLINED -> FAILED`, and `MANUAL_REVIEW -> REQUIRES_REVIEW`. Return `failureCode` only for `DECLINED` and `MANUAL_REVIEW`; never return `failureMessage` or `paymentKey`.
Convert the inherited UTC `LocalDateTime updatedAt` with `operation.getUpdatedAt().toInstant(ZoneOffset.UTC)`.

Query implementation:

```java
@Transactional(readOnly = true)
public Detail find(UUID operationUid, Long memberId) {
    PaymentOperation operation = repository.findByOperationUid(operationUid)
        .orElseThrow(PaymentOperationNotFoundException::new);
    if (!operation.isRequestedBy(memberId)) throw new PaymentAccessDeniedException();
    return Detail.from(operation);
}
```

- [ ] **Step 4: Route both HTTP endpoints and test their exact contracts**

```java
@PostMapping("/v1/payments/confirm")
public ResponseEntity<ApiResponse<Accepted>> confirmPayment(
    @Valid @RequestBody PaymentRequest.Confirm request,
    @CurrentMemberId Long memberId
) {
    return ResponseEntity.accepted()
        .body(ApiResponse.success(commandService.requestConfirmation(request, memberId)));
}

@GetMapping("/api/v1/payment-operations/{operationId}")
public ResponseEntity<ApiResponse<Detail>> find(
    @PathVariable UUID operationId,
    @CurrentMemberId Long memberId
) {
    return ResponseEntity.ok(ApiResponse.success(queryService.find(operationId, memberId)));
}
```

Controller tests must assert HTTP 202, the same service return object, exact `/api/v1/payment-operations/{uuid}` URL, and no payment-key field in serialized detail. Add both controller methods to `CurrentMemberIdControllerContractTest` as required member handlers.

- [ ] **Step 5: Replace the old confirmation-claim concurrency assertion**

In `ReservationConcurrencyTest`, run two simultaneous `requestConfirmation` calls for the same owner/request and assert:

```java
assertThat(unexpectedFailCount.get()).isZero();
assertThat(returnedOperationUids).hasSize(2).containsOnly(operationUid);
assertThat(paymentOperationRepository.count()).isEqualTo(1);
assertThat(outboxRepository.findAll().stream()
    .filter(row -> PAYMENT_EXECUTION_REQUESTED_V1.name().equals(row.getEventType())))
    .hasSize(1);
assertThat(reloadedReservation.getStatus()).isEqualTo(PAYMENT_PROCESSING);
```

- [ ] **Step 6: Run command/API/concurrency tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationCommandServiceTest" --tests "kr.kro.airbob.domain.payment.service.PaymentOperationQueryServiceTest" --tests "kr.kro.airbob.domain.payment.api.PaymentControllerTest" --tests "kr.kro.airbob.domain.payment.api.PaymentOperationControllerTest" --tests "kr.kro.airbob.domain.auth.api.CurrentMemberIdControllerContractTest" --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest.concurrentPaymentApprovalClaimsOnce"`

Expected: PASS with one operation and one initial outbox row.

- [ ] **Step 7: Commit authenticated operation acceptance**

```bash
git add src/main/java/kr/kro/airbob/domain/payment src/main/java/kr/kro/airbob/domain/reservation/entity/Reservation.java src/main/java/kr/kro/airbob/common/exception/ErrorCode.java src/main/java/kr/kro/airbob/outbox/EventType.java src/test/java/kr/kro/airbob/domain/payment src/test/java/kr/kro/airbob/domain/auth/api/CurrentMemberIdControllerContractTest.java src/test/java/kr/kro/airbob/domain/reservation/ReservationConcurrencyTest.java
git commit -m "feat: accept authenticated payment operations"
```

---

### Task 3: Put Toss behind a typed, deadline-bound gateway

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationGateway.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationCommand.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/gateway/ConfirmedPayment.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentGatewayResult.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationFailureClassifier.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/config/TossPaymentClientProperties.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapter.java`
- Modify: `src/main/java/kr/kro/airbob/config/RestClientConfig.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/gateway/PaymentConfirmationFailureClassifierTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapterTest.java`

**Interfaces:**
- Produces: `PaymentGatewayResult confirm(PaymentConfirmationCommand)` and `PaymentGatewayResult inquire(PaymentConfirmationCommand)`.
- Produces: `ConfirmedPayment` normalized without exposing `TossPaymentResponse` beyond the adapter.
- Consumes: operation payment key/order/amount/provider idempotency key from a claimed execution.

- [ ] **Step 1: Write failing classifier tests for every outcome family**

```java
@ParameterizedTest
@ValueSource(strings = {"REJECT_CARD_PAYMENT", "INVALID_CARD_NUMBER", "REJECT_ACCOUNT_PAYMENT", "FDS_ERROR"})
void allowListedCustomerDeclinesAreFinal(String code) {
    assertThat(classifier.classify(code, "safe message"))
        .isInstanceOf(PaymentGatewayResult.Declined.class);
}

@ParameterizedTest
@ValueSource(strings = {"ALREADY_PROCESSED_PAYMENT", "PROVIDER_ERROR", "INVALID_API_KEY", "SOMETHING_NEW"})
void ambiguousOrUnknownCodesRequireInquiry(String code) {
    assertThat(classifier.classify(code, "provider text"))
        .isInstanceOf(PaymentGatewayResult.OutcomeUnknown.class);
}
```

- [ ] **Step 1b: Run the classifier test and verify it fails**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationFailureClassifierTest"`

Expected: FAIL because the gateway result and classifier types do not exist.

The exact final-decline allow-list is:

```text
EXCEED_MAX_CARD_INSTALLMENT_PLAN, NOT_ALLOWED_POINT_USE, INVALID_REJECT_CARD,
BELOW_MINIMUM_AMOUNT, INVALID_CARD_EXPIRATION, INVALID_STOPPED_CARD,
EXCEED_MAX_DAILY_PAYMENT_COUNT, NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT,
INVALID_CARD_INSTALLMENT_PLAN, NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN,
EXCEED_MAX_PAYMENT_AMOUNT, INVALID_CARD_LOST_OR_STOLEN, RESTRICTED_TRANSFER_ACCOUNT,
INVALID_CARD_NUMBER, EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT,
EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT, EXCEED_MAX_AMOUNT,
INVALID_ACCOUNT_INFO_RE_REGISTER, NOT_AVAILABLE_PAYMENT,
UNAPPROVED_ORDER_ID, EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT,
REJECT_ACCOUNT_PAYMENT, REJECT_CARD_PAYMENT, REJECT_CARD_COMPANY,
REJECT_TOSSPAY_INVALID_ACCOUNT, EXCEED_MAX_AUTH_COUNT,
EXCEED_MAX_ONE_DAY_AMOUNT, NOT_AVAILABLE_BANK, INVALID_PASSWORD, FDS_ERROR,
NOT_FOUND_PAYMENT, NOT_FOUND_PAYMENT_SESSION
```

- [ ] **Step 2: Define the gateway types exactly once**

```java
public record PaymentConfirmationCommand(
    UUID operationUid,
    String paymentKey,
    String orderId,
    long amount,
    String providerIdempotencyKey
) {}

public sealed interface PaymentGatewayResult {
    record Approved(ConfirmedPayment payment) implements PaymentGatewayResult {}
    record Declined(String code, String message) implements PaymentGatewayResult {}
    record RetryableFailure(String code, String message) implements PaymentGatewayResult {}
    record OutcomeUnknown(String code, String message) implements PaymentGatewayResult {}
    record NotFound(String code, String message) implements PaymentGatewayResult {}
}

public interface PaymentConfirmationGateway {
    PaymentGatewayResult confirm(PaymentConfirmationCommand command);
    PaymentGatewayResult inquire(PaymentConfirmationCommand command);
}

public record ConfirmedPayment(
    String paymentKey,
    String orderId,
    long totalAmount,
    long balanceAmount,
    PaymentMethod method,
    PaymentStatus status,
    Instant approvedAt,
    VirtualAccountDetails virtualAccount
) {
    public record VirtualAccountDetails(
        String bankCode,
        String accountNumber,
        String customerName,
        Instant dueDate
    ) {}
}
```

The optional `VirtualAccountDetails` is used only for the database ledger.

- [ ] **Step 3: Make adapter tests describe headers, correlation data, and failure classification**

Add MockRestServiceServer cases that assert:

```java
server.expect(requestTo(CONFIRM_PATH))
    .andExpect(method(HttpMethod.POST))
    .andExpect(header("Idempotency-Key", "airbob-confirm-" + OPERATION_UID))
    .andExpect(content().json("""
        {"paymentKey":"pk_test","orderId":"%s","amount":100000}
        """.formatted(ORDER_ID)))
    .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

assertThat(adapter.confirm(command())).isInstanceOf(PaymentGatewayResult.Approved.class);
```

Also assert: allow-listed 4xx is `Declined`; already processed, unknown 4xx, 5xx, response parse failure, and read timeout are `OutcomeUnknown`; connect failure is `RetryableFailure`; inquiry 404 is `NotFound`; inquiry `DONE` is `Approved`.

- [ ] **Step 3b: Run the adapter tests and verify the old exception contract fails**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.TossPaymentsAdapterTest"`

Expected: FAIL because the adapter does not implement `PaymentConfirmationGateway`, does not accept the operation idempotency key, and still throws the legacy confirmation exception.

- [ ] **Step 4: Implement the adapter without confirmation-level `@Retryable`**

The orchestration owns retries, so `confirm(PaymentConfirmationCommand)` performs one HTTP attempt. It catches the adapter's parsed HTTP exception and calls the classifier. It classifies `ConnectException`/`HttpConnectTimeoutException` before transmission as `RetryableFailure`; `SocketTimeoutException`, response conversion failure, and all uncertain transport exceptions are `OutcomeUnknown`. `inquire` maps 404 to `NotFound`, `DONE` to `Approved`, `ABORTED/EXPIRED/CANCELED` to `Declined`, and every unresolved/intermediate status to `OutcomeUnknown`.

Keep the current cancellation method behavior intact. Do not reuse its order-ID idempotency key for confirmation.

- [ ] **Step 5: Configure explicit client deadlines**

```yaml
payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY}
    base-url: https://api.tosspayments.com
    connect-timeout: ${TOSS_CONNECT_TIMEOUT:2s}
    read-timeout: ${TOSS_READ_TIMEOUT:10s}
```

Build the Toss client with a JDK HTTP client and request factory:

```java
HttpClient client = HttpClient.newBuilder()
    .connectTimeout(properties.connectTimeout())
    .build();
JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
requestFactory.setReadTimeout(properties.readTimeout());
return RestClient.builder()
    .requestFactory(requestFactory)
    .baseUrl(properties.baseUrl())
    .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(properties.secretKey()))
    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    .build();
```

- [ ] **Step 6: Run gateway tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.gateway.PaymentConfirmationFailureClassifierTest" --tests "kr.kro.airbob.domain.payment.service.TossPaymentsAdapterTest"`

Expected: PASS; no adapter test expects a hard-coded order-ID idempotency key.

- [ ] **Step 7: Commit the typed gateway**

```bash
git add src/main/java/kr/kro/airbob/domain/payment/service/gateway src/main/java/kr/kro/airbob/domain/payment/config/TossPaymentClientProperties.java src/main/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapter.java src/main/java/kr/kro/airbob/config/RestClientConfig.java src/main/resources/application.yaml src/test/java/kr/kro/airbob/domain/payment/service/gateway src/test/java/kr/kro/airbob/domain/payment/service/TossPaymentsAdapterTest.java
git commit -m "refactor: isolate typed Toss confirmation gateway"
```

---

### Task 4: Add fenced leases and durable retry transitions

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/config/PaymentOperationProperties.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/config/PaymentOperationConfiguration.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecution.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecutionMode.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentRetryBackoff.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseServiceTest.java`

**Interfaces:**
- Produces: `Optional<PaymentExecution> claim(UUID operationUid)`.
- Produces: `boolean scheduleRetry(PaymentExecution, String code, String message)` and `boolean markOutcomeUnknown(PaymentExecution, String code, String message)`.
- Produces: `PaymentExecution.gatewayCommand()` and `PaymentExecutionMode.CONFIRM/INQUIRE`.
- Consumes: locked `PaymentOperation` repository and typed gateway command from Task 3.

- [ ] **Step 1: Write failing state/lease tests**

```java
@Test
void readyClaimGetsConfirmModeAndFencesConcurrentWorker() {
    Optional<PaymentExecution> first = service.claim(OPERATION_UID);
    Optional<PaymentExecution> second = service.claim(OPERATION_UID);

    assertThat(first).get().extracting(PaymentExecution::mode).isEqualTo(CONFIRM);
    assertThat(second).isEmpty();
    assertThat(operation.getStatus()).isEqualTo(EXECUTING);
    assertThat(operation.getAttemptCount()).isEqualTo(1);
    assertThat(operation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
}

@Test
void expiredExecutingClaimUsesInquiryAndReplacesLeaseOwner() {
    operation.acquireLease("old-worker", NOW.minusSeconds(31), Duration.ofSeconds(30));
    Optional<PaymentExecution> recovered = service.claim(OPERATION_UID);
    assertThat(recovered).get().extracting(PaymentExecution::mode).isEqualTo(INQUIRE);
    assertThat(recovered.get().leaseOwner()).isNotEqualTo("old-worker");
}

@Test
void staleWorkerCannotOverwriteCurrentRecoveryState() {
    PaymentExecution stale = claimedBy("old-worker");
    operation.acquireLease("new-worker", NOW, Duration.ofSeconds(30));
    assertThat(service.markOutcomeUnknown(stale, "TIMEOUT", "lost response")).isFalse();
    assertThat(operation.getLeaseOwner()).isEqualTo("new-worker");
}
```

- [ ] **Step 1b: Run the lease tests and verify they fail**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationLeaseServiceTest"`

Expected: FAIL because the lease service, execution record, and lease transitions do not exist.

- [ ] **Step 2: Add exact operation policy properties**

```yaml
payment:
  operation:
    lease-duration: ${PAYMENT_OPERATION_LEASE_DURATION:30s}
    scheduler-delay: ${PAYMENT_OPERATION_SCHEDULER_DELAY:10s}
    batch-size: ${PAYMENT_OPERATION_BATCH_SIZE:100}
    max-attempts: ${PAYMENT_OPERATION_MAX_ATTEMPTS:5}
    retry-initial-delay: ${PAYMENT_OPERATION_RETRY_INITIAL_DELAY:10s}
    retry-max-delay: ${PAYMENT_OPERATION_RETRY_MAX_DELAY:5m}
    recovery-publication-interval: ${PAYMENT_OPERATION_RECOVERY_PUBLICATION_INTERVAL:10s}
```

The `PaymentOperationProperties` compact constructor rejects nonpositive durations/counts and `retryInitialDelay > retryMaxDelay`. When `PaymentOperationConfiguration` wires both property records, it rejects either a Toss connect timeout or read timeout greater than or equal to the lease.

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentOperationProperties.class, TossPaymentClientProperties.class})
public class PaymentOperationConfiguration {
    @Bean
    InitializingBean paymentOperationTimeoutGuard(
        PaymentOperationProperties operation, TossPaymentClientProperties toss
    ) {
        return () -> {
            if (toss.connectTimeout().compareTo(operation.leaseDuration()) >= 0
                || toss.readTimeout().compareTo(operation.leaseDuration()) >= 0) {
                throw new IllegalStateException("Toss 타임아웃은 payment-operation lease보다 짧아야 합니다.");
            }
        };
    }
}
```

Define the claimed data contract exactly as:

```java
public record PaymentExecution(
    UUID operationUid,
    UUID reservationUid,
    String paymentKey,
    String orderId,
    long amount,
    String providerIdempotencyKey,
    String leaseOwner,
    PaymentExecutionMode mode
) {
    public static PaymentExecution from(
        PaymentOperation operation, String leaseOwner, PaymentExecutionMode mode
    ) {
        UUID reservationUid = operation.getReservation().getReservationUid();
        return new PaymentExecution(
            operation.getOperationUid(), reservationUid, operation.getPaymentKey(),
            reservationUid.toString(), operation.getExpectedAmount(),
            operation.getProviderIdempotencyKey(), leaseOwner, mode);
    }

    public PaymentConfirmationCommand gatewayCommand() {
        return new PaymentConfirmationCommand(
            operationUid, paymentKey, orderId, amount, providerIdempotencyKey);
    }
}
```

- [ ] **Step 3: Implement lease transitions on the entity**

```java
public Optional<PaymentExecutionMode> acquireLease(
    String owner, Instant now, Duration leaseDuration
) {
    if (status.isTerminal()) return Optional.empty();
    if (status == EXECUTING && leaseExpiresAt != null && leaseExpiresAt.isAfter(now)) {
        return Optional.empty();
    }
    if ((status == RETRY_WAIT || status == OUTCOME_UNKNOWN)
        && nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
        return Optional.empty();
    }
    PaymentExecutionMode mode = (status == OUTCOME_UNKNOWN || status == EXECUTING)
        ? PaymentExecutionMode.INQUIRE : PaymentExecutionMode.CONFIRM;
    status = EXECUTING;
    leaseOwner = owner;
    leaseExpiresAt = now.plus(leaseDuration);
    attemptCount++;
    return Optional.of(mode);
}
```

`scheduleRetry` and `markOutcomeUnknown` first require `status == EXECUTING` and matching `leaseOwner`; stale owners return `false`. They clear owner/expiry, store bounded code/message, and set `nextAttemptAt`. `recordEnqueued(now)` updates only `lastEnqueuedAt`.

- [ ] **Step 4: Implement capped exponential backoff and short transactional service methods**

```java
public Duration forAttempt(int attemptCount) {
    long multiplier = 1L << Math.max(0, Math.min(attemptCount - 1, 20));
    Duration candidate = initial.multipliedBy(multiplier);
    return candidate.compareTo(max) > 0 ? max : candidate;
}

@Transactional
public Optional<PaymentExecution> claim(UUID operationUid) {
    PaymentOperation operation = lock(operationUid);
    String owner = UUID.randomUUID().toString();
    return operation.acquireLease(owner, clock.instant(), properties.leaseDuration())
        .map(mode -> PaymentExecution.from(operation, owner, mode));
}
```

- [ ] **Step 5: Run lease/retry tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.entity.PaymentOperationTest" --tests "kr.kro.airbob.domain.payment.service.PaymentOperationLeaseServiceTest"`

Expected: PASS for valid lease exclusion, expiry inquiry, due-time enforcement, fencing, terminal no-op, and capped backoff.

- [ ] **Step 6: Commit lease and retry state**

```bash
git add src/main/java/kr/kro/airbob/domain/payment/config src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecution.java src/main/java/kr/kro/airbob/domain/payment/service/PaymentExecutionMode.java src/main/java/kr/kro/airbob/domain/payment/service/PaymentRetryBackoff.java src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseService.java src/main/resources/application.yaml src/test/java/kr/kro/airbob/domain/payment/entity/PaymentOperationTest.java src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationLeaseServiceTest.java
git commit -m "feat: add fenced payment execution leases"
```

---

### Task 5: Atomically finalize approval or final decline

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationFinalizer.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/exception/PaymentOperationInvariantException.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/Payment.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentTransaction.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentRepository.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentTransactionRepository.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/entity/Reservation.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepository.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationFinalizerIntegrationTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/reservation/entity/ReservationTest.java`

**Interfaces:**
- Produces: `void applyApproved(PaymentExecution, ConfirmedPayment)`.
- Produces: `void applyDeclined(PaymentExecution, String code, String message)`.
- Consumes: current lease owner, normalized gateway result, reservation/payment/ledger repositories, coupon service, history, and outbox.

- [ ] **Step 1: Write failing reservation transition tests for the accepted-operation deadline rule**

```java
@Test
void acceptedOperationCanConfirmAfterOriginalDeadline() {
    Reservation reservation = processingReservationWithExpiry(NOW.minusSeconds(1));
    assertThatCode(reservation::confirm).doesNotThrowAnyException();
    assertThat(reservation.getStatus()).isEqualTo(CONFIRMED);
}

@Test
void finalDeclineRequiresPaymentProcessingExactly() {
    Reservation pending = pendingReservation();
    assertThatThrownBy(pending::expireAfterFinalPaymentDecline)
        .isInstanceOf(InvalidReservationStatusException.class);
}
```

- [ ] **Step 1b: Run the reservation state tests and verify the strict decline method is missing**

Run: `./gradlew test --tests "kr.kro.airbob.domain.reservation.entity.ReservationTest"`

Expected: FAIL because `expireAfterFinalPaymentDecline()` does not exist.

- [ ] **Step 2: Add locked repository methods and normalized factories**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from Reservation r where r.id = :reservationId")
Optional<Reservation> findByIdWithLock(Long reservationId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Payment p where p.reservation.id = :reservationId")
Optional<Payment> findByReservationIdWithLock(Long reservationId);

Optional<Payment> findByReservationId(Long reservationId);

boolean existsByPaymentOperationId(Long paymentOperationId);
long countByPaymentOperationId(Long paymentOperationId);
```

Change the confirmation factories to:

```java
Payment.create(ConfirmedPayment confirmed, Reservation reservation)
PaymentTransaction.confirm(
    ConfirmedPayment confirmed, Reservation reservation, Payment payment, Long paymentOperationId)
PaymentTransaction.fail(
    PaymentOperation operation, Reservation reservation, String failureCode, String failureMessage)
```

- [ ] **Step 3: Write success finalization integration tests before implementation**

The primary assertion is one transaction containing every effect:

```java
finalizer.applyApproved(execution, confirmedPayment(APPROVED_AFTER_RESERVATION_EXPIRY));

PaymentOperation operation = operationRepository.findByOperationUid(OPERATION_UID).orElseThrow();
Reservation reservation = reservationRepository.findById(RESERVATION_ID).orElseThrow();
assertThat(operation.getStatus()).isEqualTo(APPLIED);
assertThat(reservation.getStatus()).isEqualTo(CONFIRMED);
assertThat(paymentRepository.findByReservationId(RESERVATION_ID)).isPresent();
assertThat(transactionRepository.countByPaymentOperationId(operation.getId())).isEqualTo(1);
assertThat(historyRepository.findAll()).extracting(ReservationHistory::getStatus)
    .contains(CONFIRMED);
assertThat(outboxEventTypes()).containsExactlyInAnyOrder(
    RESERVATION_CONFIRMED.name(), RESERVATION_CHANGED.name());
```

Call `applyApproved` twice and assert all row/event counts remain unchanged. Add an opposite-terminal replay test that throws `PaymentOperationInvariantException`.

- [ ] **Step 4: Write final-decline and rollback integration tests before implementation**

```java
finalizer.applyDeclined(execution, "REJECT_CARD_PAYMENT", "card rejected");

assertThat(reloadOperation().getStatus()).isEqualTo(DECLINED);
assertThat(reloadReservation().getStatus()).isEqualTo(EXPIRED);
assertThat(transactionRepository.countByPaymentOperationId(OPERATION_ID)).isEqualTo(1);
assertThat(reloadMemberCoupon().isUsed()).isFalse();
assertThat(outboxEventTypes()).containsExactly(RESERVATION_EXPIRED.name());
```

With `@MockitoSpyBean OutboxEventPublisher`, throw on the terminal outbox save and assert operation, reservation, payment, ledger, coupon, and history all retain their pre-call values. Reset the spy after the assertion.

- [ ] **Step 4b: Run the finalizer integration test and verify it fails**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationFinalizerIntegrationTest"`

Expected: FAIL because `PaymentOperationFinalizer` and normalized payment factories do not exist.

- [ ] **Step 5: Implement one lock order and terminal guard in both finalizers**

Use exactly this lock order: operation by UID, terminal/fence guard, reservation by ID, then payment by reservation ID on success. Do not call Toss here.

Success transaction:

```java
@Transactional
public void applyApproved(PaymentExecution execution, ConfirmedPayment confirmed) {
    PaymentOperation operation = lockOperation(execution.operationUid());
    if (operation.isApplied()) return;
    operation.rejectOppositeTerminal(PaymentOperationStatus.APPLIED);
    if (!operation.isOwnedBy(execution.leaseOwner())) return;
    Reservation reservation = reservationRepository.findByIdWithLock(operation.getReservation().getId())
        .orElseThrow(ReservationNotFoundException::new);
    validateCorrelation(operation, reservation, confirmed);
    Payment payment = paymentRepository.findByReservationIdWithLock(reservation.getId())
        .orElseGet(() -> paymentRepository.save(Payment.create(confirmed, reservation)));
    validateExistingPayment(payment, confirmed, reservation);
    if (!paymentTransactionRepository.existsByPaymentOperationId(operation.getId())) {
        paymentTransactionRepository.save(
            PaymentTransaction.confirm(confirmed, reservation, payment, operation.getId()));
    }
    reservation.confirm();
    historyRepository.save(ReservationHistory.ofSystem(
        reservation, ChangeType.STATUS_CHANGE, "결제 성공", "PAYMENT_OPERATION"));
    operation.markApplied(clock.instant());
    publishReservationConfirmedAndIndexChanged(reservation);
}
```

Decline uses the same first two locks, appends one operation-linked `FAIL`, calls `reservation.expireAfterFinalPaymentDecline()`, calls idempotent `couponUsageService.restore(reservation.getId())`, writes bounded history reason `결제 최종 거절: {code}`, marks `DECLINED`, and publishes `RESERVATION_EXPIRED`.

- [ ] **Step 6: Run finalizer and reservation tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationFinalizerIntegrationTest" --tests "kr.kro.airbob.domain.reservation.entity.ReservationTest"`

Expected: PASS including duplicate finalization, opposite result, rollback injection, coupon restore, late provider approval, and no Toss call inside the transaction.

- [ ] **Step 7: Commit atomic finalization**

```bash
git add src/main/java/kr/kro/airbob/domain/payment src/main/java/kr/kro/airbob/domain/reservation/entity/Reservation.java src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepository.java src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationFinalizerIntegrationTest.java src/test/java/kr/kro/airbob/domain/reservation/entity/ReservationTest.java
git commit -m "feat: finalize payment operations atomically"
```

---

### Task 6: Orchestrate provider execution outside transactions

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutor.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutorTest.java`

**Interfaces:**
- Produces: `void execute(UUID operationUid)` with no `@Transactional` annotation.
- Consumes: `PaymentOperationLeaseService`, `PaymentConfirmationGateway`, and `PaymentOperationFinalizer`.

- [ ] **Step 1: Write failing executor tests for every result and failure point**

```java
@Test
void approvedConfirmationFinalizesAfterLeaseTransactionHasClosed() {
    given(leaseService.claim(OPERATION_UID)).willReturn(Optional.of(confirmExecution()));
    given(gateway.confirm(confirmExecution().gatewayCommand()))
        .willReturn(new Approved(confirmedPayment()));

    executor.execute(OPERATION_UID);

    then(finalizer).should().applyApproved(confirmExecution(), confirmedPayment());
    then(leaseService).should(never()).markOutcomeUnknown(any(), any(), any());
}

@Test
void readTimeoutBecomesDurableUnknownAndDoesNotExpireReservation() {
    given(leaseService.claim(OPERATION_UID)).willReturn(Optional.of(confirmExecution()));
    given(gateway.confirm(any())).willReturn(new OutcomeUnknown("READ_TIMEOUT", "response lost"));
    executor.execute(OPERATION_UID);
    then(leaseService).should().markOutcomeUnknown(confirmExecution(), "READ_TIMEOUT", "response lost");
    then(finalizer).shouldHaveNoInteractions();
}

@Test
void unknownInquiryNotFoundReturnsToSafeConfirmRetry() {
    given(leaseService.claim(OPERATION_UID)).willReturn(Optional.of(inquiryExecution()));
    given(gateway.inquire(any())).willReturn(new NotFound("NOT_FOUND_PAYMENT", "not found"));
    executor.execute(OPERATION_UID);
    then(leaseService).should().scheduleRetry(inquiryExecution(), "NOT_FOUND_PAYMENT", "not found");
}

@Test
void finalizerDatabaseFailurePropagatesSoKafkaDoesNotAck() {
    given(leaseService.claim(OPERATION_UID)).willReturn(Optional.of(confirmExecution()));
    given(gateway.confirm(any())).willReturn(new Approved(confirmedPayment()));
    willThrow(new DataAccessResourceFailureException("db unavailable"))
        .given(finalizer).applyApproved(any(), any());
    assertThatThrownBy(() -> executor.execute(OPERATION_UID))
        .isInstanceOf(DataAccessException.class);
}
```

- [ ] **Step 1b: Run the executor tests and verify they fail**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationExecutorTest"`

Expected: FAIL because `PaymentOperationExecutor` does not exist.

Also cover terminal/valid-lease empty claims, final decline, retryable connect failure, correlation mismatch to unknown, unexpected gateway exception to unknown, and stale-worker no-op.

- [ ] **Step 2: Implement the nontransactional execution sequence**

```java
public void execute(UUID operationUid) {
    Optional<PaymentExecution> claimed = leaseService.claim(operationUid);
    if (claimed.isEmpty()) return;
    PaymentExecution execution = claimed.get();

    PaymentGatewayResult result;
    try {
        result = execution.mode() == PaymentExecutionMode.CONFIRM
            ? gateway.confirm(execution.gatewayCommand())
            : gateway.inquire(execution.gatewayCommand());
    } catch (RuntimeException unexpectedGatewayFailure) {
        leaseService.markOutcomeUnknown(
            execution, "UNCLASSIFIED_GATEWAY_FAILURE", sanitize(unexpectedGatewayFailure.getMessage()));
        return;
    }

    dispatchDurableResult(execution, result);
}
```

Keep finalizer calls outside the gateway `try/catch`; database failures must propagate. Before `applyApproved`, verify payment key, order ID, amount, `DONE`, approved timestamp, and method against the execution. A mismatch calls `markOutcomeUnknown(..., "PROVIDER_RESPONSE_MISMATCH", ...)`.

- [ ] **Step 3: Prove the executor itself is not transactional**

Add a reflection assertion that `PaymentOperationExecutor.execute` and its class do not carry `@Transactional`. The lease service and finalizer tests already prove their own short transactions.

- [ ] **Step 4: Run executor tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationExecutorTest"`

Expected: PASS for all five typed results, inquiry-before-reconfirm, correlation mismatch, unexpected gateway exception, and propagated database failure.

- [ ] **Step 5: Commit the executor**

```bash
git add src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutor.java src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationExecutorTest.java
git commit -m "feat: execute payment operations outside transactions"
```

---

### Task 7: Add the dedicated Kafka retry and quarantine boundary

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationAlertService.java`
- Create: `src/main/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumer.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumerTest.java`
- Create: `src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationKafkaIntegrationTest.java`

**Interfaces:**
- Produces: primary listener `handle(String, Acknowledgment)` and `@DltHandler handleDlt(...)`.
- Consumes: Debezium envelope parser and `PaymentOperationExecutor.execute(UUID)`.
- Produces: sanitized alerts containing topic/partition/offset/event type/operation UID only.

- [ ] **Step 1: Write failing unit tests for ACK and poison-message behavior**

```java
@Test
void executesThenAcknowledges() {
    given(parser.getEventType(message)).willReturn(PAYMENT_EXECUTION_REQUESTED_V1.name());
    given(parser.parse(message, PaymentExecutionRequestedV1.class)).willReturn(envelope);
    consumer.handle(message, acknowledgment);
    InOrder order = inOrder(executor, acknowledgment);
    order.verify(executor).execute(OPERATION_UID);
    order.verify(acknowledgment).acknowledge();
}

@Test
void parsingOrUndurableExecutionFailureIsRethrownWithoutAck() {
    given(parser.getEventType(message)).willThrow(
        new DebeziumEventParsingException(new IllegalArgumentException("broken")));
    assertThatThrownBy(() -> consumer.handle(message, acknowledgment))
        .isInstanceOf(DebeziumEventParsingException.class);
    then(acknowledgment).shouldHaveNoInteractions();
}
```

- [ ] **Step 1b: Run the consumer unit test and verify it fails**

Run: `./gradlew test --tests "kr.kro.airbob.kafka.consumer.PaymentOperationEventsConsumerTest"`

Expected: FAIL because the dedicated payment-operation consumer does not exist.

DLT tests assert the alert excludes `paymentKey`, excludes the raw message, includes original topic/partition/offset and parsed operation UID when available, and ACKs even if alert delivery throws because database recovery remains authoritative.

- [ ] **Step 2: Implement retry-topic annotations with configurable test delay**

```java
@RetryableTopic(
    attempts = "${payment.operation.kafka.attempts:4}",
    backoff = @Backoff(delayExpression = "${payment.operation.kafka.backoff-ms:30000}"),
    kafkaTemplate = "deadLetterKafkaTemplate",
    retryTopicSuffix = ".RETRY",
    dltTopicSuffix = ".DLT",
    sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
    dltStrategy = DltStrategy.FAIL_ON_ERROR
)
@KafkaListener(
    topics = "${payment.operation.kafka.topic:PAYMENT_OPERATION.events}",
    groupId = "${payment.operation.kafka.group:payment-operation-execution-group}"
)
public void handle(@Payload String message, Acknowledgment ack) {
    String type = parser.getEventType(message);
    if (EventType.from(type) != PAYMENT_EXECUTION_REQUESTED_V1) {
        throw new IllegalArgumentException("지원하지 않는 payment-operation 이벤트: " + type);
    }
    PaymentExecutionRequestedV1 event = parser
        .parse(message, PaymentExecutionRequestedV1.class).payload();
    executor.execute(event.operationUid());
    ack.acknowledge();
}
```

Do not catch `DebeziumEventParsingException` in the primary listener.

Declare the listener values explicitly:

```yaml
payment:
  operation:
    kafka:
      topic: ${PAYMENT_OPERATION_TOPIC:PAYMENT_OPERATION.events}
      group: ${PAYMENT_OPERATION_GROUP:payment-operation-execution-group}
      attempts: ${PAYMENT_OPERATION_KAFKA_ATTEMPTS:4}
      backoff-ms: ${PAYMENT_OPERATION_KAFKA_BACKOFF_MS:30000}
```

The DLT handler must never forward the raw message to Slack:

```java
@DltHandler
public void handleDlt(
    @Payload String message,
    @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
    @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
    @Header(KafkaHeaders.OFFSET) long offset,
    @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error,
    Acknowledgment ack
) {
    UUID operationUid = tryReadOperationUid(message).orElse(null);
    try {
        alertService.alertQuarantined(topic, partition, offset, operationUid, sanitize(error));
    } catch (RuntimeException alertFailure) {
        log.error("payment-operation DLT 알림 전송 실패. topic={}, partition={}, offset={}",
            topic, partition, offset, alertFailure);
    } finally {
        ack.acknowledge();
    }
}
```

- [ ] **Step 3: Add an Embedded Kafka routing test**

Use `@EmbeddedKafka` and test properties `payment.operation.kafka.backoff-ms=0`, `attempts=2`, and the embedded broker bootstrap servers. Publish a malformed string to `PAYMENT_OPERATION.events`, consume from `PAYMENT_OPERATION.events.DLT`, and assert no record appears in `PAYMENT.events.DLT`. Publish one valid Debezium envelope twice and verify the executor is called twice at the message boundary; lease/executor tests own side-effect deduplication.

- [ ] **Step 4: Run consumer and Kafka integration tests**

Run: `./gradlew test --tests "kr.kro.airbob.kafka.consumer.PaymentOperationEventsConsumerTest" --tests "kr.kro.airbob.kafka.consumer.PaymentOperationKafkaIntegrationTest"`

Expected: PASS; malformed operation messages route only to `PAYMENT_OPERATION.events.DLT` and successful execution ACKs after delegation.

- [ ] **Step 5: Commit the Kafka boundary**

```bash
git add src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationAlertService.java src/main/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumer.java src/main/resources/application.yaml src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumerTest.java src/test/java/kr/kro/airbob/kafka/consumer/PaymentOperationKafkaIntegrationTest.java
git commit -m "feat: isolate payment operation Kafka recovery"
```

---

### Task 8: Recover stale, retryable, and ambiguous work from MySQL

**Files:**
- Create: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryService.java`
- Create: `src/main/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoveryScheduler.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/repository/PaymentOperationRepository.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryServiceIntegrationTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoverySchedulerTest.java`

**Interfaces:**
- Produces: `RecoveryBatch recoverDue()` containing enqueue count and sanitized manual-review notices.
- Produces: `void recoverPaymentOperations()` scheduled every 10 seconds.
- Consumes: `PaymentOperationRepository.findRecoverableForUpdate(now, staleBefore, batchSize)`.

- [ ] **Step 1: Write failing MySQL tests for scheduler authority and concurrent instances**

Create fixtures for stale `READY`, due `RETRY_WAIT`, due `OUTCOME_UNKNOWN`, expired `EXECUTING`, not-due retry, valid lease, and attempt-exhausted unknown. Assert one service call:

```java
RecoveryBatch batch = recoveryService.recoverDue();

assertThat(batch.enqueued()).isEqualTo(4);
assertThat(outboxOperationUids()).containsExactlyInAnyOrder(
    staleReadyUid, retryUid, unknownUid, expiredLeaseUid);
assertThat(reload(expiredLeaseUid).getStatus()).isEqualTo(OUTCOME_UNKNOWN);
assertThat(reload(exhaustedUid).getStatus()).isEqualTo(MANUAL_REVIEW);
assertThat(batch.manualReviews()).extracting(ManualReviewNotice::operationUid)
    .containsExactly(exhaustedUid);
assertThat(outboxOperationUids()).doesNotContain(notDueUid, validLeaseUid, exhaustedUid);
```

Start two transactions concurrently against one due row and assert the sum of returned enqueue counts is one and only one outbox row is created.

- [ ] **Step 1b: Run the recovery integration test and verify it fails**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryServiceIntegrationTest"`

Expected: FAIL because the locked recovery query and recovery service do not exist.

- [ ] **Step 2: Add the locked, rate-limited recovery query**

```java
@Query(value = """
    select * from payment_operation
    where last_enqueued_at <= :staleBefore
      and (
        status = 'READY'
        or (status in ('RETRY_WAIT', 'OUTCOME_UNKNOWN') and next_attempt_at <= :now)
        or (status = 'EXECUTING' and lease_expires_at <= :now)
      )
    order by coalesce(next_attempt_at, lease_expires_at, created_at), id
    limit :batchSize
    for update skip locked
    """, nativeQuery = true)
List<PaymentOperation> findRecoverableForUpdate(
    @Param("now") Instant now,
    @Param("staleBefore") Instant staleBefore,
    @Param("batchSize") int batchSize);
```

Keep this query inside `@Transactional recoverDue()`. The `last_enqueued_at` predicate prevents every application instance from appending the same recovery command every 10 seconds.

- [ ] **Step 3: Implement recovery transitions and outbox publication atomically**

For each locked operation:

1. If `EXECUTING` lease expired, clear the lease and change it to `OUTCOME_UNKNOWN` before publication.
2. If `attemptCount >= maxAttempts` and state is `RETRY_WAIT`, `OUTCOME_UNKNOWN`, or expired `EXECUTING`, mark `MANUAL_REVIEW`, add a sanitized notice, and do not publish.
3. Otherwise save `PAYMENT_EXECUTION_REQUESTED_V1`, then call `recordEnqueued(now)` in the same transaction.
4. Never call Toss or Slack from `recoverDue()`.

- [ ] **Step 4: Implement and test the thin scheduler**

```java
@Scheduled(fixedDelayString = "${payment.operation.scheduler-delay:10s}")
public void recoverPaymentOperations() {
    RecoveryBatch batch = recoveryService.recoverDue();
    batch.manualReviews().forEach(alertService::alertManualReview);
}
```

The unit test directly invokes the method, verifies one service call, and verifies one alert per returned notice. Reflectively assert the `fixedDelayString`. Existing `SchedulingConfig` already disables all scheduling under `test` and `bulk-write-benchmark`; do not add another `@EnableScheduling`.

- [ ] **Step 5: Run recovery tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryServiceIntegrationTest" --tests "kr.kro.airbob.domain.payment.scheduler.PaymentOperationRecoverySchedulerTest" --tests "kr.kro.airbob.config.SchedulingConfigTest"`

Expected: PASS for stale READY republish, inquiry recovery, expired lease conversion, max-attempt manual review, publication throttling, and two-scheduler `SKIP LOCKED` behavior.

- [ ] **Step 6: Commit database-led recovery**

```bash
git add src/main/java/kr/kro/airbob/domain/payment/repository/PaymentOperationRepository.java src/main/java/kr/kro/airbob/domain/payment/entity/PaymentOperation.java src/main/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryService.java src/main/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoveryScheduler.java src/test/java/kr/kro/airbob/domain/payment/service/PaymentOperationRecoveryServiceIntegrationTest.java src/test/java/kr/kro/airbob/domain/payment/scheduler/PaymentOperationRecoverySchedulerTest.java
git commit -m "feat: recover payment operations from database state"
```

---

### Task 9: Remove the legacy confirmation choreography with a clean cutover

**Files:**
- Delete: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentApprovalService.java`
- Delete: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentConfirmationProcessor.java`
- Delete: `src/main/java/kr/kro/airbob/domain/payment/service/PaymentCompensationService.java`
- Delete: corresponding three test classes.
- Rename: `PaymentTransactionService.java` to `PaymentCancellationTransactionService.java` and its test.
- Rename: `PaymentGatewayWorker.java` to `PaymentCancellationGatewayWorker.java` and its test.
- Rename: `PaymentEventsConsumer.java` to `PaymentCancellationEventsConsumer.java` and its test.
- Rename: `PaymentEventTranslator.java` to `PaymentCancellationEventTranslator.java`.
- Modify: `src/main/java/kr/kro/airbob/domain/payment/dto/PaymentRequest.java`
- Modify: `src/main/java/kr/kro/airbob/domain/payment/event/PaymentEvent.java`
- Modify: `src/main/java/kr/kro/airbob/outbox/EventType.java`
- Modify: `src/main/java/kr/kro/airbob/kafka/consumer/ReservationEventsConsumer.java`
- Modify: `src/main/java/kr/kro/airbob/kafka/consumer/DlqConsumer.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/service/ReservationService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/service/ReservationTransactionService.java`
- Modify: `src/test/java/kr/kro/airbob/kafka/consumer/CancellationSagaEventRoutingTest.java`
- Modify: `src/test/java/kr/kro/airbob/kafka/consumer/DlqConsumerTest.java`
- Create: `src/test/java/kr/kro/airbob/kafka/consumer/ReservationEventsConsumerTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/payment/service/PaymentCancellationProcessorTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/reservation/service/ReservationServiceTest.java`
- Modify: `src/test/java/kr/kro/airbob/domain/reservation/service/ReservationTransactionServiceTest.java`
- Modify: `src/test/java/kr/kro/airbob/outbox/EventEnvelopeTest.java`

**Interfaces:**
- Removes: `PAYMENT_CONFIRM_REQUESTED`, `PG_CALL_REQUESTED`, `PG_CALL_SUCCEEDED`, `PG_CALL_FAILED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `RESERVATION_CONFIRM_REQUESTED`, `RESERVATION_EXPIRE_REQUESTED`.
- Preserves: all `PAYMENT_CANCELLATION_*`, `PG_CANCEL_CALL_*`, `RESERVATION_CANCELLATION_*`, `RESERVATION_CONFIRMED`, `RESERVATION_EXPIRED`, and `RESERVATION_CHANGED` behavior.
- Consumes: new confirmation operation flow from Tasks 1–8.

- [ ] **Step 1: Add an architecture test that fails while legacy types remain**

Create a focused assertion in `CancellationSagaEventRoutingTest`:

```java
@Test
void paymentTopicContainsCancellationOnlyAfterOperationCutover() {
    assertThat(Arrays.stream(EventType.values()).map(Enum::name))
        .doesNotContain(
            "PAYMENT_CONFIRM_REQUESTED", "PG_CALL_REQUESTED", "PG_CALL_SUCCEEDED",
            "PG_CALL_FAILED", "PAYMENT_COMPLETED", "PAYMENT_FAILED",
            "RESERVATION_CONFIRM_REQUESTED", "RESERVATION_EXPIRE_REQUESTED")
        .contains(
            "PAYMENT_CANCELLATION_REQUESTED", "PG_CANCEL_CALL_REQUESTED",
            "PG_CANCEL_CALL_SUCCEEDED", "PG_CANCEL_CALL_FAILED");
}
```

- [ ] **Step 1b: Run the architecture test and verify legacy events make it fail**

Run: `./gradlew test --tests "kr.kro.airbob.kafka.consumer.CancellationSagaEventRoutingTest.paymentTopicContainsCancellationOnlyAfterOperationCutover"`

Expected: FAIL because the eight legacy confirmation event names are still present.

- [ ] **Step 2: Remove only confirmation-specific services and methods**

Delete the three obsolete services. From the former `PaymentTransactionService`, remove `processSuccessfulPayment`, `processFailedPayment`, and `processCompensationInTx`; rename the remaining cancellation-only class and update `PaymentCancellationProcessor`. From `ReservationTransactionService`, remove `confirmReservationInTx`, `expireReservationInTx`, `preparePaymentCompensationInTx`, and `findByReservationUidNullable`; remove their `ReservationService` wrappers.

Do not remove reservation terminal fact handling: `ReservationEventsConsumer` must still process `RESERVATION_CONFIRMED` and `RESERVATION_EXPIRED` to remove Redis holds.

- [ ] **Step 3: Remove legacy event shapes, constants, translations, and DLT branches**

`PaymentRequest.Confirm` becomes a plain validated record and no longer implements `EventPayload`. `PaymentEvent` retains only cancellation records. The cancellation translator retains only completed/failed cancellation translation. The global `DlqConsumer` retains cancellation recovery branches and drops all confirmation/compensation dependencies. The dedicated operation consumer owns confirmation quarantine.

- [ ] **Step 4: Rename cancellation-only classes and preserve their focused tests**

Use the exact names `PaymentCancellationTransactionService`, `PaymentCancellationGatewayWorker`, `PaymentCancellationEventsConsumer`, and `PaymentCancellationEventTranslator`. Rename test classes in the same commit so class/file names remain aligned.

- [ ] **Step 5: Prove no legacy confirmation symbol remains**

Run:

```bash
rg -n -g '!CancellationSagaEventRoutingTest.java' "PAYMENT_CONFIRM_REQUESTED|PG_CALL_REQUESTED|PG_CALL_SUCCEEDED|PG_CALL_FAILED|PAYMENT_COMPLETED|PAYMENT_FAILED|RESERVATION_CONFIRM_REQUESTED|RESERVATION_EXPIRE_REQUESTED|PaymentApprovalService|PaymentConfirmationProcessor|PaymentCompensationService" src/main src/test
```

Expected: no output. Keep the architecture test so a later change cannot reintroduce the removed event names unnoticed.

- [ ] **Step 6: Run cancellation and reservation regression tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.service.PaymentCancellationProcessorTest" --tests "kr.kro.airbob.domain.payment.service.PaymentCancellationTransactionServiceTest" --tests "kr.kro.airbob.kafka.consumer.PaymentCancellationGatewayWorkerTest" --tests "kr.kro.airbob.kafka.consumer.PaymentCancellationEventsConsumerTest" --tests "kr.kro.airbob.kafka.consumer.CancellationSagaEventRoutingTest" --tests "kr.kro.airbob.kafka.consumer.DlqConsumerTest" --tests "kr.kro.airbob.kafka.consumer.ReservationEventsConsumerTest" --tests "kr.kro.airbob.domain.reservation.service.ReservationServiceTest" --tests "kr.kro.airbob.domain.reservation.service.ReservationTransactionServiceTest"`

Expected: PASS with cancellation behavior unchanged and reservation terminal hold removal still covered.

- [ ] **Step 7: Commit the clean cutover**

```bash
git add -A src/main/java src/test/java
git commit -m "refactor: remove legacy payment confirmation saga"
```

---

### Task 10: Prove end-to-end convergence and document operations

**Files:**
- Create: `src/test/java/kr/kro/airbob/domain/payment/PaymentOperationFlowIntegrationTest.java`
- Create: `docs/payment-operation-runbook.md`
- Modify: `docs/superpowers/specs/2026-08-14-async-payment-orchestration-design.md` only if implementation names differ; keep behavior unchanged.

**Interfaces:**
- Verifies: API/command → operation/outbox → executor/gateway → atomic finalizer → status API.
- Documents: topic provisioning, nonterminal/manual-review queries, alerts, and rollback boundary.

- [ ] **Step 1: Write the end-to-end integration matrix with a mocked gateway and real MySQL**

Implement these exact test cases in `PaymentOperationFlowIntegrationTest`:

```text
ownerRequest_thenApproved                -> 202/PENDING, then APPLIED/CONFIRMED/SUCCEEDED
outboxFailureDuringAcceptance             -> no operation and reservation remains PAYMENT_PENDING
duplicateHttpAndKafkaDelivery            -> one operation, one payment, one operation ledger effect
connectFailure_thenRetry_thenApproved     -> RETRY_WAIT, then APPLIED
readTimeout_thenInquiryApproved           -> OUTCOME_UNKNOWN, inquiry, then APPLIED
successResponse_thenDbRollback_thenInquiry -> EXECUTING survives, lease expires, inquiry, one APPLIED result
delayedApprovalAfterAcceptedDeadline      -> APPLIED and CONFIRMED, no compensation
finalDeclineWithCoupon                    -> DECLINED, EXPIRED, coupon restored
exhaustedUnknown                          -> MANUAL_REVIEW and REQUIRES_REVIEW
nonOwnerCreateAndRead                     -> 403 and no write/data exposure
```

For every case assert operation status, reservation status, payment count, `payment_transaction` count by operation ID, terminal outbox types, and absence of payment keys in serialized Kafka/status objects.

- [ ] **Step 2: Run the new flow test and all targeted payment-operation tests**

Run: `./gradlew test --tests "kr.kro.airbob.domain.payment.PaymentOperationFlowIntegrationTest" --tests "kr.kro.airbob.domain.payment.*" --tests "kr.kro.airbob.kafka.consumer.PaymentOperation*" --tests "kr.kro.airbob.migration.PaymentOperationMigrationIntegrationTest"`

Expected: PASS.

- [ ] **Step 3: Write the production runbook**

`docs/payment-operation-runbook.md` must contain:

```text
Required topics:
- PAYMENT_OPERATION.events
- PAYMENT_OPERATION.events.RETRY
- PAYMENT_OPERATION.events.DLT

Health queries:
- counts by payment_operation.status
- EXECUTING rows with lease_expires_at < UTC_TIMESTAMP(6)
- READY rows with last_enqueued_at older than 10 seconds
- MANUAL_REVIEW rows with operation_uid, reservation_id, failure_code only

Alerts:
- DLT topic/partition/offset and operation UID
- transition to MANUAL_REVIEW

Rollback boundary:
- before the first accepted operation, an application rollback is safe
- after acceptance, stop new confirmations and drain or explicitly resolve every nonterminal operation before using an older binary
```

Do not include a payment key or example raw Toss body in the runbook.

- [ ] **Step 4: Run static privacy and legacy checks**

Run:

```bash
rg -n "paymentKey|TossPaymentResponse" src/main/java/kr/kro/airbob/domain/payment/event/PaymentOperationEvent.java src/main/java/kr/kro/airbob/kafka/consumer/PaymentOperationEventsConsumer.java src/main/java/kr/kro/airbob/domain/payment/dto/PaymentOperationResponse.java
rg -n "PAYMENT_CONFIRM_REQUESTED|PG_CALL_REQUESTED|PG_CALL_SUCCEEDED|PG_CALL_FAILED|PAYMENT_COMPLETED|PAYMENT_FAILED|RESERVATION_CONFIRM_REQUESTED|RESERVATION_EXPIRE_REQUESTED" src/main
```

Expected: both commands produce no output.

- [ ] **Step 5: Run compile, full tests, and diff hygiene**

Run: `./gradlew compileJava`

Expected: BUILD SUCCESSFUL and QueryDSL generation completes.

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL with the full repository test suite passing.

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL.

Run: `git diff --check`

Expected: no output.

- [ ] **Step 6: Commit the acceptance proof and runbook**

```bash
git add src/test/java/kr/kro/airbob/domain/payment/PaymentOperationFlowIntegrationTest.java docs/payment-operation-runbook.md docs/superpowers/specs/2026-08-14-async-payment-orchestration-design.md
git commit -m "test: prove payment operation convergence"
```

---

## Completion Checklist

- [ ] V17 migrates a fresh MySQL schema and enforces one operation, provider key, deduplication key, payment, and operation-linked ledger effect.
- [ ] Confirmation creation and status reads are authenticated and owner-only.
- [ ] The client receives 202 plus a pollable operation resource.
- [ ] Toss is never called while a database transaction or lock is open.
- [ ] Duplicate HTTP requests and Kafka deliveries converge on one provider/local result.
- [ ] Connect failures retry, response-loss failures inquire, and only the explicit decline allow-list expires a reservation.
- [ ] Accepted operations can complete after the original reservation deadline without an automatic refund path.
- [ ] Stale READY, due retry, unknown outcome, and expired lease all recover from MySQL without relying on Kafka redelivery.
- [ ] Payment-operation DLT handling is isolated from `PAYMENT.events.DLT` and contains no sensitive payload.
- [ ] Final decline restores a coupon in the same local transaction.
- [ ] Legacy confirmation choreography is absent while cancellation regression tests remain green.
- [ ] Targeted tests, full `./gradlew test`, full `./gradlew build`, and `git diff --check` pass.

package kr.kro.airbob.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolution;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionActorType;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;
import kr.kro.airbob.domain.payment.repository.query.JpaPaymentOperationManualReviewQueryRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ClockConfig.class,
	JpaAuditingConfig.class,
	QueryDslConfig.class,
	JpaPaymentOperationManualReviewQueryRepository.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationManualReviewRepositoryIntegrationTest {

	private static final UUID RESERVATION_UID = UUID.fromString("38e09e8d-75f7-4853-b3e9-12c938627c4e");
	private static final UUID ACCOMMODATION_UID = UUID.fromString("87e04b99-b4ae-4c7c-98a4-c1c9d9680320");
	private static final Instant REVIEWED_AT = Instant.parse("2026-08-17T00:00:00Z");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_manual_payment_resolution_repository");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PaymentOperationRepository paymentOperationRepository;
	@Autowired private PaymentOperationResolutionRepository resolutionRepository;
	@Autowired private PaymentOperationManualReviewQueryRepository manualReviewQueryRepository;

	private long memberId;
	private long reservationId;

	@BeforeEach
	void setUpFixture() {
		jdbc.update("DELETE FROM payment_operation_resolution");
		jdbc.update("DELETE FROM payment_operation");
		jdbc.update("DELETE FROM reservation");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");

		jdbc.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('manual-review@test.com', 'manual-review', 'ADMIN', 'ACTIVE', NOW(6))
			""");
		memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO accommodation (
			  member_id, check_in_time, check_out_time, accommodation_uid, updated_at, status, time_zone_id
			) VALUES (?, '15:00:00', '11:00:00', UNHEX(REPLACE(?, '-', '')), NOW(6), 'DRAFT', 'UTC')
			""", memberId, ACCOMMODATION_UID.toString());
		long accommodationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
			INSERT INTO reservation (
			  reservation_uid, accommodation_id, guest_id, check_in_date, check_out_date,
			  check_in_at, check_out_at, time_zone_id, guest_count, total_price, discount_amount,
			  status, reservation_code, created_at, expires_at, updated_at, currency
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, '2026-08-18', '2026-08-19',
			  '2026-08-18 15:00:00', '2026-08-19 11:00:00', 'UTC', 2, 100000, 0,
			  'PAYMENT_PROCESSING', 'REVIEW0001', NOW(6), '2026-08-17 01:00:00', NOW(6), 'KRW'
			)
			""", RESERVATION_UID.toString(), accommodationId, memberId);
		reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	@Test
	void persistsSystemAndAdminAuditActors() {
		UUID operationUid = UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e25");
		insertOperation(operationUid, "MANUAL_REVIEW", REVIEWED_AT, 1);
		PaymentOperation operation = paymentOperationRepository.findByOperationUid(operationUid).orElseThrow();

		resolutionRepository.save(PaymentOperationResolution.recordSystem(
			operation,
			PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
			"Provider state remained inconclusive",
			"case=system",
			PaymentOperationStatus.EXECUTING,
			PaymentOperationStatus.MANUAL_REVIEW,
			REVIEWED_AT
		));
		resolutionRepository.saveAndFlush(PaymentOperationResolution.recordAdmin(
			operation,
			memberId,
			PaymentOperationResolutionAction.RECONCILIATION_REQUESTED,
			"Admin requested provider inquiry",
			"case=admin",
			PaymentOperationStatus.MANUAL_REVIEW,
			PaymentOperationStatus.QUEUED,
			REVIEWED_AT.plusSeconds(1)
		));

		assertThat(resolutionRepository.findAll())
			.extracting(PaymentOperationResolution::getActorType)
			.containsExactlyInAnyOrder(
				PaymentOperationResolutionActorType.SYSTEM,
				PaymentOperationResolutionActorType.ADMIN
			);
	}

	@Test
	void returnsOnlySafeManualReviewFieldsInStableOldestFirstOrder() {
		UUID firstUid = UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e31");
		UUID secondUid = UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e32");
		UUID queuedUid = UUID.fromString("98283dcc-f24f-44b2-a877-d89983fb7e33");
		insertOperation(firstUid, "MANUAL_REVIEW", REVIEWED_AT, 1);
		insertOperation(secondUid, "MANUAL_REVIEW", REVIEWED_AT, 2);
		insertOperation(queuedUid, "QUEUED", null, 0);
		jdbc.update("""
			UPDATE payment_operation
			SET not_paid_resolution_eligible = true
			WHERE operation_uid = UNHEX(REPLACE(?, '-', ''))
			""", firstUid.toString());

		List<PaymentOperationManualReviewQueueItem> items = manualReviewQueryRepository.findOldest(10);

		assertThat(items).extracting(PaymentOperationManualReviewQueueItem::operationUid)
			.containsExactly(firstUid, secondUid);
		assertThat(items).extracting(PaymentOperationManualReviewQueueItem::notPaidResolutionEligible)
			.containsExactly(true, false);
		assertThat(PaymentOperationManualReviewQueueItem.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly(
				"operationUid",
				"operationType",
				"attemptCount",
				"manualReviewCount",
				"reviewRequiredAt",
				"version",
				"notPaidResolutionEligible"
			);
	}

	@Test
	void allowsTheSingleLookAheadRowAndRejectsRequestsOutsideTheInternalBound() {
		for (int index = 0; index < 101; index++) {
			insertOperation(UUID.randomUUID(), "MANUAL_REVIEW", REVIEWED_AT.plusSeconds(index), index + 1);
		}

		assertThat(manualReviewQueryRepository.findOldest(101)).hasSize(101);
		assertThatThrownBy(() -> manualReviewQueryRepository.findOldest(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> manualReviewQueryRepository.findOldest(102))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void insertOperation(UUID operationUid, String status, Instant reviewRequiredAt, int suffix) {
		jdbc.update("""
			INSERT INTO payment_operation (
			  operation_uid, reservation_id, requester_member_id, operation_type, status, next_action,
			  payment_key, expected_amount, provider_idempotency_key, deduplication_key,
			  dispatch_generation, attempt_count, next_attempt_at, queued_at, review_required_at,
			  manual_reconciliation_pending, manual_review_count, version, created_at, updated_at
			) VALUES (
			  UNHEX(REPLACE(?, '-', '')), ?, ?, 'CONFIRM', ?, 'INQUIRE_CONFIRM',
			  ?, 100000, ?, ?,
			  1, 5, NULL, '2026-08-17 00:00:00', ?,
			  false, 1, 0, NOW(6), NOW(6)
			)
			""",
			operationUid.toString(),
			reservationId,
			memberId,
			status,
			"payment-key-" + suffix,
			"provider-key-" + operationUid,
			"CONFIRM:" + operationUid,
			reviewRequiredAt
		);
	}
}

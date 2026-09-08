package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationDetailRow;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({JpaAuditingConfig.class, QueryDslConfig.class, PaymentOperationQueryService.class,
	PaymentOperationReadQueryIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentOperationReadQueryIntegrationTest {

	private static final UUID OPERATION_UID = UUID.fromString("30000000-0000-4000-8000-000000000003");
	private static final UUID RESERVATION_UID = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final Instant NOW = Instant.parse("2026-09-01T10:02:01Z");
	private static final long OWNER_ID = 10L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_operation_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private PaymentOperationQueryService service;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@Autowired private PaymentOperationRepository repository;
	@Autowired private ObjectMapper objectMapper;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (10, 'operation-owner@test.invalid', 'owner', 'MEMBER', 'ACTIVE', NOW(6)),
			       (20, 'reservation-guest@test.invalid', 'guest', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, member_id, accommodation_uid, status,
				check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (30, 10, UUID_TO_BIN(UUID()), 'DRAFT', '15:00:00', '11:00:00', 'UTC', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO reservation (id, reservation_uid, accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, status, expires_at, created_at, updated_at, currency)
			VALUES (40, UUID_TO_BIN(?), 30, 20, '2026-09-02', '2026-09-03',
				'2026-09-02 15:00:00', '2026-09-03 11:00:00', 'UTC', 2, 100000, 0,
				'PAYMENT_PROCESSING', '2026-09-01 10:10:00', '2026-09-01 10:00:00',
				'2026-09-01 10:02:00', 'KRW')
			""", RESERVATION_UID.toString());
		jdbc.update("""
			INSERT INTO payment_operation (id, operation_uid, reservation_id, requester_member_id,
				operation_type, status, next_action, payment_key, expected_amount,
				provider_idempotency_key, deduplication_key, dispatch_generation, attempt_count,
				queued_at, manual_reconciliation_pending, manual_review_count, version, created_at, updated_at)
			VALUES (50, UUID_TO_BIN(?), 40, 10, 'CONFIRM', 'QUEUED', 'CONFIRM',
				'synthetic-payment-key', 100000, 'synthetic-provider-key', 'synthetic-dedupe-key',
				1, 0, '2026-09-01 10:02:00', false, 0, 0, '2026-09-01 10:02:00', '2026-09-01 10:02:00')
			""", OPERATION_UID.toString());
	}

	@Test
	void replacesTwoEntityReadsWithOneElevenColumnProjection() {
		Statistics before = prepareMeasurement();
		var previous = readThroughEntities();
		assertThat(before.getPrepareStatementCount()).isEqualTo(2);
		assertThat(before.getEntityLoadCount()).isEqualTo(2);
		assertThat(selectedColumnCounts()).containsExactly(30, 25);

		Statistics statistics = prepareMeasurement();
		var detail = service.find(OPERATION_UID, OWNER_ID);
		assertThat(detail).isEqualTo(previous);
		assertThat(detail.operationId()).isEqualTo(OPERATION_UID);
		assertThat(detail.orderId()).isEqualTo(RESERVATION_UID);
		assertThat(detail.status()).isEqualTo(PaymentOperationResponse.Status.PENDING);
		assertThat(detail.retryAfterSeconds()).isEqualTo(2L);
		assertSingleProjection(statistics);
	}

	@ParameterizedTest
	@MethodSource("operationStates")
	void preservesAllConfirmationAndCancellationStateResponses(
		PaymentOperationStatus status, PaymentOperationType type
	) {
		setState(status, type);
		prepareMeasurement();
		var previous = readThroughEntities();
		Statistics statistics = prepareMeasurement();
		assertThat(service.find(OPERATION_UID, OWNER_ID)).isEqualTo(previous);
		assertSingleProjection(statistics);
	}

	static Stream<Arguments> operationStates() {
		return Stream.of(PaymentOperationStatus.values())
			.flatMap(status -> Stream.of(PaymentOperationType.values()).map(type -> Arguments.of(status, type)));
	}

	@ParameterizedTest
	@ValueSource(longs = {-1, 0, 1})
	void preservesPendingHoldExpiryAtMicrosecondBoundary(long offsetMicros) {
		setState(PaymentOperationStatus.DECLINED, PaymentOperationType.CONFIRM);
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING', expires_at = ? WHERE id = 40",
			Timestamp.from(NOW.plusNanos(offsetMicros * 1000)));
		Statistics statistics = prepareMeasurement();
		assertThat(service.find(OPERATION_UID, OWNER_ID).nextAction()).isEqualTo(offsetMicros > 0
			? PaymentOperationResponse.NextAction.NONE : PaymentOperationResponse.NextAction.START_NEW_CHECKOUT);
		assertSingleProjection(statistics);
		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = 40", String.class))
			.isEqualTo("PAYMENT_PENDING");
	}

	@ParameterizedTest
	@ValueSource(longs = {-1, 0, 1})
	void preservesCancellationCheckInCutoffAtMicrosecondBoundary(long offsetMicros) {
		setState(PaymentOperationStatus.DECLINED, PaymentOperationType.CANCEL);
		jdbc.update("UPDATE reservation SET check_in_at = ? WHERE id = 40",
			Timestamp.from(NOW.plusNanos(offsetMicros * 1000)));
		Statistics statistics = prepareMeasurement();
		assertThat(service.find(OPERATION_UID, OWNER_ID).nextAction()).isEqualTo(offsetMicros > 0
			? PaymentOperationResponse.NextAction.RETRY_CANCELLATION
			: PaymentOperationResponse.NextAction.CONTACT_SUPPORT);
		assertSingleProjection(statistics);
	}

	@Test
	void preservesMinimumRetryHintWhenNextAttemptIsMissing() {
		setState(PaymentOperationStatus.WAITING_RETRY, PaymentOperationType.CONFIRM);
		jdbc.update("UPDATE payment_operation SET next_attempt_at = NULL WHERE id = 50");
		Statistics statistics = prepareMeasurement();
		assertThat(service.find(OPERATION_UID, OWNER_ID).retryAfterSeconds()).isEqualTo(2L);
		assertSingleProjection(statistics);
	}

	@Test
	void rejectsTheReservationGuestWhenTheyDidNotRequestTheOperation() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.find(OPERATION_UID, 20L))
			.isInstanceOf(PaymentAccessDeniedException.class);
		assertSingleProjection(statistics);
	}

	@Test
	void preservesMissingOperationError() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.find(UUID.randomUUID(), OWNER_ID))
			.isInstanceOf(PaymentOperationNotFoundException.class);
		assertSingleProjection(statistics);
	}

	@Test
	void matchesTheConfirmationContractConsumedByTheFrontend() throws Exception {
		try (var input = new ClassPathResource("contracts/payment-operation-read.json").getInputStream()) {
			for (var scenario : objectMapper.readTree(input)) {
				setState(PaymentOperationStatus.valueOf(scenario.get("operation_status").asText()),
					PaymentOperationType.CONFIRM);
				Statistics statistics = prepareMeasurement();
				var detail = service.find(OPERATION_UID, OWNER_ID);
				assertThat(objectMapper.readTree(objectMapper.writeValueAsString(detail)))
					.isEqualTo(scenario.get("response"));
				assertSingleProjection(statistics);
			}
		}
	}

	private void setState(PaymentOperationStatus status, PaymentOperationType type) {
		jdbc.update("""
			UPDATE payment_operation SET status = ?, operation_type = ?, failure_code = 'SYNTHETIC_DECLINE',
				next_attempt_at = ? WHERE id = 50
			""", status.name(), type.name(), Timestamp.from(NOW.plusSeconds(5).plusNanos(1000)));
		jdbc.update("UPDATE reservation SET status = ? WHERE id = 40",
			type == PaymentOperationType.CONFIRM ? "EXPIRED" : "CANCELLATION_FAILED");
	}

	private PaymentOperationResponse.Detail readThroughEntities() {
		var operation = repository.findByOperationUid(OPERATION_UID).orElseThrow();
		var reservation = operation.getReservation();
		return PaymentOperationResponse.Detail.from(new PaymentOperationDetailRow(
			operation.getOperationUid(), operation.getRequesterMemberId(), operation.getOperationType(),
			operation.getStatus(), operation.getFailureCode(), operation.getUpdatedAt(), operation.getNextAttemptAt(),
			reservation.getReservationUid(), reservation.getStatus(), reservation.getCheckInAt(), reservation.getExpiresAt()
		), NOW);
	}

	private void assertSingleProjection(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCounts()).containsExactly(11);
		assertThat(sqlCapture.statements.getFirst())
			.contains("join reservation")
			.doesNotContain("payment_key", "provider_idempotency_key", "failure_message", "for update");
	}

	private List<Integer> selectedColumnCounts() {
		return sqlCapture.statements.stream()
			.map(sql -> sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length)
			.toList();
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		return statistics;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ReadTestConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		SqlCapture sqlCapture() {
			return new SqlCapture();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(SqlCapture capture) {
			return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
		}
	}

	static class SqlCapture implements StatementInspector {
		private final List<String> statements = new ArrayList<>();

		@Override
		public String inspect(String sql) {
			statements.add(sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim());
			return sql;
		}
	}
}

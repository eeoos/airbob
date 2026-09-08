package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({JpaAuditingConfig.class, QueryDslConfig.class,
	ReservationPaymentReadQueryIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationPaymentReadQueryIntegrationTest {

	private static final UUID RESERVATION_UID = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final Instant NOW = Instant.parse("2026-09-01T10:02:01Z");
	private static final long GUEST_ID = 20L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_reservation_payment_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private PaymentTransactionRepository transactionRepository;
	@Autowired private ObjectMapper objectMapper;
	private ReservationTransactionService service;

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
		service = new ReservationTransactionService(
			mock(AccommodationSearchRefreshPublisher.class), mock(CursorPageInfoCreator.class),
			mock(MemberRepository.class), mock(ReviewRepository.class), paymentRepository, reservationRepository,
			mock(AccommodationRepository.class), transactionRepository, mock(ReservationHistoryRepository.class),
			mock(CouponUsageService.class), mock(BookingWindowProvider.class), ReservationHoldPolicy.defaultPolicy(),
			mock(ReservationQuoteRepository.class), mock(ReservationCheckoutRequestStore.class),
			mock(ReservationInventoryService.class), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationStatus.class, names = {"PAYMENT_PENDING", "PAYMENT_PROCESSING"})
	void pendingCardReservationDoesNotNeedPaymentInfo(ReservationStatus status) {
		jdbc.update("UPDATE reservation SET status = ? WHERE id = 40", status.name());
		Statistics statistics = prepareMeasurement();
		var detail = service.findMyReservationDetail(RESERVATION_UID.toString(), GUEST_ID);
		assertThat(detail.status()).isEqualTo(status);
		assertThat(detail.payment()).isNull();
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
		assertThat(sqlCapture.statements).noneMatch(sql -> sql.contains("payment_transaction"));
		assertThat(detail.paymentAllowed()).isEqualTo(status == ReservationStatus.PAYMENT_PENDING);
	}

	@Test
	void ignoresLegacyIssuanceRowsWithoutDeletingLedgerData() {
		jdbc.update("UPDATE reservation SET status = 'PAYMENT_PENDING' WHERE id = 40");
		insertTransaction(61, "VIRTUAL_ISSUED", null, null, "2026-09-01 10:00:00");
		insertTransaction(62, "FAIL", null, null, "2026-09-01 10:01:00");
		jdbc.update("UPDATE payment_transaction SET virtual_account_number = 'synthetic-account' WHERE id = 61");
		Statistics statistics = prepareMeasurement();

		assertThat(service.findMyReservationDetail(RESERVATION_UID.toString(), GUEST_ID).payment()).isNull();
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
		assertThat(sqlCapture.statements).noneMatch(sql -> sql.contains("payment_transaction"));
		assertThat(transactionRepository.findById(61L).orElseThrow().getTransactionType())
			.isEqualTo(PaymentTransactionType.VIRTUAL_ISSUED);
		assertThat(transactionRepository.findById(61L).orElseThrow().getVirtualAccountNumber())
			.isEqualTo("synthetic-account");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction", Long.class)).isEqualTo(2L);
	}

	@Test
	void keepsCardPaymentAmountsAndAllCancellationHistory() throws Exception {
		jdbc.update("UPDATE reservation SET status = 'CANCELLED' WHERE id = 40");
		jdbc.update("""
			INSERT INTO payment (id, payment_uid, payment_key, order_id, amount, method, approved_at,
				created_at, reservation_id, status, balance_amount, updated_at)
			VALUES (50, UUID_TO_BIN(UUID()), 'synthetic-payment-key', ?, 100000, 'CARD',
				'2026-09-01 09:00:00', '2026-09-01 09:00:00', 40, 'CANCELED', 0, '2026-09-01 10:02:00')
			""", RESERVATION_UID.toString());
		insertTransaction(61, "CONFIRM", 50L, null, "2026-09-01 09:00:00");
		insertTransaction(62, "PARTIAL_CANCEL", 50L, 25000L, "2026-09-01 10:00:00");
		insertTransaction(63, "CANCEL", 50L, 75000L, "2026-09-01 10:01:00");
		insertTransaction(64, "VIRTUAL_ISSUED", null, null, "2026-09-01 08:00:00");
		Statistics statistics = prepareMeasurement();

		var payment = service.findMyReservationDetail(RESERVATION_UID.toString(), GUEST_ID).payment();
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
		assertThat(payment.totalAmount()).isEqualTo(100000L);
		assertThat(payment.balanceAmount()).isZero();
		assertThat(payment.method()).isEqualTo("카드");
		assertThat(payment.cancels()).extracting(cancel -> cancel.cancelAmount()).containsExactly(25000L, 75000L);
		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(payment)).has("virtual_account")).isFalse();
	}

	@Test
	void doesNotReadPaymentOrLedgerForAnotherGuestsReservation() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findMyReservationDetail(RESERVATION_UID.toString(), 999L))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(sqlCapture.statements).noneMatch(sql -> sql.contains(" from payment"));
	}

	@ParameterizedTest
	@CsvSource({"DONE,100001", "PARTIAL_CANCELED,75001", "CANCELED,0"})
	void hostReadsOnlyOriginalPaymentAmountWithoutCancellationHistory(String status, long balanceAmount)
		throws Exception {
		jdbc.update("UPDATE reservation SET status = ? WHERE id = 40",
			"CANCELED".equals(status) ? "CANCELLED" : "CONFIRMED");
		insertPayment(100001L, balanceAmount, status);
		if (balanceAmount < 100001L) {
			insertTransaction(61, "PARTIAL_CANCEL", 50L, 25000L, "2026-09-01 10:00:00");
		}
		if (balanceAmount == 0L) {
			insertTransaction(62, "CANCEL", 50L, 75001L, "2026-09-01 10:01:00");
		}
		Statistics statistics = prepareMeasurement();

		var detail = service.findHostReservationDetail(RESERVATION_UID.toString(), 10L);

		assertThat(detail.payment().totalAmount()).isEqualTo(100001L);
		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(detail.payment())))
			.isEqualTo(objectMapper.readTree("{\"total_amount\":100001}"));
		assertHostProjectionQuery(statistics);
	}

	@Test
	void hostKeepsMissingPaymentAndAddressWithoutReadingLegacyLedger() throws Exception {
		insertTransaction(61, "VIRTUAL_ISSUED", null, null, "2026-09-01 10:00:00");
		jdbc.update("""
			INSERT INTO reservation (id, reservation_uid, accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, status, expires_at, created_at, updated_at, currency)
			SELECT 41, UUID_TO_BIN('20000000-0000-4000-8000-000000000003'), accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, 'CONFIRMED', expires_at, created_at, updated_at, currency
			FROM reservation WHERE id = 40
			""");
		jdbc.update("""
			INSERT INTO payment (id, payment_uid, payment_key, order_id, amount, method, approved_at,
				created_at, reservation_id, status, balance_amount, updated_at)
			VALUES (51, UUID_TO_BIN(UUID()), 'synthetic-other-payment', '20000000-0000-4000-8000-000000000003',
				200000, 'CARD', '2026-09-01 09:00:00', '2026-09-01 09:00:00', 41, 'DONE', 200000, NOW(6))
			""");
		Statistics statistics = prepareMeasurement();

		var detail = service.findHostReservationDetail(RESERVATION_UID.toString(), 10L);
		assertThat(detail.payment()).isNull();
		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(detail.address())))
			.isEqualTo(objectMapper.readTree("""
				{"country":null,"state":null,"city":null,"district":null,"street":null,"detail":null,"postal_code":null}
				"""));
		assertHostProjectionQuery(statistics);
	}

	@Test
	void hostDistinguishesRecordedZeroPaymentFromMissingPayment() {
		insertPayment(0L, 0L, "DONE");
		Statistics statistics = prepareMeasurement();

		assertThat(service.findHostReservationDetail(RESERVATION_UID.toString(), 10L).payment().totalAmount())
			.isZero();
		assertHostProjectionQuery(statistics);
	}

	@ParameterizedTest
	@ValueSource(longs = {GUEST_ID, 999L})
	void anotherMemberCannotReadHostDetail(long memberId) {
		insertPayment(100001L, 100001L, "DONE");
		Statistics statistics = prepareMeasurement();

		assertThatThrownBy(() -> service.findHostReservationDetail(RESERVATION_UID.toString(), memberId))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
	}

	@Test
	void hostGetsNotFoundForUnknownReservation() {
		Statistics statistics = prepareMeasurement();

		assertThatThrownBy(() -> service.findHostReservationDetail(UUID.randomUUID().toString(), 10L))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
	}

	@Test
	void hostProjectionPreservesFullJsonContractAndReservationTimeZone() throws Exception {
		jdbc.update("""
			INSERT INTO address (id, country, state, city, district, street, detail, postal_code, updated_at)
			VALUES (31, '미국', 'New York', 'New York', 'Manhattan', 'Test Street', 'Unit 1', '10001', NOW(6))
			""");
		jdbc.update("""
			UPDATE accommodation SET name = '호스트 계약 숙소', address_id = 31,
				thumbnail_url = '/contract/stay.jpg', time_zone_id = 'Asia/Seoul' WHERE id = 30
			""");
		jdbc.update("""
			UPDATE member SET nickname = '테스트 게스트', thumbnail_image_url = '/contract/guest.jpg' WHERE id = 20
			""");
		jdbc.update("""
			UPDATE reservation SET reservation_code = 'HOST-2026', status = 'CONFIRMED',
				created_at = '2026-09-01 00:00:00', check_in_date = '2026-11-01', check_out_date = '2026-11-03',
				check_in_at = '2026-11-01 04:00:00', check_out_at = '2026-11-03 05:00:00',
				time_zone_id = 'America/New_York', message = '늦은 체크인' WHERE id = 40
			""");
		insertPayment(100001L, 100001L, "DONE");
		Statistics statistics = prepareMeasurement();

		var detail = service.findHostReservationDetail(RESERVATION_UID.toString(), 10L);

		try (var fixture = new ClassPathResource("contracts/host-reservation-stay-payment.json").getInputStream()) {
			ObjectNode expected = (ObjectNode)objectMapper.readTree(fixture);
			expected.put("reservation_uid", RESERVATION_UID.toString()).put("request_message", "늦은 체크인");
			((ObjectNode)expected.get("accommodation")).put("id", 30).put("thumbnail_url", "/contract/stay.jpg");
			((ObjectNode)expected.get("guest")).put("id", 20).put("thumbnail_image_url", "/contract/guest.jpg");
			((ObjectNode)expected.get("address")).put("district", "Manhattan").put("detail", "Unit 1");
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(detail))).isEqualTo(expected);
		}
		assertHostProjectionQuery(statistics);
	}

	private void assertHostProjectionQuery(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(sqlCapture.statements).hasSize(1);
		String sql = sqlCapture.statements.getFirst();
		assertThat(sql.substring("select ".length(), sql.indexOf(" from ")).split(",")).hasSize(23);
		assertThat(sql).contains("left join payment ")
			.doesNotContain("payment_transaction", ".payment_key", ".balance_amount", ".description",
				".latitude", ".longitude", ".updated_at", ".expires_at", ".total_price", ".discount_amount", ".email");
	}

	private void insertPayment(long amount, long balanceAmount, String status) {
		jdbc.update("""
			INSERT INTO payment (id, payment_uid, payment_key, order_id, amount, method, approved_at,
				created_at, reservation_id, status, balance_amount, updated_at)
			VALUES (50, UUID_TO_BIN(UUID()), 'synthetic-payment-key', ?, ?, 'CARD',
				'2026-09-01 09:00:00', '2026-09-01 09:00:00', 40, ?, ?, '2026-09-01 10:02:00')
			""", RESERVATION_UID.toString(), amount, status, balanceAmount);
	}

	private void insertTransaction(long id, String type, Long paymentId, Long cancelAmount, String createdAt) {
		jdbc.update("""
			INSERT INTO payment_transaction (id, reservation_id, payment_id, transaction_type, order_id,
				cancel_amount, cancel_reason, canceled_at, created_at, updated_at)
			VALUES (?, 40, ?, ?, ?, ?, '사용자 요청', ?, ?, ?)
			""", id, paymentId, type, RESERVATION_UID.toString(), cancelAmount, createdAt, createdAt, createdAt);
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

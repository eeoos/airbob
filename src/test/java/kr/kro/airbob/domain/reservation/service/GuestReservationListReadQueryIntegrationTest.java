package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest.CursorPageRequest;
import kr.kro.airbob.cursor.dto.CursorResponse.PageInfo;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse.GuestReservationInfo;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse.GuestReservationInfos;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
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
	CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class,
	GuestReservationListReadQueryIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuestReservationListReadQueryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
	private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-02-01T00:00:00.123456");
	private static final ZoneId RESERVATION_ZONE = ZoneId.of("America/New_York");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_guest_reservation_list");

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
	@Autowired private CursorPageInfoCreator cursorPageInfoCreator;
	@Autowired private CursorDecoder cursorDecoder;
	private ReservationTransactionService service;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, nickname, role, status, thumbnail_image_url, updated_at)
			VALUES (10, 'host', 'MEMBER', 'ACTIVE', null, NOW(6)),
			       (11, 'other host', 'MEMBER', 'ACTIVE', null, NOW(6)),
			       (20, '예약 게스트', 'MEMBER', 'ACTIVE', '/guest.jpg', NOW(6)),
			       (21, '다른 게스트', 'MEMBER', 'ACTIVE', null, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO address (id, country, city, street, updated_at)
			VALUES (35, '대한민국', '서울', 'Test Street', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, member_id, name, accommodation_uid, status, address_id,
				thumbnail_url, check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (30, 10, '목록 숙소', UUID_TO_BIN(UUID()), 'PUBLISHED', 35, '/stay.jpg',
				'15:00:00', '11:00:00', 'Asia/Seoul', NOW(6)),
			       (31, 10, '이전 숙소', UUID_TO_BIN(UUID()), 'UNPUBLISHED', null, null,
				'15:00:00', '11:00:00', 'Asia/Seoul', NOW(6)),
			       (32, 11, '타인 숙소', UUID_TO_BIN(UUID()), 'DRAFT', null, null,
				'15:00:00', '11:00:00', 'Asia/Seoul', NOW(6))
			""");
		service = new ReservationTransactionService(
			mock(AccommodationSearchRefreshPublisher.class), cursorPageInfoCreator,
			mock(MemberRepository.class), mock(ReviewRepository.class), paymentRepository, reservationRepository,
			mock(AccommodationRepository.class), transactionRepository, mock(ReservationHistoryRepository.class),
			mock(CouponUsageService.class), mock(BookingWindowProvider.class), ReservationHoldPolicy.defaultPolicy(),
			mock(ReservationQuoteRepository.class), mock(ReservationCheckoutRequestStore.class),
			mock(ReservationInventoryService.class), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@ParameterizedTest
	@EnumSource(ReservationFilterType.class)
	void preservesFiltersGuestOwnershipTiedCursorsAndLastPage(ReservationFilterType filter) {
		LocalDate checkIn = LocalDate.parse(filter == ReservationFilterType.PAST ? "2026-03-08" : "2026-11-01");
		ReservationStatus status = filter == ReservationFilterType.CANCELLED
			? ReservationStatus.CANCELLED : ReservationStatus.CONFIRMED;
		for (long id = 40; id <= 43; id++) {
			insertReservation(id, id == 40 ? 32 : (id % 2 == 0 ? 30 : 31), 20, status, checkIn);
		}
		jdbc.update("UPDATE reservation SET created_at = ? WHERE id = 40", CREATED_AT.minusSeconds(1));
		insertReservation(44, 30, 21, status, checkIn);
		insertReservation(47, 30, 20, filter == ReservationFilterType.CANCELLED
			? ReservationStatus.CONFIRMED : ReservationStatus.CANCELLED, checkIn);

		GuestReservationInfos first = find(20L, filter, null, 2);
		assertThat(first.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(43L, 42L);
		assertThat(first.pageInfo().hasNext()).isTrue();
		assertThat(first.pageInfo().currentSize()).isEqualTo(2);
		assertThat(first.reservations().getFirst().accommodation().name()).isEqualTo("이전 숙소");
		assertThat(first.reservations().getFirst().accommodation().thumbnailUrl()).isNull();
		assertThat(first.reservations()).allSatisfy(row -> {
			assertThat(row.timeZoneId()).isEqualTo(RESERVATION_ZONE.getId());
			assertThat(row.checkInDate()).isEqualTo(checkIn);
			assertThat(row.checkOutDate()).isEqualTo(checkIn.plusDays(2));
			assertThat(row.status()).isEqualTo(status);
			assertThat(row.createdAt()).isEqualTo(CREATED_AT.toInstant(ZoneOffset.UTC));
		});
		CursorData cursor = cursorDecoder.decode(first.pageInfo().nextCursor(), CursorData.class);
		assertThat(cursor).isEqualTo(new CursorData(42L, CREATED_AT));

		GuestReservationInfos second = find(20L, filter, cursor, 2);
		assertThat(second.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(41L, 40L);
		assertThat(second.reservations().getLast().accommodation().name()).isEqualTo("타인 숙소");
		assertThat(second.pageInfo()).isEqualTo(new PageInfo(false, null, 2));
		GuestReservationInfos empty = find(20L, filter, new CursorData(40L, CREATED_AT.minusSeconds(1)), 2);
		assertThat(empty.reservations()).isEmpty();
		assertThat(empty.pageInfo()).isEqualTo(new PageInfo(false, null, 0));
	}

	@Test
	void expiresPendingAtBoundaryButKeepsProcessingReservation() {
		LocalDate checkIn = LocalDate.parse("2026-11-01");
		for (long id = 50; id <= 52; id++) {
			insertReservation(id, 30, 20, ReservationStatus.PAYMENT_PENDING, checkIn);
		}
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 50", NOW.plusMillis(1));
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 51", NOW);
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 52", NOW.minusMillis(1));
		insertReservation(53, 30, 20, ReservationStatus.PAYMENT_PROCESSING, checkIn);
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 53", NOW.minusSeconds(60));

		GuestReservationInfos upcoming = find(20L, ReservationFilterType.UPCOMING, null, 20);
		assertThat(upcoming.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(53L, 50L);
		assertThat(upcoming.reservations()).extracting(GuestReservationInfo::status)
			.containsExactly(ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAYMENT_PENDING);
		GuestReservationInfos cancelled = find(20L, ReservationFilterType.CANCELLED, null, 20);
		assertThat(cancelled.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(52L, 51L);
		assertThat(cancelled.reservations()).extracting(GuestReservationInfo::status)
			.containsOnly(ReservationStatus.EXPIRED);
		assertThat(jdbc.queryForList("SELECT status FROM reservation WHERE id IN (51,52) ORDER BY id", String.class))
			.containsExactly("PAYMENT_PENDING", "PAYMENT_PENDING");
	}

	@Test
	void preservesExpiredFrontendJsonContractBeforeCleanup() throws Exception {
		insertReservation(41, 30, 20, ReservationStatus.PAYMENT_PENDING, LocalDate.parse("2026-11-01"));
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 41", NOW);
		GuestReservationInfos result = find(20L, ReservationFilterType.CANCELLED, null, 20);

		try (var fixture = new ClassPathResource("contracts/guest-reservation-list-expired.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
	}

	@Test
	void cursorSurvivesDeletedBoundaryAndNewerReservationInsertion() {
		LocalDate checkIn = LocalDate.parse("2026-11-01");
		for (long id = 40; id <= 42; id++) {
			insertReservation(id, 30, 20, ReservationStatus.CONFIRMED, checkIn);
		}
		GuestReservationInfos first = find(20L, ReservationFilterType.UPCOMING, null, 1);
		assertThat(first.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(42L);
		CursorData cursor = cursorDecoder.decode(first.pageInfo().nextCursor(), CursorData.class);
		jdbc.update("DELETE FROM reservation WHERE id = 42");
		insertReservation(43, 30, 20, ReservationStatus.CONFIRMED, checkIn);
		jdbc.update("UPDATE reservation SET created_at = ? WHERE id = 43", CREATED_AT.plusSeconds(1));

		GuestReservationInfos next = find(20L, ReservationFilterType.UPCOMING, cursor, 2);
		assertThat(next.reservations()).extracting(GuestReservationInfo::reservationId).containsExactly(41L, 40L);
		assertThat(next.pageInfo()).isEqualTo(new PageInfo(false, null, 2));
	}

	@Test
	void accommodationHostCannotSeeGuestsReservationAsTheirOwnTrip() {
		insertReservation(41, 30, 20, ReservationStatus.CONFIRMED, LocalDate.parse("2026-11-01"));

		GuestReservationInfos result = find(10L, ReservationFilterType.UPCOMING, null, 20);
		assertThat(result.reservations()).isEmpty();
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 0));
	}

	@Test
	void omittedFilterKeepsAllOwnStatusesAndEffectiveExpiry() {
		long id = 40;
		for (ReservationStatus status : ReservationStatus.values()) {
			insertReservation(id, 30, 20, status,
				LocalDate.parse(id % 2 == 0 ? "2026-03-08" : "2026-11-01"));
			id++;
		}
		insertReservation(54, 31, 20, ReservationStatus.PAYMENT_PENDING, LocalDate.parse("2026-11-01"));
		jdbc.update("UPDATE reservation SET expires_at = ? WHERE id = 54", NOW);
		insertReservation(55, 30, 21, ReservationStatus.CONFIRMED, LocalDate.parse("2026-11-01"));

		GuestReservationInfos result = find(20L, null, null, 20);
		assertThat(result.reservations()).extracting(GuestReservationInfo::status).containsExactlyInAnyOrder(
			ReservationStatus.PAYMENT_PENDING, ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.CONFIRMED,
			ReservationStatus.CANCELLATION_PENDING, ReservationStatus.CANCELLATION_FAILED,
			ReservationStatus.CANCELLED, ReservationStatus.EXPIRED, ReservationStatus.EXPIRED);
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 8));
	}

	private GuestReservationInfos find(Long guestId, ReservationFilterType filter, CursorData cursor, int size) {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		GuestReservationInfos result = service.findMyReservations(guestId,
			new CursorPageRequest(size, cursor == null ? null : cursor.id(),
				cursor == null ? null : cursor.lastCreatedAt()), filter);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(sqlCapture.statements).hasSize(1);
		String sql = sqlCapture.statements.getFirst();
		String select = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(select.split(",")).hasSize(11);
		assertThat(select).contains(".expires_at");
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(sql).doesNotContain(" join address ", "payment_transaction", " join payment ", " join member ",
			".description", ".message", ".total_price", ".discount_amount", ".updated_at", ".created_by");
		return result;
	}

	private void insertReservation(long id, long accommodationId, long guestId, ReservationStatus status, LocalDate checkIn) {
		LocalDate checkOut = checkIn.plusDays(2);
		jdbc.update("""
			INSERT INTO reservation (id, reservation_uid, reservation_code, accommodation_id, guest_id,
				check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
				guest_count, total_price, discount_amount, status, expires_at, created_at, updated_at, currency)
			VALUES (?, UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?, 2, 100001, 19999, ?, ?, ?, ?, 'KRW')
			""", id, uid(id), "LIST-" + id, accommodationId, guestId, checkIn, checkOut,
			checkIn.atTime(LocalTime.of(15, 0)).atZone(RESERVATION_ZONE).toInstant(),
			checkOut.atTime(LocalTime.of(11, 0)).atZone(RESERVATION_ZONE).toInstant(), RESERVATION_ZONE.getId(),
			status.name(), NOW.plusSeconds(60), CREATED_AT, CREATED_AT);
	}

	private static String uid(long id) {
		return "40000000-0000-4000-8000-%012d".formatted(id);
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

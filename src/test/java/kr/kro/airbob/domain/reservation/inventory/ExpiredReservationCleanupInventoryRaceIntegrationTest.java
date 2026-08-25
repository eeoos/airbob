package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(ExpiredReservationCleanupInventoryRaceIntegrationTest.FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("만료 cleanup과 날짜 재고 checkout의 실제 MySQL 경합")
class ExpiredReservationCleanupInventoryRaceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate STAY_START = LocalDate.of(2026, 9, 10);
	private static final LocalDate STAY_END = STAY_START.plusDays(2);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_inventory_cleanup_race")
		.withCommand("--log-bin-trust-function-creators=1");

	@Container
	private static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
			.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired private ReservationService reservationService;
	@Autowired private ReservationQuoteService quoteService;
	@Autowired private ExpiredReservationCleanupService cleanupService;
	@MockitoSpyBean private ReservationInventoryService inventoryService;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private OutboxMessageRepository outboxRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private AddressRepository addressRepository;
	@Autowired private OccupancyPolicyRepository occupancyPolicyRepository;
	@Autowired private CouponRepository couponRepository;
	@Autowired private MemberCouponRepository memberCouponRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private io.awspring.cloud.s3.S3Template s3Template;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;

	private Accommodation accommodation;
	private List<Member> guests;

	@BeforeEach
	void setUp() {
		given(bookingWindowProvider.currentFor(eq(TIME_ZONE_ID), any(Instant.class)))
			.willReturn(BookingWindow.startingOn(WINDOW_START));
		clearRows();
		createFixture();
		seedCalendar();
	}

	@AfterEach
	void tearDown() {
		clearRows();
	}

	@Test
	@DisplayName("READ COMMITTED cleanup이 예약 행을 잠근 동안 checkout은 takeover 후 먼저 커밋한다")
	void checkoutCanCommitWhileCleanupHoldsExpiredReservationRow() throws Exception {
		ExpiredHold expired = createExpiredHold(guests.get(0), "expired-race-owner");
		CountDownLatch cleanupReachedInventoryRelease = new CountDownLatch(1);
		CountDownLatch allowCleanupInventoryRelease = new CountDownLatch(1);
		AtomicReference<String> cleanupIsolation = new AtomicReference<>();
		ReservationInventoryService inventoryTarget =
			AopTestUtils.getUltimateTargetObject(inventoryService);
		doAnswer(invocation -> {
			cleanupIsolation.set(jdbcTemplate.queryForObject(
				"SELECT @@transaction_isolation", String.class));
			cleanupReachedInventoryRelease.countDown();
			await(allowCleanupInventoryRelease);
			return invocation.callRealMethod();
		}).when(inventoryTarget).releaseHeldIfOwned(
			eq(accommodation.getId()),
			eq(STAY_START),
			eq(STAY_END),
			eq(expired.reservationId())
		);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<Integer> cleanup = null;
		Future<ReservationResponse.Ready> replacementCheckout = null;
		ReservationResponse.Ready replacement;
		try {
			cleanup = executor.submit(cleanupService::cleanupExpiredPendingReservations);
			assertThat(cleanupReachedInventoryRelease.await(10, TimeUnit.SECONDS)).isTrue();

			replacementCheckout = executor.submit(() -> checkout(
				guests.get(1), null, "replacement-race-owner"));
			replacement = replacementCheckout.get(5, TimeUnit.SECONDS);

			assertThat(cleanup).isNotDone();
			allowCleanupInventoryRelease.countDown();
			assertThat(cleanup.get(10, TimeUnit.SECONDS)).isOne();
		} finally {
			allowCleanupInventoryRelease.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(cleanupIsolation.get()).isEqualTo("READ-COMMITTED");
		Long replacementId = reservationId(replacement);
		assertCleanupAndReplacementState(expired, replacementId);
	}

	@Test
	@DisplayName("takeover 뒤 늦은 cleanup은 새 HOLD owner를 해제하지 않는다")
	void staleCleanupNeverReleasesReplacementOwner() {
		ExpiredHold expired = createExpiredHold(guests.get(0), "expired-stale-owner");
		ReservationResponse.Ready replacement = checkout(
			guests.get(1), null, "replacement-stale-owner");
		Long replacementId = reservationId(replacement);

		assertThat(cleanupService.cleanupExpiredPendingReservations()).isOne();

		assertCleanupAndReplacementState(expired, replacementId);
	}

	private ExpiredHold createExpiredHold(Member guest, String idempotencyKey) {
		Coupon coupon = issueFixedCoupon(guest, 10_000);
		ReservationResponse.Ready checkout = checkout(guest, coupon.getId(), idempotencyKey);
		Long reservationId = reservationId(checkout);
		jdbcTemplate.update(
			"UPDATE reservation SET expires_at = ? WHERE id = ?", NOW, reservationId);
		jdbcTemplate.update("""
			UPDATE accommodation_inventory_day
			SET hold_expires_at = ?
			WHERE reservation_id = ?
			""", NOW, reservationId);
		return new ExpiredHold(reservationId, coupon.getId());
	}

	private ReservationResponse.Ready checkout(
		Member guest,
		Long couponId,
		String idempotencyKey
	) {
		ReservationResponse.Quote quote = quoteService.createQuote(new ReservationRequest.Quote(
			accommodation.getId(),
			STAY_START,
			STAY_END,
			2,
			couponId
		), guest.getId());
		return reservationService.createPendingReservation(
			new ReservationRequest.Checkout(quote.quoteUid(), "inventory cleanup race"),
			guest.getId(),
			idempotencyKey);
	}

	private Long reservationId(ReservationResponse.Ready checkout) {
		return reservationRepository.findByReservationUid(
			UUID.fromString(checkout.reservationUid())).orElseThrow().getId();
	}

	private void assertCleanupAndReplacementState(ExpiredHold expired, Long replacementId) {
		Reservation expiredReservation = reservationRepository.findById(
			expired.reservationId()).orElseThrow();
		Reservation replacement = reservationRepository.findById(replacementId).orElseThrow();
		assertThat(expiredReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(replacement.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(reservationRepository.count()).isEqualTo(2L);

		assertThat(jdbcTemplate.queryForList("""
			SELECT state, reservation_id, hold_expires_at
			FROM accommodation_inventory_day
			WHERE accommodation_id = ?
			  AND stay_date >= ?
			  AND stay_date < ?
			ORDER BY stay_date
			""", accommodation.getId(), STAY_START, STAY_END))
			.hasSize(2)
			.allSatisfy(row -> {
				assertThat(row.get("state")).isEqualTo("HOLD");
				assertThat(((Number)row.get("reservation_id")).longValue())
					.isEqualTo(replacementId);
				assertThat(row.get("hold_expires_at")).isNotNull();
			});

		MemberCoupon restoredCoupon = memberCouponRepository.findByMemberIdAndCouponId(
			guests.get(0).getId(), expired.couponId()).orElseThrow();
		assertThat(restoredCoupon.isUsed()).isFalse();
		assertThat(restoredCoupon.getReservationId()).isEqualTo(expired.reservationId());
		assertThat(historyRepository.count()).isEqualTo(3L);
		assertThat(jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM reservation_history
			WHERE reservation_id = ?
			  AND status = 'EXPIRED'
			  AND change_type = 'STATUS_CHANGE'
			  AND source_system = 'BATCH'
			""", Long.class, expired.reservationId())).isOne();
	}

	private Coupon issueFixedCoupon(Member member, int discountAmount) {
		LocalDateTime couponNow = LocalDateTime.now(ZoneId.of(TIME_ZONE_ID));
		Coupon coupon = couponRepository.save(Coupon.builder()
			.name(discountAmount + "원 할인")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(discountAmount)
			.issueStartAt(couponNow.minusDays(1))
			.issueEndAt(couponNow.plusDays(1))
			.usableFrom(couponNow.minusDays(1))
			.usableUntil(couponNow.plusDays(1))
			.isActive(true)
			.issuedQuantity(1)
			.build());
		memberCouponRepository.save(MemberCoupon.issue(member, coupon));
		return coupon;
	}

	private void seedCalendar() {
		for (LocalDate date = STAY_START; date.isBefore(STAY_END); date = date.plusDays(1)) {
			jdbcTemplate.update("""
				INSERT INTO accommodation_inventory_day (accommodation_id, stay_date, state)
				VALUES (?, ?, 'FREE')
				""", accommodation.getId(), date);
		}
	}

	private void createFixture() {
		Member host = memberRepository.save(
			Member.builder().email("cleanup-race-host@test.com")
				.nickname("cleanup-race-host").build());
		guests = List.of(
			memberRepository.save(Member.builder()
				.email("cleanup-race-old@test.com").nickname("cleanup-race-old").build()),
			memberRepository.save(Member.builder()
				.email("cleanup-race-new@test.com").nickname("cleanup-race-new").build())
		);
		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Inventory cleanup race accommodation")
			.basePrice(100_000L)
			.currency("KRW")
			.address(Address.builder().country("KR").build())
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).build())
			.member(host)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.build());
	}

	private void clearRows() {
		outboxRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		jdbcTemplate.update("DELETE FROM reservation_checkout_request");
		jdbcTemplate.update("DELETE FROM reservation_quote");
		memberCouponRepository.deleteAllInBatch();
		jdbcTemplate.update("DELETE FROM accommodation_inventory_day");
		reservationRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		occupancyPolicyRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for cleanup race gate");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("cleanup race interrupted", exception);
		}
	}

	private record ExpiredHold(Long reservationId, Long couponId) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock cleanupInventoryRaceClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}

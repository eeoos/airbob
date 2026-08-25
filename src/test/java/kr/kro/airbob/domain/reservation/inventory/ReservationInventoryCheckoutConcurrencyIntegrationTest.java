package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.kro.airbob.common.exception.BaseException;
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
import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(ReservationInventoryCheckoutConcurrencyIntegrationTest.FixedClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실제 MySQL 날짜 재고 checkout 동시성")
class ReservationInventoryCheckoutConcurrencyIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate STAY_START = LocalDate.of(2026, 9, 10);
	private static final Duration FAST_BUSY_LIMIT = Duration.ofSeconds(2);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_inventory_checkout_concurrency")
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
	@Autowired private PlatformTransactionManager transactionManager;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private io.awspring.cloud.s3.S3Template s3Template;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;

	private TransactionTemplate transactionTemplate;
	private Accommodation accommodation;
	private List<Member> guests;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		given(bookingWindowProvider.currentFor(eq(TIME_ZONE_ID), any(Instant.class)))
			.willReturn(BookingWindow.startingOn(WINDOW_START));
		clearRows();
		createFixture();
	}

	@AfterEach
	void tearDown() {
		clearRows();
	}

	@Test
	@DisplayName("같은 날짜의 두 checkout은 최대 한 예약만 점유한다")
	void sameDateCompetingCheckoutsHaveOneWinner() throws Exception {
		LocalDate checkOut = STAY_START.plusDays(2);
		seedCalendar(STAY_START, checkOut);

		List<CheckoutAttempt> attempts = runSimultaneously(
			request(STAY_START, checkOut), guests.get(0), "same-date-a",
			request(STAY_START, checkOut), guests.get(1), "same-date-b");

		assertSingleWinner(attempts);
		assertThat(reservationRepository.count()).isOne();
		assertThat(heldDayCount(STAY_START, checkOut)).isEqualTo(2L);
		assertThat(distinctOwnerCount(STAY_START, checkOut)).isOne();
	}

	@Test
	@DisplayName("일부 날짜가 겹치는 checkout도 범위 일부를 남기지 않고 하나만 성공한다")
	void partiallyOverlappingCheckoutsAreAtomic() throws Exception {
		LocalDate firstEnd = STAY_START.plusDays(3);
		LocalDate secondStart = STAY_START.plusDays(2);
		LocalDate secondEnd = STAY_START.plusDays(5);
		seedCalendar(STAY_START, secondEnd);

		List<CheckoutAttempt> attempts = runSimultaneously(
			request(STAY_START, firstEnd), guests.get(0), "partial-overlap-a",
			request(secondStart, secondEnd), guests.get(1), "partial-overlap-b");

		assertSingleWinner(attempts);
		assertThat(reservationRepository.count()).isOne();
		assertThat(heldDayCount(STAY_START, secondEnd)).isEqualTo(3L);
		assertThat(distinctOwnerCount(STAY_START, secondEnd)).isOne();
		assertThat(freeDayCount(STAY_START, secondEnd)).isEqualTo(2L);
	}

	@Test
	@DisplayName("같은 숙소의 겹치지 않는 날짜는 다른 checkout commit을 기다리지 않는다")
	void disjointRangesOnSameAccommodationProceedIndependently() throws Exception {
		LocalDate firstEnd = STAY_START.plusDays(2);
		LocalDate secondEnd = STAY_START.plusDays(4);
		seedCalendar(STAY_START, secondEnd);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch firstPrepared = new CountDownLatch(1);
		CountDownLatch allowFirstCommit = new CountDownLatch(1);

		Future<ReservationResponse.Ready> first = executor.submit(() ->
			transactionTemplate.execute(status -> {
				ReservationResponse.Ready ready = checkout(
					request(STAY_START, firstEnd), guests.get(0), "disjoint-a");
				firstPrepared.countDown();
				await(allowFirstCommit);
				return ready;
			}));

		assertThat(firstPrepared.await(10, TimeUnit.SECONDS)).isTrue();
		Future<ReservationResponse.Ready> second = executor.submit(() -> checkout(
			request(firstEnd, secondEnd), guests.get(1), "disjoint-b"));

		try {
			assertThat(second.get(2, TimeUnit.SECONDS).reservationUid()).isNotBlank();
		} finally {
			allowFirstCommit.countDown();
		}
		assertThat(first.get(10, TimeUnit.SECONDS).reservationUid()).isNotBlank();
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertThat(reservationRepository.count()).isEqualTo(2L);
		assertThat(heldDayCount(STAY_START, secondEnd)).isEqualTo(4L);
		assertThat(distinctOwnerCount(STAY_START, secondEnd)).isEqualTo(2L);
	}

	@Test
	@DisplayName("달력 행이 하나라도 없으면 R026이고 예약·쿠폰·이력이 모두 롤백된다")
	void missingInventoryDayFailsClosedWithoutSideEffects() {
		LocalDate checkOut = STAY_START.plusDays(3);
		seedDay(STAY_START);
		seedDay(STAY_START.plusDays(2));
		Coupon coupon = issueFixedCoupon(guests.get(0), 10_000);
		ReservationCreateCommand request = new ReservationCreateCommand(
			accommodation.getId(), STAY_START, checkOut, 2, coupon.getId(), "missing inventory day");

		Throwable failure = catchThrowable(() -> checkout(request, guests.get(0), "missing-day"));

		assertDomainCode(failure, "R026");
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
		assertThat(memberCouponRepository.findByMemberIdAndCouponId(
			guests.get(0).getId(), coupon.getId()).orElseThrow().isUsed()).isFalse();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_checkout_request", Long.class)).isZero();
		assertThat(freeDayCount(STAY_START, checkOut)).isEqualTo(2L);
	}

	@Test
	@DisplayName("다른 트랜잭션이 날짜 행을 보유하면 checkout은 기다리지 않고 R025를 반환한다")
	void lockedInventoryDayReturnsBusyImmediately() throws Exception {
		LocalDate checkOut = STAY_START.plusDays(2);
		seedCalendar(STAY_START, checkOut);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch inventoryLocked = new CountDownLatch(1);
		CountDownLatch releaseInventory = new CountDownLatch(1);

		Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			jdbcTemplate.queryForObject("""
				SELECT stay_date
				FROM accommodation_inventory_day
				WHERE accommodation_id = ? AND stay_date = ?
				FOR UPDATE
				""", LocalDate.class, accommodation.getId(), STAY_START);
			inventoryLocked.countDown();
			await(releaseInventory);
		}));
		assertThat(inventoryLocked.await(10, TimeUnit.SECONDS)).isTrue();

		Future<TimedFailure> checkout = executor.submit(() -> {
			long startedAt = System.nanoTime();
			Throwable failure = catchThrowable(() -> checkout(
				request(STAY_START, checkOut), guests.get(0), "nowait-busy"));
			return new TimedFailure(failure, Duration.ofNanos(System.nanoTime() - startedAt));
		});

		TimedFailure result;
		try {
			result = checkout.get(FAST_BUSY_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		} finally {
			releaseInventory.countDown();
		}
		lockHolder.get(10, TimeUnit.SECONDS);
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertDomainCode(result.failure(), "R025");
		assertThat(result.elapsed()).isLessThan(FAST_BUSY_LIMIT);
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(freeDayCount(STAY_START, checkOut)).isEqualTo(2L);
	}

	@Test
	@DisplayName("숙소 수정 트랜잭션이 행을 보유하면 checkout의 공유 잠금도 기다리지 않고 R025를 반환한다")
	void lockedAccommodationReturnsBusyImmediately() throws Exception {
		LocalDate checkOut = STAY_START.plusDays(2);
		seedCalendar(STAY_START, checkOut);
		ReservationRequest.Checkout request = checkoutRequest(
			request(STAY_START, checkOut), guests.get(0));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch accommodationLocked = new CountDownLatch(1);
		CountDownLatch releaseAccommodation = new CountDownLatch(1);

		Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			accommodationRepository.findByIdForUpdate(accommodation.getId()).orElseThrow();
			accommodationLocked.countDown();
			await(releaseAccommodation);
		}));
		assertThat(accommodationLocked.await(10, TimeUnit.SECONDS)).isTrue();

		Future<TimedFailure> checkout = executor.submit(() -> {
			long startedAt = System.nanoTime();
			Throwable failure = catchThrowable(() -> reservationService.createPendingReservation(
				request, guests.get(0).getId(), "accommodation-nowait-busy"));
			return new TimedFailure(failure, Duration.ofNanos(System.nanoTime() - startedAt));
		});

		TimedFailure result;
		try {
			result = checkout.get(FAST_BUSY_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		} finally {
			releaseAccommodation.countDown();
		}
		lockHolder.get(10, TimeUnit.SECONDS);
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertDomainCode(result.failure(), "R025");
		assertThat(result.elapsed()).isLessThan(FAST_BUSY_LIMIT);
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(freeDayCount(STAY_START, checkOut)).isEqualTo(2L);
	}

	private List<CheckoutAttempt> runSimultaneously(
		ReservationCreateCommand firstRequest,
		Member firstGuest,
		String firstKey,
		ReservationCreateCommand secondRequest,
		Member secondGuest,
		String secondKey
	) throws Exception {
		ReservationRequest.Checkout firstCheckout = checkoutRequest(firstRequest, firstGuest);
		ReservationRequest.Checkout secondCheckout = checkoutRequest(secondRequest, secondGuest);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Future<CheckoutAttempt> first = executor.submit(() ->
			attemptAfterBarrier(firstCheckout, firstGuest, firstKey, ready, start));
		Future<CheckoutAttempt> second = executor.submit(() ->
			attemptAfterBarrier(secondCheckout, secondGuest, secondKey, ready, start));

		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		try {
			return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private CheckoutAttempt attemptAfterBarrier(
		ReservationRequest.Checkout request,
		Member guest,
		String key,
		CountDownLatch ready,
		CountDownLatch start
	) {
		ready.countDown();
		await(start);
		try {
			return CheckoutAttempt.success(reservationService.createPendingReservation(
				request, guest.getId(), key));
		} catch (Throwable failure) {
			return CheckoutAttempt.failure(failure);
		}
	}

	private ReservationResponse.Ready checkout(
		ReservationCreateCommand request,
		Member guest,
		String key
	) {
		return reservationService.createPendingReservation(
			checkoutRequest(request, guest), guest.getId(), key);
	}

	private ReservationRequest.Checkout checkoutRequest(
		ReservationCreateCommand request,
		Member guest
	) {
		ReservationResponse.Quote quote = quoteService.createQuote(new ReservationRequest.Quote(
			request.accommodationId(),
			request.checkInDate(),
			request.checkOutDate(),
			request.guestCount(),
			request.couponId()
		), guest.getId());
		return new ReservationRequest.Checkout(quote.quoteUid(), request.requestMessage());
	}

	private void assertSingleWinner(List<CheckoutAttempt> attempts) {
		assertThat(attempts).filteredOn(CheckoutAttempt::succeeded).hasSize(1);
		assertThat(attempts).filteredOn(attempt -> !attempt.succeeded()).singleElement()
			.satisfies(attempt -> {
				assertThat(attempt.failure()).isInstanceOf(BaseException.class);
				assertThat(domainCode(attempt.failure())).isIn("R002", "R025");
			});
	}

	private void assertDomainCode(Throwable failure, String expectedCode) {
		assertThat(failure).isInstanceOf(BaseException.class);
		assertThat(domainCode(failure)).isEqualTo(expectedCode);
	}

	private String domainCode(Throwable failure) {
		return ((BaseException)failure).getErrorCode().getCode();
	}

	private ReservationCreateCommand request(LocalDate checkIn, LocalDate checkOut) {
		return new ReservationCreateCommand(accommodation.getId(), checkIn, checkOut, 2);
	}

	private void seedCalendar(LocalDate startInclusive, LocalDate endExclusive) {
		for (LocalDate date = startInclusive; date.isBefore(endExclusive); date = date.plusDays(1)) {
			seedDay(date);
		}
	}

	private void seedDay(LocalDate date) {
		jdbcTemplate.update("""
			INSERT INTO accommodation_inventory_day (accommodation_id, stay_date, state)
			VALUES (?, ?, 'FREE')
			""", accommodation.getId(), date);
	}

	private long heldDayCount(LocalDate startInclusive, LocalDate endExclusive) {
		return inventoryCount("state = 'HOLD'", startInclusive, endExclusive);
	}

	private long freeDayCount(LocalDate startInclusive, LocalDate endExclusive) {
		return inventoryCount("state = 'FREE'", startInclusive, endExclusive);
	}

	private long inventoryCount(
		String condition,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		return jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM accommodation_inventory_day
			WHERE accommodation_id = ?
			  AND stay_date >= ?
			  AND stay_date < ?
			  AND """ + " " + condition,
			Long.class,
			accommodation.getId(),
			startInclusive,
			endExclusive);
	}

	private long distinctOwnerCount(LocalDate startInclusive, LocalDate endExclusive) {
		return jdbcTemplate.queryForObject("""
			SELECT COUNT(DISTINCT reservation_id)
			FROM accommodation_inventory_day
			WHERE accommodation_id = ?
			  AND stay_date >= ?
			  AND stay_date < ?
			""", Long.class, accommodation.getId(), startInclusive, endExclusive);
	}

	private Coupon issueFixedCoupon(Member member, int discountAmount) {
		LocalDateTime couponNow = LocalDateTime.ofInstant(NOW, ZoneId.of(TIME_ZONE_ID));
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

	private void createFixture() {
		Member host = memberRepository.save(
			Member.builder().email("inventory-host@test.com").nickname("inventory-host").build());
		guests = List.of(
			memberRepository.save(Member.builder()
				.email("inventory-guest-a@test.com").nickname("inventory-guest-a").build()),
			memberRepository.save(Member.builder()
				.email("inventory-guest-b@test.com").nickname("inventory-guest-b").build())
		);
		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Inventory concurrency accommodation")
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
				throw new IllegalStateException("timed out waiting for concurrent test gate");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("concurrent test interrupted", exception);
		}
	}

	private record CheckoutAttempt(ReservationResponse.Ready ready, Throwable failure) {
		private static CheckoutAttempt success(ReservationResponse.Ready ready) {
			return new CheckoutAttempt(ready, null);
		}

		private static CheckoutAttempt failure(Throwable failure) {
			return new CheckoutAttempt(null, failure);
		}

		private boolean succeeded() {
			return ready != null;
		}
	}

	private record TimedFailure(Throwable failure, Duration elapsed) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock inventoryCheckoutClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}

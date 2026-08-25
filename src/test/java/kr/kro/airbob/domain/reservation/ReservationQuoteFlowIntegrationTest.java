package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteAlreadyCheckedOutException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteExpiredException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteStaleException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(ReservationQuoteFlowIntegrationTest.QuoteFlowClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실제 MySQL 예약 견적·V2 checkout 흐름")
class ReservationQuoteFlowIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate BOOKING_WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 10);
	private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 13);
	private static final long NIGHTLY_PRICE = 120_000L;
	private static final long SUBTOTAL = NIGHTLY_PRICE * 3;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_reservation_quote_flow")
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

	@Autowired private AdjustableClock clock;
	@Autowired private ReservationQuoteService quoteService;
	@Autowired private ReservationService reservationService;
	@Autowired private ReservationQuoteRepository quoteRepository;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private OutboxMessageRepository outboxRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private AddressRepository addressRepository;
	@Autowired private OccupancyPolicyRepository occupancyPolicyRepository;
	@Autowired private CouponRepository couponRepository;
	@Autowired private MemberCouponRepository memberCouponRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private io.awspring.cloud.s3.S3Template s3Template;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;

	private Member guest;
	private Accommodation accommodation;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		given(bookingWindowProvider.currentFor(TIME_ZONE_ID, NOW))
			.willReturn(BookingWindow.startingOn(BOOKING_WINDOW_START));
		clearRows();
		createFixture();
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS reservation_quote_checkout_reject_history");
		clearRows();
	}

	@Test
	@DisplayName("견적 생성은 가격을 저장하지만 예약·이력·outbox·쿠폰을 점유하지 않는다")
	void quoteCreationDoesNotHoldInventoryOrConsumeCoupon() {
		Coupon coupon = issueFixedCoupon(30_000);

		ReservationResponse.Quote response = createQuote(coupon.getId());

		assertThat(response.inventoryHeld()).isFalse();
		assertThat(response.nightlyPrice()).isEqualTo(NIGHTLY_PRICE);
		assertThat(response.nights()).isEqualTo(3L);
		assertThat(response.subtotal()).isEqualTo(SUBTOTAL);
		assertThat(response.discountAmount()).isEqualTo(30_000L);
		assertThat(response.amount()).isEqualTo(330_000L);
		assertThat(response.quoteExpiresAt()).isEqualTo(NOW.plusSeconds(5 * 60));
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
		assertThat(memberCoupon(coupon).isUsed()).isFalse();

		ReservationQuote persisted = quote(response.quoteUid());
		assertThat(persisted.getReservationId()).isNull();
		assertThat(persisted.getCheckedOutAt()).isNull();
	}

	@Test
	@DisplayName("V2 checkout은 견적을 재검증한 뒤 정확히 15분 결제 hold를 시작한다")
	void checkoutStartsFifteenMinutePaymentHold() {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Quote quote = createQuote(coupon.getId());

		ReservationResponse.Ready ready = checkout(quote, "quote-checkout-hold", "늦은 체크인 예정입니다");

		assertThat(ready.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(ready.holdExpiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
		assertThat(ready.serverTime()).isEqualTo(NOW);
		assertThat(ready.subtotal()).isEqualTo(SUBTOTAL);
		assertThat(ready.discountAmount()).isEqualTo(30_000L);
		assertThat(ready.amount()).isEqualTo(330_000L);
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(memberCoupon(coupon).isUsed()).isTrue();

		ReservationQuote persisted = quote(quote.quoteUid());
		assertThat(persisted.getReservationId()).isNotNull();
		assertThat(persisted.getCheckedOutAt()).isEqualTo(NOW);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT expires_at FROM reservation WHERE id = ?",
			Timestamp.class,
			persisted.getReservationId()).toInstant())
			.isEqualTo(NOW.plusSeconds(15 * 60));
	}

	@Test
	@DisplayName("성공한 checkout은 quote 만료 뒤에도 같은 멱등성 키로 기존 예약을 재생한다")
	void successfulCheckoutReplaysAfterQuoteExpiry() {
		ReservationResponse.Quote quote = createQuote(null);
		String idempotencyKey = "quote-replay-after-expiry";
		String requestMessage = "동일 요청 재시도";
		ReservationResponse.Ready first = checkout(quote, idempotencyKey, requestMessage);
		clock.set(quote.quoteExpiresAt());

		ReservationResponse.Ready replayed = checkout(quote, idempotencyKey, requestMessage);

		assertThat(replayed.reservationUid()).isEqualTo(first.reservationUid());
		assertThat(replayed.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(checkoutRequestCount()).isOne();
	}

	@Test
	@DisplayName("견적 만료 시각과 정확히 같아진 checkout은 거절하고 아무 상태도 소비하지 않는다")
	void checkoutAtExactQuoteExpiryIsRejected() {
		ReservationResponse.Quote quote = createQuote(null);
		clock.set(quote.quoteExpiresAt());

		assertThatThrownBy(() -> checkout(quote, "quote-expiry-boundary", null))
			.isInstanceOf(ReservationQuoteExpiredException.class);

		assertQuoteAndCheckoutRemainUnconsumed(quote.quoteUid());
	}

	@Test
	@DisplayName("checkout이 숙소 mutex를 기다리는 동안 quote가 만료되면 전체를 롤백한다")
	void quoteExpiryWhileWaitingForInventoryRollsBackCheckout() throws Exception {
		ReservationResponse.Quote quote = createQuote(null);
		given(bookingWindowProvider.currentFor(TIME_ZONE_ID, quote.quoteExpiresAt()))
			.willReturn(BookingWindow.startingOn(BOOKING_WINDOW_START));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			accommodationRepository.findByIdForUpdate(accommodation.getId()).orElseThrow();
			lockAcquired.countDown();
			await(releaseLock);
		}));
		assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

		Future<ReservationResponse.Ready> checkout = executor.submit(() ->
			checkout(quote, "quote-expires-while-waiting", null));
		try {
			assertThatThrownBy(() -> checkout.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);
			clock.set(quote.quoteExpiresAt());
		} finally {
			releaseLock.countDown();
		}

		try {
			assertThatThrownBy(() -> checkout.get(10, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(ReservationQuoteExpiredException.class);
			lockHolder.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertQuoteAndCheckoutRemainUnconsumed(quote.quoteUid());
	}

	@Test
	@DisplayName("다른 회원의 quote는 존재하지 않는 quote와 같은 오류로 거절한다")
	void checkoutDoesNotRevealAnotherMembersQuote() {
		ReservationResponse.Quote quote = createQuote(null);
		Member outsider = memberRepository.save(
			Member.builder().email("quote-outsider@test.com").nickname("quote-outsider").build());

		assertThatThrownBy(() -> reservationService.createPendingReservation(
			checkoutRequest(quote, null), outsider.getId(), "cross-member-quote"))
			.isInstanceOf(ReservationQuoteNotFoundException.class);

		assertQuoteAndCheckoutRemainUnconsumed(quote.quoteUid());
	}

	@Test
	@DisplayName("quote 생성 뒤 날짜가 점유되면 checkout은 quote·쿠폰·멱등성 키를 소비하지 않는다")
	void inventoryClaimedAfterQuoteCreationRejectsCheckoutAtomically() {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Quote quote = createQuote(coupon.getId());
		Member competitor = memberRepository.save(
			Member.builder().email("quote-competitor@test.com").nickname("quote-competitor").build());
		reservationService.createPendingReservation(
			new ReservationRequest.Create(
				accommodation.getId(), CHECK_IN, CHECK_OUT, 2, null, null),
			competitor.getId(),
			"competing-reservation"
		);

		assertThatThrownBy(() -> checkout(quote, "quote-inventory-conflict", null))
			.isInstanceOf(ReservationConflictException.class);

		ReservationQuote persisted = quote(quote.quoteUid());
		assertThat(persisted.getReservationId()).isNull();
		assertThat(persisted.getCheckedOutAt()).isNull();
		assertThat(memberCoupon(coupon).isUsed()).isFalse();
		assertThat(checkoutRequestCount()).isZero();
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
	}

	@Test
	@DisplayName("견적 이후 숙소 가격이 바뀌면 stale로 거절하고 checkout 전체를 롤백한다")
	void priceChangeRejectsStaleQuoteAndRollsBack() {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Quote quote = createQuote(coupon.getId());
		jdbcTemplate.update(
			"UPDATE accommodation SET base_price = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
			NIGHTLY_PRICE + 10_000,
			accommodation.getId());

		assertThatThrownBy(() -> checkout(quote, "quote-stale-price", null))
			.isInstanceOf(ReservationQuoteStaleException.class);

		assertQuoteAndCheckoutRemainUnconsumed(quote.quoteUid());
		assertThat(memberCoupon(coupon).isUsed()).isFalse();
	}

	@Test
	@DisplayName("동일한 quote와 멱등성 키의 동시 checkout은 하나의 예약 UID로 수렴한다")
	void concurrentSameQuoteAndKeyConvergesOnOneReservation() throws InterruptedException {
		int threadCount = 8;
		ReservationResponse.Quote quote = createQuote(null);
		ReservationRequest.Checkout request = checkoutRequest(quote, "동시 checkout");
		String idempotencyKey = "same-quote-concurrent-key";
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<String> reservationUids = java.util.Collections.synchronizedList(new ArrayList<>());
		List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

		try {
			for (int index = 0; index < threadCount; index++) {
				executor.submit(() -> {
					try {
						ready.countDown();
						start.await();
						reservationUids.add(reservationService.createPendingReservation(
							request, guest.getId(), idempotencyKey).reservationUid());
					} catch (Throwable failure) {
						failures.add(failure);
					} finally {
						done.countDown();
					}
				});
			}

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(failures).isEmpty();
		assertThat(reservationUids).hasSize(threadCount).containsOnly(reservationUids.getFirst());
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(checkoutRequestCount()).isOne();
		assertThat(quote(quote.quoteUid()).getReservationId()).isNotNull();
	}

	@Test
	@DisplayName("이미 checkout된 quote를 다른 멱등성 키로 사용하면 충돌한다")
	void consumedQuoteWithDifferentKeyConflicts() {
		ReservationResponse.Quote quote = createQuote(null);
		ReservationResponse.Ready first = checkout(quote, "quote-first-checkout", null);

		assertThatThrownBy(() -> checkout(quote, "quote-second-checkout", null))
			.isInstanceOf(ReservationQuoteAlreadyCheckedOutException.class);

		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(checkoutRequestCount()).isOne();
		assertThat(quote(quote.quoteUid()).getReservationId())
			.isEqualTo(reservationId(first.reservationUid()));
	}

	@Test
	@DisplayName("checkout 트랜잭션 실패는 quote·멱등성 키·쿠폰을 소비하지 않아 같은 키로 재시도할 수 있다")
	void failedCheckoutLeavesQuoteAndIdempotencyUnconsumed() {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Quote quote = createQuote(coupon.getId());
		String idempotencyKey = "quote-checkout-rollback";
		jdbcTemplate.execute("""
			CREATE TRIGGER reservation_quote_checkout_reject_history
			BEFORE INSERT ON reservation_history
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced quote checkout failure'
			""");

		try {
			assertThatThrownBy(() -> checkout(quote, idempotencyKey, "rollback retry"))
				.isInstanceOf(DataAccessException.class);
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS reservation_quote_checkout_reject_history");
		}

		assertQuoteAndCheckoutRemainUnconsumed(quote.quoteUid());
		assertThat(memberCoupon(coupon).isUsed()).isFalse();

		ReservationResponse.Ready retried = checkout(quote, idempotencyKey, "rollback retry");

		assertThat(retried.reservationUid()).isNotBlank();
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(checkoutRequestCount()).isOne();
		assertThat(memberCoupon(coupon).isUsed()).isTrue();
	}

	private void createFixture() {
		Member host = memberRepository.save(
			Member.builder().email("quote-host@test.com").nickname("quote-host").build());
		guest = memberRepository.save(
			Member.builder().email("quote-guest@test.com").nickname("quote-guest").build());
		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Quote flow accommodation")
			.basePrice(NIGHTLY_PRICE)
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

	private Coupon issueFixedCoupon(int discountAmount) {
		Coupon coupon = couponRepository.save(Coupon.builder()
			.name(discountAmount + "원 할인")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(discountAmount)
			.issueStartAt(LocalDateTime.of(2025, 1, 1, 0, 0))
			.issueEndAt(LocalDateTime.of(2030, 1, 1, 0, 0))
			.usableFrom(LocalDateTime.of(2025, 1, 1, 0, 0))
			.usableUntil(LocalDateTime.of(2030, 1, 1, 0, 0))
			.isActive(true)
			.issuedQuantity(1)
			.build());
		memberCouponRepository.save(MemberCoupon.issue(guest, coupon));
		return coupon;
	}

	private ReservationResponse.Quote createQuote(Long couponId) {
		return quoteService.createQuote(new ReservationRequest.Quote(
			accommodation.getId(),
			CHECK_IN,
			CHECK_OUT,
			2,
			couponId
		), guest.getId());
	}

	private ReservationResponse.Ready checkout(
		ReservationResponse.Quote quote,
		String idempotencyKey,
		String requestMessage
	) {
		return reservationService.createPendingReservation(
			checkoutRequest(quote, requestMessage),
			guest.getId(),
			idempotencyKey
		);
	}

	private ReservationRequest.Checkout checkoutRequest(
		ReservationResponse.Quote quote,
		String requestMessage
	) {
		return new ReservationRequest.Checkout(quote.quoteUid(), requestMessage);
	}

	private ReservationQuote quote(UUID quoteUid) {
		return quoteRepository.findByQuoteUid(quoteUid).orElseThrow();
	}

	private MemberCoupon memberCoupon(Coupon coupon) {
		return memberCouponRepository.findByMemberIdAndCouponId(guest.getId(), coupon.getId())
			.orElseThrow();
	}

	private long checkoutRequestCount() {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_checkout_request WHERE endpoint = 'RESERVATION_CHECKOUT_V2'",
			Long.class);
	}

	private Long reservationId(String reservationUid) {
		return jdbcTemplate.queryForObject(
			"SELECT id FROM reservation WHERE reservation_uid = UUID_TO_BIN(?)",
			Long.class,
			reservationUid);
	}

	private void assertQuoteAndCheckoutRemainUnconsumed(UUID quoteUid) {
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_quote "
				+ "WHERE quote_uid = UUID_TO_BIN(?) AND reservation_id IS NULL AND checked_out_at IS NULL",
			Long.class,
			quoteUid.toString())).isOne();
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(checkoutRequestCount()).isZero();
	}

	private void clearRows() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS reservation_quote_checkout_reject_history");
		jdbcTemplate.update("DELETE FROM reservation_checkout_request");
		quoteRepository.deleteAllInBatch();
		outboxRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		memberCouponRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
		occupancyPolicyRepository.deleteAllInBatch();
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for test lock release");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for test lock release", exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class QuoteFlowClockConfiguration {
		@Bean
		@Primary
		AdjustableClock reservationQuoteFlowClock() {
			return new AdjustableClock(NOW);
		}
	}

	static final class AdjustableClock extends Clock {
		private final AtomicReference<Instant> instant;

		private AdjustableClock(Instant instant) {
			this.instant = new AtomicReference<>(instant);
		}

		void set(Instant instant) {
			this.instant.set(instant);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.fixed(instant(), zone);
		}

		@Override
		public Instant instant() {
			return instant.get();
		}
	}
}

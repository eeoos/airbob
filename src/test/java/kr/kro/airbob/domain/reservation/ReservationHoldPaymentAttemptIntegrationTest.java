package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException;
import kr.kro.airbob.domain.reservation.exception.ReservationHoldReleaseNotAllowedException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptTooLateException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(ReservationHoldPaymentAttemptIntegrationTest.HoldClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("실제 MySQL 예약 hold 해제와 V2 결제 시도 경계")
class ReservationHoldPaymentAttemptIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate BOOKING_WINDOW_START = LocalDate.of(2026, 8, 25);
	private static final LocalDate FIRST_CHECK_IN = LocalDate.of(2026, 9, 10);
	private static final long NIGHTLY_PRICE = 120_000L;
	private static final long BLOCK_ASSERTION_MILLIS = 500L;
	private static final String FAILURE_TRIGGER = "reservation_attempt_reject_payment_operation";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_reservation_hold_payment_attempt")
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
	@Autowired private ReservationHoldCommandService holdCommandService;
	@Autowired private ExpiredReservationCleanupService cleanupService;
	@Autowired private PaymentOperationCommandService paymentCommandService;
	@Autowired private ReservationQuoteRepository quoteRepository;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private PaymentOperationRepository paymentOperationRepository;
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

	private TransactionTemplate transactionTemplate;
	private Member guest;
	private Member outsider;
	private Accommodation accommodation;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		given(bookingWindowProvider.currentFor(TIME_ZONE_ID, NOW))
			.willReturn(BookingWindow.startingOn(BOOKING_WINDOW_START));
		transactionTemplate = new TransactionTemplate(transactionManager);
		clearRows();
		createFixture();
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER);
		clearRows();
	}

	@Test
	@DisplayName("hold 해제가 먼저 커밋되면 대기하던 결제 승인은 상태 변경 없이 거절된다")
	void releaseCommitsFirst_confirmationIsRejected() throws Exception {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Ready ready = createV2Hold(0, coupon.getId(), "release-wins");
		ReservationResponse.PaymentAttemptReady attempt = beginAttempt(ready);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch releasePrepared = new CountDownLatch(1);
		CountDownLatch allowReleaseCommit = new CountDownLatch(1);

		try {
			Future<ReservationResponse.HoldRelease> release = executor.submit(() ->
				transactionTemplate.execute(status -> {
					ReservationResponse.HoldRelease response = holdCommandService.releaseHold(
						ready.reservationUid(), guest.getId());
					releasePrepared.countDown();
					awaitRelease(allowReleaseCommit);
					return response;
				}));
			awaitPrepared(releasePrepared, release);

			Future<Accepted> confirmation = executor.submit(() ->
				paymentCommandService.requestConfirmation(confirmRequest(attempt, "release-wins-key"), guest.getId()));
			try {
				assertBlocked(confirmation);
			} finally {
				allowReleaseCommit.countDown();
			}

			assertThat(release.get(10, TimeUnit.SECONDS).releasedNow()).isTrue();
			assertFutureFailedWith(confirmation, ExpiredReservationConfirmationException.class);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		Reservation persisted = reservation(ready.reservationUid());
		assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(persisted.getPaymentAttemptConsumedAt()).isNull();
		assertThat(paymentOperationRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
		assertThat(memberCoupon(coupon).isUsed()).isFalse();
		assertThat(expiredHistoryCount(persisted.getId())).isOne();
	}

	@Test
	@DisplayName("결제 승인이 먼저 커밋되면 대기하던 hold 해제는 거절된다")
	void confirmationCommitsFirst_releaseIsRejected() throws Exception {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Ready ready = createV2Hold(0, coupon.getId(), "confirmation-wins");
		ReservationResponse.PaymentAttemptReady attempt = beginAttempt(ready);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch confirmationPrepared = new CountDownLatch(1);
		CountDownLatch allowConfirmationCommit = new CountDownLatch(1);

		try {
			Future<Accepted> confirmation = executor.submit(() ->
				transactionTemplate.execute(status -> {
					Accepted accepted = paymentCommandService.requestConfirmation(
						confirmRequest(attempt, "confirmation-wins-key"), guest.getId());
					confirmationPrepared.countDown();
					awaitRelease(allowConfirmationCommit);
					return accepted;
				}));
			awaitPrepared(confirmationPrepared, confirmation);

			Future<ReservationResponse.HoldRelease> release = executor.submit(() ->
				holdCommandService.releaseHold(ready.reservationUid(), guest.getId()));
			try {
				assertBlocked(release);
			} finally {
				allowConfirmationCommit.countDown();
			}

			assertThat(confirmation.get(10, TimeUnit.SECONDS).operationId()).isNotNull();
			assertFutureFailedWith(release, ReservationHoldReleaseNotAllowedException.class);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		Reservation persisted = reservation(ready.reservationUid());
		assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		assertThat(persisted.getPaymentAttemptConsumedAt()).isEqualTo(NOW);
		assertThat(paymentOperationRepository.count()).isOne();
		assertThat(outboxRepository.count()).isOne();
		assertThat(memberCoupon(coupon).isUsed()).isTrue();
		assertThat(expiredHistoryCount(persisted.getId())).isZero();
	}

	@Test
	@DisplayName("만료 cleanup이 먼저 잠그면 hold 해제는 커밋 뒤 멱등 응답으로 수렴한다")
	void cleanupCommitsFirst_releaseReplaysExpiredState() throws Exception {
		Coupon coupon = issueFixedCoupon(30_000);
		ReservationResponse.Ready ready = createV2Hold(0, coupon.getId(), "cleanup-wins");
		clock.set(ready.holdExpiresAt());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch cleanupPrepared = new CountDownLatch(1);
		CountDownLatch allowCleanupCommit = new CountDownLatch(1);

		try {
			Future<Integer> cleanup = executor.submit(() -> transactionTemplate.execute(status -> {
				int cleaned = cleanupService.cleanupExpiredPendingReservations();
				cleanupPrepared.countDown();
				awaitRelease(allowCleanupCommit);
				return cleaned;
			}));
			awaitPrepared(cleanupPrepared, cleanup);

			Future<ReservationResponse.HoldRelease> release = executor.submit(() ->
				holdCommandService.releaseHold(ready.reservationUid(), guest.getId()));
			try {
				assertBlocked(release);
			} finally {
				allowCleanupCommit.countDown();
			}

			assertThat(cleanup.get(10, TimeUnit.SECONDS)).isOne();
			ReservationResponse.HoldRelease replayed = release.get(10, TimeUnit.SECONDS);
			assertThat(replayed.status()).isEqualTo(ReservationStatus.EXPIRED);
			assertThat(replayed.releasedNow()).isFalse();
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		Reservation persisted = reservation(ready.reservationUid());
		assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(memberCoupon(coupon).isUsed()).isFalse();
		assertThat(expiredHistoryCount(persisted.getId())).isOne();
	}

	@Test
	@DisplayName("동시 결제 시작 요청은 하나의 미소비 토큰으로 수렴한다")
	void concurrentBeginConvergesOnOneToken() throws InterruptedException {
		ReservationResponse.Ready ready = createV2Hold(0, null, "concurrent-begin");
		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch prepared = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<UUID> attemptIds = java.util.Collections.synchronizedList(new ArrayList<>());
		List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

		try {
			for (int index = 0; index < threadCount; index++) {
				executor.submit(() -> {
					try {
						prepared.countDown();
						start.await();
						attemptIds.add(beginAttempt(ready).paymentAttemptId());
					} catch (Throwable failure) {
						failures.add(failure);
					} finally {
						done.countDown();
					}
				});
			}

			assertThat(prepared.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(failures).isEmpty();
		assertThat(attemptIds).hasSize(threadCount).containsOnly(attemptIds.getFirst());
		Reservation persisted = reservation(ready.reservationUid());
		assertThat(persisted.getPaymentAttemptUid()).isEqualTo(attemptIds.getFirst());
		assertThat(persisted.getPaymentAttemptStartedAt()).isEqualTo(NOW);
		assertThat(persisted.getPaymentAttemptConsumedAt()).isNull();
		assertThat(paymentOperationRepository.count()).isZero();
	}

	@Test
	@DisplayName("최초 결제 시작은 정확히 90초 남으면 허용하고 89.999초면 거절하며 hold를 연장하지 않는다")
	void beginHonorsExactCutoffWithoutExtendingHold() {
		ReservationResponse.Ready exactBoundary = createV2Hold(0, null, "exact-boundary");
		ReservationResponse.Ready belowBoundary = createV2Hold(7, null, "below-boundary");

		clock.set(exactBoundary.holdExpiresAt().minusSeconds(90));
		ReservationResponse.PaymentAttemptReady issued = beginAttempt(exactBoundary);

		assertThat(issued.holdExpiresAt()).isEqualTo(exactBoundary.holdExpiresAt());
		assertThat(issued.remainingSeconds()).isEqualTo(90L);
		assertThat(reservation(exactBoundary.reservationUid()).getExpiresAt())
			.isEqualTo(exactBoundary.holdExpiresAt());

		clock.set(belowBoundary.holdExpiresAt().minusSeconds(90).plusMillis(1));
		assertThatThrownBy(() -> beginAttempt(belowBoundary))
			.isInstanceOf(ReservationPaymentAttemptTooLateException.class);

		Reservation rejected = reservation(belowBoundary.reservationUid());
		assertThat(rejected.getExpiresAt()).isEqualTo(belowBoundary.holdExpiresAt());
		assertThat(rejected.getPaymentAttemptUid()).isNull();
	}

	@Test
	@DisplayName("결제 승인 트랜잭션 롤백은 V2 토큰 소비도 되돌려 같은 토큰으로 재시도할 수 있다")
	void confirmationRollbackLeavesAttemptReusable() {
		ReservationResponse.Ready ready = createV2Hold(0, null, "confirm-rollback");
		ReservationResponse.PaymentAttemptReady attempt = beginAttempt(ready);
		PaymentRequest.Confirm request = confirmRequest(attempt, "rollback-payment-key");
		jdbcTemplate.execute("""
			CREATE TRIGGER reservation_attempt_reject_payment_operation
			BEFORE INSERT ON payment_operation
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced payment operation failure'
			""");

		try {
			assertThatThrownBy(() -> paymentCommandService.requestConfirmation(request, guest.getId()))
				.isInstanceOf(DataAccessException.class);
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER);
		}

		Reservation rolledBack = reservation(ready.reservationUid());
		assertThat(rolledBack.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(rolledBack.getPaymentAttemptUid()).isEqualTo(attempt.paymentAttemptId());
		assertThat(rolledBack.getPaymentAttemptConsumedAt()).isNull();
		assertThat(paymentOperationRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();

		Accepted retried = paymentCommandService.requestConfirmation(request, guest.getId());

		assertThat(retried.operationId()).isNotNull();
		Reservation succeeded = reservation(ready.reservationUid());
		assertThat(succeeded.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		assertThat(succeeded.getPaymentAttemptConsumedAt()).isEqualTo(NOW);
		assertThat(paymentOperationRepository.count()).isOne();
		assertThat(outboxRepository.count()).isOne();
	}

	@Test
	@DisplayName("hold API는 다른 회원의 예약과 존재하지 않는 예약을 같은 not-found로 숨긴다")
	void holdCommandsDoNotRevealAnotherMembersReservation() {
		ReservationResponse.Ready ready = createV2Hold(0, null, "privacy-hold");
		String unknownUid = UUID.randomUUID().toString();

		assertThatThrownBy(() -> holdCommandService.releaseHold(ready.reservationUid(), outsider.getId()))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThatThrownBy(() -> holdCommandService.releaseHold(unknownUid, outsider.getId()))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThatThrownBy(() -> holdCommandService.beginPaymentAttempt(ready.reservationUid(), outsider.getId()))
			.isInstanceOf(ReservationNotFoundException.class);
		assertThatThrownBy(() -> holdCommandService.beginPaymentAttempt(unknownUid, outsider.getId()))
			.isInstanceOf(ReservationNotFoundException.class);

		Reservation persisted = reservation(ready.reservationUid());
		assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(persisted.getPaymentAttemptUid()).isNull();
	}

	private void createFixture() {
		Member host = memberRepository.save(
			Member.builder().email("hold-host@test.com").nickname("hold-host").build());
		guest = memberRepository.save(
			Member.builder().email("hold-guest@test.com").nickname("hold-guest").build());
		outsider = memberRepository.save(
			Member.builder().email("hold-outsider@test.com").nickname("hold-outsider").build());
		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Hold flow accommodation")
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

	private ReservationResponse.Ready createV2Hold(int stayOffsetDays, Long couponId, String idempotencyKey) {
		LocalDate checkIn = FIRST_CHECK_IN.plusDays(stayOffsetDays);
		ReservationResponse.Quote quote = quoteService.createQuote(new ReservationRequest.Quote(
			accommodation.getId(),
			checkIn,
			checkIn.plusDays(3),
			2,
			couponId
		), guest.getId());
		return reservationService.createPendingReservation(
			new ReservationRequest.Checkout(quote.quoteUid(), null),
			guest.getId(),
			idempotencyKey
		);
	}

	private ReservationResponse.PaymentAttemptReady beginAttempt(ReservationResponse.Ready ready) {
		return holdCommandService.beginPaymentAttempt(ready.reservationUid(), guest.getId());
	}

	private PaymentRequest.Confirm confirmRequest(
		ReservationResponse.PaymentAttemptReady attempt,
		String paymentKey
	) {
		return new PaymentRequest.Confirm(
			paymentKey,
			attempt.orderId(),
			Math.toIntExact(attempt.amount()),
			attempt.paymentAttemptId()
		);
	}

	private Reservation reservation(String reservationUid) {
		return reservationRepository.findByReservationUid(UUID.fromString(reservationUid)).orElseThrow();
	}

	private MemberCoupon memberCoupon(Coupon coupon) {
		return memberCouponRepository.findByMemberIdAndCouponId(guest.getId(), coupon.getId())
			.orElseThrow();
	}

	private long expiredHistoryCount(Long reservationId) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND status = 'EXPIRED'",
			Long.class,
			reservationId
		);
	}

	private void clearRows() {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER);
		jdbcTemplate.update("DELETE FROM payment_operation_resolution");
		jdbcTemplate.update("DELETE FROM payment_transaction");
		jdbcTemplate.update("DELETE FROM payment");
		outboxRepository.deleteAllInBatch();
		paymentOperationRepository.deleteAllInBatch();
		jdbcTemplate.update("DELETE FROM reservation_checkout_request");
		quoteRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		memberCouponRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
		occupancyPolicyRepository.deleteAllInBatch();
	}

	private void assertBlocked(Future<?> future) {
		assertThatThrownBy(() -> future.get(BLOCK_ASSERTION_MILLIS, TimeUnit.MILLISECONDS))
			.isInstanceOf(TimeoutException.class);
	}

	private void awaitPrepared(CountDownLatch prepared, Future<?> worker) throws Exception {
		if (!prepared.await(10, TimeUnit.SECONDS)) {
			if (worker.isDone()) {
				worker.get(1, TimeUnit.SECONDS);
			}
			throw new AssertionError("transaction did not reach the prepared state");
		}
	}

	private void assertFutureFailedWith(
		Future<?> future,
		Class<? extends Throwable> expectedCause
	) {
		assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(expectedCause);
	}

	private void awaitRelease(CountDownLatch release) {
		try {
			if (!release.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting to release transaction");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("transaction wait interrupted", exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class HoldClockConfiguration {
		@Bean
		@Primary
		AdjustableClock reservationHoldClock() {
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

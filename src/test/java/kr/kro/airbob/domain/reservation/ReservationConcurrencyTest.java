package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.service.PaymentCancellationCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckoutIdempotencyConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationConcurrencyTest {

	private static final int THREAD_COUNT = 50;
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 12);
	private static final BookingWindow BOOKING_WINDOW = BookingWindow.startingOn(WINDOW_START);

	@Autowired
	private ReservationService reservationService;
	@Autowired
	private ReservationTransactionService transactionService;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private AccommodationRepository accommodationRepository;
	@Autowired
	private AddressRepository addressRepository;
	@Autowired
	private ReservationHistoryRepository historyRepository;
	@Autowired
	private PaymentOperationCommandService paymentOperationCommandService;
	@Autowired
	private PaymentCancellationCommandService paymentCancellationCommandService;
	@Autowired
	private PaymentOperationRepository paymentOperationRepository;
	@Autowired
	private PaymentRepository paymentRepository;
	@Autowired
	private PaymentTransactionRepository paymentTransactionRepository;
	@Autowired
	private OutboxMessageRepository outboxRepository;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private MemberCouponRepository memberCouponRepository;
	@Autowired
	private ExpiredReservationCleanupService cleanupService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ElasticsearchClient elasticsearchClient;
	@MockitoBean
	private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean
	private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean
	private io.awspring.cloud.s3.S3Template s3Template;
	@MockitoBean
	private BookingWindowProvider bookingWindowProvider;

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test")
		.withCommand("--log-bin-trust-function-creators=1");

	@Container
	private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.datasource.username", mySQLContainer::getUsername);
		registry.add("spring.datasource.password", mySQLContainer::getPassword);
		registry.add("spring.flyway.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.flyway.user", mySQLContainer::getUsername);
		registry.add("spring.flyway.password", mySQLContainer::getPassword);
		registry.add("spring.data.redis.host", redisContainer::getHost);
		registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379).toString());
	}

	private Accommodation accommodation;
	private List<Member> guests;

	@BeforeEach
	void setUp() {
		given(bookingWindowProvider.currentFor(eq(TIME_ZONE_ID), any(Instant.class)))
			.willReturn(BOOKING_WINDOW);
		jdbcTemplate.update("DELETE FROM reservation_checkout_request");
		outboxRepository.deleteAllInBatch();
		paymentTransactionRepository.deleteAllInBatch();
		paymentRepository.deleteAllInBatch();
		paymentOperationRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		memberCouponRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();

		Member host = memberRepository.save(Member.builder().email("host@test.com").nickname("Host").build());

		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Test Accommodation")
			.basePrice(100000L)
			.address(Address.builder().country("KR").build())
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(2).build())
			.member(host)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.build());

		guests = new ArrayList<>();
		for (int i = 1; i <= THREAD_COUNT; i++) {
			guests.add(memberRepository.save(
				Member.builder().email("guest" + i + "@test.com").nickname("guest" + i).build()
			));
		}
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("DELETE FROM reservation_checkout_request");
		outboxRepository.deleteAllInBatch();
		paymentTransactionRepository.deleteAllInBatch();
		paymentRepository.deleteAllInBatch();
		paymentOperationRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		memberCouponRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("최대 정원을 초과하면 예약과 이벤트가 남지 않는다")
	void occupancyLimitIsEnforcedByTheAuthoritativeTransaction() {
		LocalDate checkIn = WINDOW_START.plusDays(30);

		assertThatThrownBy(() -> reservationService.createPendingReservation(
			new ReservationRequest.Create(accommodation.getId(), checkIn, checkIn.plusDays(2), 3),
			guests.getFirst().getId(),
			"occupancy-limit"))
			.isInstanceOf(ReservationOccupancyExceededException.class);

		assertThat(reservationRepository.count()).isZero();
		assertThat(outboxRepository.count()).isZero();
	}

	@Test
	@DisplayName("전액 할인 예약은 PG 없이 확정되고 취소도 로컬에서 쿠폰과 재고를 복원한다")
	void complimentaryReservationCompletesWithoutPaymentGateway() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 200_000);
		LocalDate checkIn = WINDOW_START.plusDays(30);

		var ready = reservationService.createPendingReservation(
			new ReservationRequest.Create(
				accommodation.getId(), checkIn, checkIn.plusDays(2), 2, coupon.getId()),
			guest.getId(),
			"complimentary-flow");

		Reservation confirmed = reservationRepository.findByReservationUid(UUID.fromString(ready.reservationUid()))
			.orElseThrow();
		assertThat(ready.amount()).isZero();
		assertThat(ready.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(ready.paymentRequired()).isFalse();
		assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(paymentOperationRepository.count()).isZero();
		assertThat(memberCoupon(guest, coupon).isUsed()).isTrue();
		assertThat(outboxRepository.findAll()).extracting(row -> row.getEventType())
			.containsExactly(
				AccommodationSearchRefreshRequestedV1.DESCRIPTOR.eventType());

		reservationService.cancelReservation(
			ready.reservationUid(), new PaymentRequest.Cancel("0원 예약 취소", null), guest.getId());

		Reservation cancelled = reservationRepository.findByReservationUid(confirmed.getReservationUid()).orElseThrow();
		assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(memberCoupon(guest, coupon).isUsed()).isFalse();
		assertThat(outboxRepository.findAll()).extracting(row -> row.getEventType())
			.doesNotContain("RESERVATION_CANCELLATION_REQUESTED");
	}

	@Test
	@DisplayName("결제 대기 만료 배치는 사용한 쿠폰을 함께 복원한다")
	void expiredPendingReservationRestoresCoupon() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 50_000);
		LocalDate checkIn = WINDOW_START.plusDays(30);
		var ready = reservationService.createPendingReservation(
			new ReservationRequest.Create(
				accommodation.getId(), checkIn, checkIn.plusDays(2), 2, coupon.getId()),
			guest.getId(),
			"expiry-coupon-restore");
		Reservation pending = reservationRepository.findByReservationUid(UUID.fromString(ready.reservationUid()))
			.orElseThrow();
		assertThat(memberCoupon(guest, coupon).isUsed()).isTrue();
		jdbcTemplate.update(
			"UPDATE reservation SET expires_at = ? WHERE id = ?",
			Timestamp.from(Instant.now().minusSeconds(5)),
			pending.getId());

		assertThat(cleanupService.cleanupExpiredPendingReservations()).isEqualTo(1);

		assertThat(reservationRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.EXPIRED);
		assertThat(memberCoupon(guest, coupon).isUsed()).isFalse();
	}

	@Test
	@DisplayName("만료 이력 저장이 실패하면 예약 만료와 쿠폰 복원을 함께 롤백한다")
	void expirationFailureRollsBackCouponRestoration() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 50_000);
		LocalDate checkIn = WINDOW_START.plusDays(30);
		var ready = reservationService.createPendingReservation(
			new ReservationRequest.Create(
				accommodation.getId(), checkIn, checkIn.plusDays(2), 2, coupon.getId()),
			guest.getId(),
			"expiry-rollback");
		Reservation pending = reservationRepository.findByReservationUid(UUID.fromString(ready.reservationUid()))
			.orElseThrow();
		jdbcTemplate.update(
			"UPDATE reservation SET expires_at = ? WHERE id = ?",
			Timestamp.from(Instant.now().minusSeconds(5)),
			pending.getId());
		jdbcTemplate.execute("""
			CREATE TRIGGER reservation_expiration_reject_history
			BEFORE INSERT ON reservation_history
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced history failure'
			""");

		try {
			assertThatThrownBy(cleanupService::cleanupExpiredPendingReservations)
				.isInstanceOf(DataAccessException.class);
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS reservation_expiration_reject_history");
		}

		assertThat(reservationRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(memberCoupon(guest, coupon).isUsed()).isTrue();
	}

	private Coupon issueFixedCoupon(Member member, int discountAmount) {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(Coupon.builder()
			.name(discountAmount + "원 할인")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(discountAmount)
			.issueStartAt(now.minusDays(1))
			.issueEndAt(now.plusDays(1))
			.usableFrom(now.minusDays(1))
			.usableUntil(now.plusDays(1))
			.isActive(true)
			.issuedQuantity(1)
			.build());
		memberCouponRepository.save(MemberCoupon.issue(member, coupon));
		return coupon;
	}

	private MemberCoupon memberCoupon(Member member, Coupon coupon) {
		return memberCouponRepository.findByMemberIdAndCouponId(member.getId(), coupon.getId()).orElseThrow();
	}

	@Test
	@DisplayName("같은 결제 승인 요청이 동시에 도착해도 하나의 결제 작업과 이벤트를 공유한다")
	void concurrentPaymentApprovalClaimsOnce() throws InterruptedException {
		LocalDate checkInDate = WINDOW_START.plusDays(30);
		Reservation pendingReservation = transactionService.createPendingReservationInTx(
			new ReservationRequest.Create(
				accommodation.getId(), checkInDate, checkInDate.plusDays(2), 2),
			guests.getFirst().getId(),
			"결제 승인 선점 동시성 테스트"
		);
		outboxRepository.deleteAllInBatch();

		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key",
			pendingReservation.getReservationUid().toString(),
			pendingReservation.getTotalPrice().intValue()
		);
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch readyLatch = new CountDownLatch(2);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);
		List<Throwable> unexpectedFailures = java.util.Collections.synchronizedList(new ArrayList<>());
		List<UUID> returnedOperationUids = java.util.Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < 2; i++) {
			executorService.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();
					returnedOperationUids.add(paymentOperationCommandService
						.requestConfirmation(request, guests.getFirst().getId()).operationId());
				} catch (Exception e) {
					unexpectedFailures.add(e);
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		Reservation reloadedReservation = reservationRepository
			.findByReservationUid(pendingReservation.getReservationUid())
			.orElseThrow();

		assertThat(unexpectedFailures).isEmpty();
		assertThat(returnedOperationUids).hasSize(2).containsOnly(returnedOperationUids.getFirst());
		assertThat(paymentOperationRepository.count()).isEqualTo(1);
		assertThat(outboxRepository.findAll().stream()
			.filter(row -> "PAYMENT_OPERATION_EXECUTION_REQUESTED".equals(row.getEventType())))
			.hasSize(1);
		assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
	}

	@Test
	@DisplayName("동일한 전액 취소 명령이 동시에 도착해도 하나의 취소 작업과 이벤트를 공유한다")
	void concurrentPaymentCancellationReturnsTheSameOperation() throws InterruptedException {
		Member guest = guests.getFirst();
		LocalDate checkInDate = WINDOW_START.plusDays(30);
		Reservation reservation = transactionService.createPendingReservationInTx(
			new ReservationRequest.Create(
				accommodation.getId(), checkInDate, checkInDate.plusDays(2), 2),
			guest.getId(),
			"결제 취소 동시성 테스트"
		);
		jdbcTemplate.update(
			"UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", reservation.getId());
		paymentRepository.saveAndFlush(Payment.builder()
			.paymentKey("paid-cancellation-key")
			.orderId(reservation.getReservationUid().toString())
			.amount(reservation.getTotalPrice())
			.balanceAmount(reservation.getTotalPrice())
			.method(PaymentMethod.CARD)
			.approvedAt(Instant.now())
			.reservation(reservation)
			.status(PaymentStatus.DONE)
			.build());
		outboxRepository.deleteAllInBatch();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		List<UUID> operationUids = java.util.Collections.synchronizedList(new ArrayList<>());
		AtomicInteger failures = new AtomicInteger();
		PaymentRequest.Cancel request = new PaymentRequest.Cancel(
			"동일 전액 취소", reservation.getTotalPrice());

		for (int index = 0; index < 2; index++) {
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					operationUids.add(paymentCancellationCommandService.requestCancellation(
						reservation.getReservationUid().toString(), request, guest.getId()).operationId());
				} catch (Exception exception) {
					failures.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertThat(failures.get()).isZero();
		assertThat(operationUids).hasSize(2).containsOnly(operationUids.getFirst());
		assertThat(paymentOperationRepository.findAll())
			.filteredOn(operation -> operation.getOperationType() == PaymentOperationType.CANCEL)
			.hasSize(1);
		assertThat(outboxRepository.findAll())
			.filteredOn(row -> "PAYMENT_OPERATION_EXECUTION_REQUESTED".equals(row.getEventType()))
			.hasSize(1);
		assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.CANCELLATION_PENDING);
	}

	@Test
	@DisplayName("동시에 같은 숙소를 예약하면 단 1명만 성공해야 한다")
	void reservationConcurrencyTest() throws InterruptedException {
		// given
		ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger expectedFailCount = new AtomicInteger(0);
		AtomicInteger unexpectedFailCount = new AtomicInteger(0);

		LocalDate checkInDate = WINDOW_START.plusDays(30);
		LocalDate checkOutDate = checkInDate.plusDays(2);

		// when
		for (int i = 0; i < THREAD_COUNT; i++) {
			final Member guest = guests.get(i);
			executorService.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();

					ReservationRequest.Create request = new ReservationRequest.Create(
						accommodation.getId(),
						checkInDate,
						checkOutDate,
						2
					);

					reservationService.createPendingReservation(
						request, guest.getId(), "inventory-contention-" + guest.getId());
					successCount.incrementAndGet();

				} catch (ReservationConflictException e) {
					expectedFailCount.incrementAndGet();
				} catch (Exception e) {
					unexpectedFailCount.incrementAndGet();
					System.err.println("Unexpected: " + e.getClass().getSimpleName() + " - " + e.getMessage());
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("동시성 테스트 결과");
		System.out.println("총 시도: " + THREAD_COUNT);
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예상된 실패: " + expectedFailCount.get());
		System.out.println("예상치 못한 실패: " + unexpectedFailCount.get());
		System.out.println("DB 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(unexpectedFailCount.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(successCount.get()).as("오직 하나의 예약만 성공해야 한다.").isEqualTo(1);
		assertThat(successCount.get() + expectedFailCount.get()).as("모든 요청이 처리되어야 한다.").isEqualTo(THREAD_COUNT);
		assertThat(reservationCount).as("DB에도 오직 하나의 예약만 기록되어야 한다.").isEqualTo(1);
	}

	@Test
	@DisplayName("같은 회원의 동일한 checkout 재시도는 기존 예약의 현재 Ready를 반환하고 부수효과를 재사용한다")
	void identicalCheckoutReplayReturnsTheExistingReservationWithoutDuplicateSideEffects() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 300_000);
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 2, coupon.getId(), "아기 침대 요청");
		String key = "same-checkout-replay";

		var first = reservationService.createPendingReservation(request, guest.getId(), key);
		var replay = reservationService.createPendingReservation(request, guest.getId(), key);

		assertThat(replay.reservationUid()).isEqualTo(first.reservationUid());
		assertThat(replay.amount()).isEqualTo(first.amount());
		assertThat(replay.status()).isEqualTo(first.status());
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(memberCoupon(guest, coupon).isUsed()).isTrue();
		assertThat(outboxRepository.findAll()).extracting(row -> row.getEventType())
			.containsExactly(AccommodationSearchRefreshRequestedV1.DESCRIPTOR.eventType());
		String keyHash = jdbcTemplate.queryForObject(
			"SELECT key_hash FROM reservation_checkout_request WHERE member_id = ?",
			String.class,
			guest.getId());
		String requestFingerprint = jdbcTemplate.queryForObject(
			"SELECT request_fingerprint FROM reservation_checkout_request WHERE member_id = ?",
			String.class,
			guest.getId());
		assertThat(keyHash).matches("[0-9a-f]{64}").doesNotContain(key);
		assertThat(requestFingerprint).matches("[0-9a-f]{64}").doesNotContain("아기 침대 요청");
	}

	@Test
	@DisplayName("만료 후 동일 checkout을 재시도하면 새 예약 없이 기존 예약의 현재 만료 상태를 반환한다")
	void checkoutReplayReturnsTheCurrentEffectiveReservationState() {
		Member guest = guests.getFirst();
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 2, null, "만료 상태 재조회");
		String key = "expired-checkout-replay";
		var first = reservationService.createPendingReservation(request, guest.getId(), key);
		jdbcTemplate.update(
			"UPDATE reservation SET expires_at = ? WHERE reservation_uid = UUID_TO_BIN(?)",
			Timestamp.from(Instant.now().minusSeconds(1)),
			first.reservationUid());

		var replay = reservationService.createPendingReservation(request, guest.getId(), key);

		assertThat(replay.reservationUid()).isEqualTo(first.reservationUid());
		assertThat(replay.status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(replay.paymentAllowed()).isFalse();
		assertThat(replay.serverTime()).isAfterOrEqualTo(first.serverTime());
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
	}

	@Test
	@DisplayName("같은 checkout 키라도 요청사항·쿠폰·날짜·인원·숙소 중 하나가 다르면 409다")
	void sameKeyWithDifferentFingerprintConflicts() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 10_000);
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create original = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 1, null, "조용한 방");
		String key = "fingerprint-contract";
		var first = reservationService.createPendingReservation(original, guest.getId(), key);
		jdbcTemplate.update("UPDATE reservation SET status = 'EXPIRED' WHERE reservation_uid = UUID_TO_BIN(?)",
			first.reservationUid());

		Member anotherHost = memberRepository.save(
			Member.builder().email("fingerprint-host@test.com").nickname("host").build());
		Accommodation anotherAccommodation = accommodationRepository.save(Accommodation.builder()
			.name("Another Accommodation").basePrice(100000L)
			.address(Address.builder().country("KR").build())
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(2).build())
			.member(anotherHost).checkInTime(LocalTime.of(15, 0)).checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID).status(AccommodationStatus.PUBLISHED).build());

		List<ReservationRequest.Create> changedRequests = List.of(
			new ReservationRequest.Create(accommodation.getId(), checkIn, checkIn.plusDays(2), 1, null, "높은 층"),
			new ReservationRequest.Create(accommodation.getId(), checkIn, checkIn.plusDays(2), 1, coupon.getId(), "조용한 방"),
			new ReservationRequest.Create(accommodation.getId(), checkIn.plusDays(1), checkIn.plusDays(3), 1, null, "조용한 방"),
			new ReservationRequest.Create(accommodation.getId(), checkIn, checkIn.plusDays(3), 1, null, "조용한 방"),
			new ReservationRequest.Create(accommodation.getId(), checkIn, checkIn.plusDays(2), 2, null, "조용한 방"),
			new ReservationRequest.Create(anotherAccommodation.getId(), checkIn, checkIn.plusDays(2), 1, null, "조용한 방")
		);

		for (ReservationRequest.Create changed : changedRequests) {
			assertThatThrownBy(() -> reservationService.createPendingReservation(changed, guest.getId(), key))
				.isInstanceOf(ReservationCheckoutIdempotencyConflictException.class);
		}
		assertThat(reservationRepository.count()).isOne();
		assertThat(memberCoupon(guest, coupon).isUsed()).isFalse();
	}

	@Test
	@DisplayName("같은 회원·키로 서로 다른 checkout이 동시에 오면 하나만 성공하고 다른 하나는 409다")
	void concurrentDifferentPayloadsWithTheSameKeyConvergeOnOneConflict() throws InterruptedException {
		Member guest = guests.getFirst();
		LocalDate checkIn = WINDOW_START.plusDays(35);
		List<ReservationRequest.Create> requests = List.of(
			new ReservationRequest.Create(
				accommodation.getId(), checkIn, checkIn.plusDays(2), 2, null, "조용한 방"),
			new ReservationRequest.Create(
				accommodation.getId(), checkIn, checkIn.plusDays(2), 2, null, "높은 층")
		);
		String key = "concurrent-fingerprint-conflict";
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();
		List<Throwable> unexpectedFailures = java.util.Collections.synchronizedList(new ArrayList<>());

		for (ReservationRequest.Create request : requests) {
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					reservationService.createPendingReservation(request, guest.getId(), key);
					successes.incrementAndGet();
				} catch (ReservationCheckoutIdempotencyConflictException expected) {
					conflicts.incrementAndGet();
				} catch (Throwable unexpected) {
					unexpectedFailures.add(unexpected);
				} finally {
					done.countDown();
				}
			});
		}

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();

		assertThat(unexpectedFailures).isEmpty();
		assertThat(successes).hasValue(1);
		assertThat(conflicts).hasValue(1);
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
	}

	@Test
	@DisplayName("같은 회원의 동시 동일 checkout 요청은 하나의 예약으로 수렴한다")
	void concurrentIdenticalCheckoutRequestsConvergeOnOneReservation() throws InterruptedException {
		int threadCount = 10;
		Member guest = guests.getFirst();
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 2, null, "동시 재시도");
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<String> reservationUids = java.util.Collections.synchronizedList(new ArrayList<>());
		List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					reservationUids.add(reservationService
						.createPendingReservation(request, guest.getId(), "concurrent-same-checkout")
						.reservationUid());
				} catch (Throwable failure) {
					failures.add(failure);
				} finally {
					done.countDown();
				}
			});
		}

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();

		assertThat(failures).isEmpty();
		assertThat(reservationUids).hasSize(threadCount).containsOnly(reservationUids.getFirst());
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
	}

	@Test
	@DisplayName("실패한 checkout 트랜잭션은 키를 소비하지 않아 같은 요청을 재시도할 수 있다")
	void failedCheckoutTransactionDoesNotConsumeTheKey() {
		Member guest = guests.getFirst();
		Coupon coupon = issueFixedCoupon(guest, 10_000);
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 2, coupon.getId(), "rollback retry");
		String key = "rollback-does-not-consume";
		jdbcTemplate.execute("""
			CREATE TRIGGER reservation_checkout_reject_history
			BEFORE INSERT ON reservation_history
			FOR EACH ROW
			SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced checkout failure'
			""");

		try {
			assertThatThrownBy(() -> reservationService.createPendingReservation(request, guest.getId(), key))
				.isInstanceOf(DataAccessException.class);
		} finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS reservation_checkout_reject_history");
		}
		assertThat(reservationRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
		assertThat(memberCoupon(guest, coupon).isUsed()).isFalse();

		var retried = reservationService.createPendingReservation(request, guest.getId(), key);

		assertThat(retried.reservationUid()).isNotBlank();
		assertThat(reservationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(memberCoupon(guest, coupon).isUsed()).isTrue();
	}

	@Test
	@DisplayName("같은 키는 회원 scope가 다르면 다른 예약을 만들며 기존 예약을 노출하지 않는다")
	void idempotencyKeyIsScopedByMember() {
		LocalDate checkIn = WINDOW_START.plusDays(35);
		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(), checkIn, checkIn.plusDays(2), 1, null, "member scope");
		String key = "same-raw-key-for-two-members";
		var first = reservationService.createPendingReservation(request, guests.get(0).getId(), key);
		jdbcTemplate.update("UPDATE reservation SET status = 'EXPIRED' WHERE reservation_uid = UUID_TO_BIN(?)",
			first.reservationUid());

		var second = reservationService.createPendingReservation(request, guests.get(1).getId(), key);

		assertThat(second.reservationUid()).isNotEqualTo(first.reservationUid());
		assertThat(reservationRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("서비스 계층을 우회해도 DB 권위 트랜잭션이 중복 예약을 막는다")
	void authoritativeTransactionPreventsDuplicateReservations() throws InterruptedException {
		// given
		int threadCount = 10;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger expectedFailCount = new AtomicInteger(0);
		AtomicInteger unexpectedFailCount = new AtomicInteger(0);

		LocalDate checkInDate = WINDOW_START.plusDays(30);
		LocalDate checkOutDate = checkInDate.plusDays(2);

		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(),
			checkInDate,
			checkOutDate,
			2
		);

		// when - 외부 서비스 계층을 우회하고 권위 트랜잭션을 직접 호출
		for (int i = 0; i < threadCount; i++) {
			final Member guest = guests.get(i);
			executorService.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();

					transactionService.createPendingReservationInTx(request, guest.getId(), "DB 권위 트랜잭션 테스트");
					successCount.incrementAndGet();
				} catch (ReservationConflictException e) {
					expectedFailCount.incrementAndGet();
				} catch (Exception e) {
					unexpectedFailCount.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("[DB 권위 트랜잭션] 중복 예약 방지 테스트");
		System.out.println("총 시도: " + threadCount);
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예상된 실패: " + expectedFailCount.get());
		System.out.println("예상치 못한 실패: " + unexpectedFailCount.get());
		System.out.println("DB 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(unexpectedFailCount.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(successCount.get()).as("오직 하나의 예약만 성공해야 한다.").isEqualTo(1);
		assertThat(expectedFailCount.get()).as("나머지 요청은 예약 충돌로 종료되어야 한다.")
			.isEqualTo(threadCount - 1);
		assertThat(reservationCount).as("DB에도 오직 하나의 예약만 기록되어야 한다.").isEqualTo(1);
	}

	@Test
	@DisplayName("서로 다른 숙소는 같은 날짜여도 동시 예약이 모두 성공해야 한다")
	void differentAccommodations_bothSucceed() throws InterruptedException {
		// given
		Member host2 = memberRepository.save(Member.builder().email("host2@test.com").nickname("Host2").build());
		Accommodation accommodation2 = accommodationRepository.save(Accommodation.builder()
			.name("Test Accommodation 2")
			.basePrice(200000L)
			.address(Address.builder().country("KR").build())
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(2).build())
			.member(host2)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.build());

		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch readyLatch = new CountDownLatch(2);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger unexpectedFailCount = new AtomicInteger(0);

		LocalDate checkInDate = WINDOW_START.plusDays(30);
		LocalDate checkOutDate = checkInDate.plusDays(2);

		Member guestA = guests.get(0);
		Member guestB = guests.get(1);

		// when - 같은 날짜, 다른 숙소
		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(
					new ReservationRequest.Create(accommodation.getId(), checkInDate, checkOutDate, 2),
					guestA.getId(),
					"different-accommodation-a"
				);
				successCount.incrementAndGet();
			} catch (Exception e) {
				unexpectedFailCount.incrementAndGet();
				System.err.println("숙소1 실패: " + e.getClass().getSimpleName());
			} finally {
				doneLatch.countDown();
			}
		});

		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(
					new ReservationRequest.Create(accommodation2.getId(), checkInDate, checkOutDate, 2),
					guestB.getId(),
					"different-accommodation-b"
				);
				successCount.incrementAndGet();
			} catch (Exception e) {
				unexpectedFailCount.incrementAndGet();
				System.err.println("숙소2 실패: " + e.getClass().getSimpleName());
			} finally {
				doneLatch.countDown();
			}
		});

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("서로 다른 숙소 동시 예약 테스트");
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예상치 못한 실패: " + unexpectedFailCount.get());
		System.out.println("DB 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(unexpectedFailCount.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(successCount.get()).as("서로 다른 숙소이므로 둘 다 성공해야 한다.").isEqualTo(2);
		assertThat(reservationCount).as("DB에 2건의 예약이 있어야 한다.").isEqualTo(2);
	}

	@Test
	@DisplayName("날짜가 겹치는 두 예약을 동시에 진행할 때 데드락이 발생하지 않아야 한다")
	void deadlockAvoidanceTest() throws InterruptedException {
		// given
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch readyLatch = new CountDownLatch(2);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);
		AtomicInteger unexpectedFailCount = new AtomicInteger(0);

		LocalDate baseDate = WINDOW_START.plusDays(30);
		ReservationRequest.Create requestA = new ReservationRequest.Create(
			accommodation.getId(),
			baseDate.plusDays(1),
			baseDate.plusDays(3),
			2
		);

		ReservationRequest.Create requestB = new ReservationRequest.Create(
			accommodation.getId(),
			baseDate,
			baseDate.plusDays(2),
			2
		);

		Member guestA = guests.get(0);
		Member guestB = guests.get(1);

		// when
		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(
					requestA, guestA.getId(), "overlap-request-a");
				successCount.incrementAndGet();
			} catch (ReservationConflictException e) {
				failCount.incrementAndGet();
			} catch (Exception e) {
				unexpectedFailCount.incrementAndGet();
				System.err.println("Unexpected for User A: " + e.getClass().getSimpleName());
			} finally {
				doneLatch.countDown();
			}
		});

		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(
					requestB, guestB.getId(), "overlap-request-b");
				successCount.incrementAndGet();
			} catch (ReservationConflictException e) {
				failCount.incrementAndGet();
			} catch (Exception e) {
				unexpectedFailCount.incrementAndGet();
				System.err.println("Unexpected for User B: " + e.getClass().getSimpleName());
			} finally {
				doneLatch.countDown();
			}
		});

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("데드락 테스트 결과");
		System.out.println("총 시도: 2");
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예약 실패: " + failCount.get());
		System.out.println("예상치 못한 실패: " + unexpectedFailCount.get());
		System.out.println("DB 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(unexpectedFailCount.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(successCount.get()).as("두 예약 중 하나는 성공해야 한다.").isEqualTo(1);
		assertThat(failCount.get()).as("두 예약 중 하나는 실패해야 한다.").isEqualTo(1);
		assertThat(reservationCount).as("DB에는 최종적으로 하나의 예약만 있어야 한다.").isEqualTo(1);
	}
}

package kr.kro.airbob.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;
import kr.kro.airbob.messaging.outbox.infrastructure.jpa.OutboxMessageRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@Import(PaymentConfirmationInventoryBoundaryIntegrationTest.BoundaryClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("결제 승인 재고 경계 동시성")
class PaymentConfirmationInventoryBoundaryIntegrationTest {

	private static final String TIME_ZONE_ID = "UTC";
	private static final Instant CONFIRMATION_ACCEPTED_AT =
		Instant.parse("2026-08-17T00:00:00Z");
	private static final Instant RESERVATION_EXPIRES_AT =
		CONFIRMATION_ACCEPTED_AT.plusSeconds(60);
	private static final Instant NEW_RESERVATION_REQUESTED_AT =
		RESERVATION_EXPIRES_AT.plusSeconds(60);
	private static final LocalDate STAY_START = LocalDate.of(2026, 8, 27);
	private static final LocalDate STAY_END = STAY_START.plusDays(2);
	private static final long PAYMENT_AMOUNT = 200_000L;
	private static final long BLOCK_ASSERTION_MILLIS = 500L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_confirmation_inventory_boundary")
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
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private PaymentOperationCommandService commandService;
	@Autowired private ReservationTransactionService reservationTransactionService;
	@Autowired private PaymentOperationRepository paymentOperationRepository;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationHistoryRepository historyRepository;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private OccupancyPolicyRepository occupancyPolicyRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private OutboxMessageRepository outboxRepository;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private io.awspring.cloud.s3.S3Template s3Template;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;

	private TransactionTemplate transactionTemplate;
	private ExecutorService executor;
	private Accommodation accommodation;
	private Member confirmingGuest;
	private Member competingGuest;
	private Reservation pendingReservation;

	@BeforeEach
	void setUp() {
		transactionTemplate = new TransactionTemplate(transactionManager);
		executor = Executors.newFixedThreadPool(2);
		clock.set(CONFIRMATION_ACCEPTED_AT);
		given(bookingWindowProvider.currentFor(TIME_ZONE_ID, NEW_RESERVATION_REQUESTED_AT))
			.willReturn(BookingWindow.startingOn(STAY_START.minusDays(1)));

		clearRows();
		createFixture();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		executor.shutdownNow();
		executor.awaitTermination(10, TimeUnit.SECONDS);
		clearRows();
	}

	@Test
	@DisplayName("신규 예약이 먼저 커밋되면 대기하던 결제 승인은 overlap 재검증으로 롤백된다")
	void createCommitsFirst_confirmationRollsBack() throws Exception {
		CountDownLatch createPrepared = new CountDownLatch(1);
		CountDownLatch allowCreateCommit = new CountDownLatch(1);
		clock.set(NEW_RESERVATION_REQUESTED_AT);

		Future<?> create = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			createCompetingReservation();
			createPrepared.countDown();
			awaitRelease(allowCreateCommit);
		}));
		awaitPrepared(createPrepared, create);

		clock.set(CONFIRMATION_ACCEPTED_AT);
		Future<Accepted> confirmation = executor.submit(() ->
			transactionTemplate.execute(status -> commandService.requestConfirmation(
				confirmationRequest(), confirmingGuest.getId())));

		try {
			assertBlocked(confirmation);
		} finally {
			allowCreateCommit.countDown();
		}

		create.get(10, TimeUnit.SECONDS);
		assertFutureFailedWith(confirmation, ReservationConflictException.class);
		assertThat(reservationRepository.findByReservationUid(pendingReservation.getReservationUid())
			.orElseThrow().getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(reservationRepository.count()).isEqualTo(2);
		assertThat(paymentOperationRepository.count()).isZero();
	}

	@Test
	@DisplayName("결제 승인이 먼저 커밋되면 대기하던 신규 예약은 충돌로 롤백된다")
	void confirmationCommitsFirst_createRollsBack() throws Exception {
		CountDownLatch confirmationPrepared = new CountDownLatch(1);
		CountDownLatch allowConfirmationCommit = new CountDownLatch(1);
		clock.set(CONFIRMATION_ACCEPTED_AT);

		Future<Accepted> confirmation = executor.submit(() -> transactionTemplate.execute(status -> {
			Accepted accepted = commandService.requestConfirmation(
				confirmationRequest(), confirmingGuest.getId());
			confirmationPrepared.countDown();
			awaitRelease(allowConfirmationCommit);
			return accepted;
		}));
		awaitPrepared(confirmationPrepared, confirmation);

		clock.set(NEW_RESERVATION_REQUESTED_AT);
		Future<?> create = executor.submit(this::createCompetingReservation);

		try {
			assertBlocked(create);
		} finally {
			allowConfirmationCommit.countDown();
		}

		confirmation.get(10, TimeUnit.SECONDS);
		assertFutureFailedWith(create, ReservationConflictException.class);
		assertThat(reservationRepository.findByReservationUid(pendingReservation.getReservationUid())
			.orElseThrow().getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		assertThat(reservationRepository.count()).isOne();
		assertThat(paymentOperationRepository.count()).isOne();
	}

	@Test
	@DisplayName("결제 승인은 커밋할 때까지 숙소 재고 mutex를 보유한다")
	void confirmationHoldsAccommodationInventoryMutexUntilCommit() throws Exception {
		CountDownLatch confirmationPrepared = new CountDownLatch(1);
		CountDownLatch allowConfirmationCommit = new CountDownLatch(1);

		Future<Accepted> confirmation = executor.submit(() -> transactionTemplate.execute(status -> {
			Accepted accepted = commandService.requestConfirmation(
				confirmationRequest(), confirmingGuest.getId());
			confirmationPrepared.countDown();
			awaitRelease(allowConfirmationCommit);
			return accepted;
		}));
		awaitPrepared(confirmationPrepared, confirmation);

		Future<?> accommodationLockProbe = executor.submit(() ->
			transactionTemplate.executeWithoutResult(status ->
				accommodationRepository.findByIdForUpdate(accommodation.getId()).orElseThrow()));

		try {
			assertBlocked(accommodationLockProbe);
		} finally {
			allowConfirmationCommit.countDown();
		}

		confirmation.get(10, TimeUnit.SECONDS);
		accommodationLockProbe.get(10, TimeUnit.SECONDS);
	}

	private void createFixture() {
		Member host = memberRepository.save(
			Member.builder().email("boundary-host@test.com").nickname("boundary-host").build());
		confirmingGuest = memberRepository.save(
			Member.builder().email("boundary-confirm@test.com").nickname("boundary-confirm").build());
		competingGuest = memberRepository.save(
			Member.builder().email("boundary-create@test.com").nickname("boundary-create").build());

		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Boundary accommodation")
			.basePrice(100_000L)
			.currency("KRW")
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).build())
			.member(host)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.build());

		pendingReservation = reservationRepository.saveAndFlush(Reservation.builder()
			.reservationUid(UUID.randomUUID())
			.reservationCode("BOUNDARY1")
			.accommodation(accommodation)
			.guest(confirmingGuest)
			.checkInDate(STAY_START)
			.checkOutDate(STAY_END)
			.checkInAt(STAY_START.atTime(15, 0).toInstant(ZoneOffset.UTC))
			.checkOutAt(STAY_END.atTime(11, 0).toInstant(ZoneOffset.UTC))
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(PAYMENT_AMOUNT)
			.discountAmount(0L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(RESERVATION_EXPIRES_AT)
			.build());
	}

	private void createCompetingReservation() {
		reservationTransactionService.createPendingReservationInTx(
			new ReservationRequest.Create(accommodation.getId(), STAY_START, STAY_END, 2),
			competingGuest.getId(),
			"inventory boundary concurrency test"
		);
	}

	private PaymentRequest.Confirm confirmationRequest() {
		return new PaymentRequest.Confirm(
			"boundary-payment-key",
			pendingReservation.getReservationUid().toString(),
			Math.toIntExact(PAYMENT_AMOUNT)
		);
	}

	private void clearRows() {
		outboxRepository.deleteAllInBatch();
		paymentOperationRepository.deleteAllInBatch();
		historyRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
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
	static class BoundaryClockConfiguration {
		@Bean
		@Primary
		AdjustableClock paymentConfirmationInventoryBoundaryClock() {
			return new AdjustableClock(CONFIRMATION_ACCEPTED_AT);
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

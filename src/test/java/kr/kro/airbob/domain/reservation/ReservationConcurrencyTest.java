package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationStatusHistoryRepository;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
class ReservationConcurrencyTest {

	private static final int THREAD_COUNT = 50;

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
	private ReservationStatusHistoryRepository historyRepository;

	@MockitoBean
	private ElasticsearchClient elasticsearchClient;
	@MockitoBean
	private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean
	private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean
	private io.awspring.cloud.s3.S3Template s3Template;

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test");

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

		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	private Accommodation accommodation;
	private List<Member> guests;

	@BeforeEach
	void setUp() {
		historyRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
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
		historyRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
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

		LocalDate checkInDate = LocalDate.of(2025, 12, 24);
		LocalDate checkOutDate = LocalDate.of(2025, 12, 26);

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

					reservationService.createPendingReservation(request, guest.getId());
					successCount.incrementAndGet();

				} catch (ReservationLockException | ReservationConflictException e) {
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
	@DisplayName("분산 락 없이 동시 예약하면 중복 예약이 발생한다")
	void withoutLock_duplicateReservationOccurs() throws InterruptedException {
		// given
		int threadCount = 10;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		LocalDate checkInDate = LocalDate.of(2025, 12, 24);
		LocalDate checkOutDate = LocalDate.of(2025, 12, 26);

		ReservationRequest.Create request = new ReservationRequest.Create(
			accommodation.getId(),
			checkInDate,
			checkOutDate,
			2
		);

		// when - 락 없이 트랜잭션 서비스 직접 호출
		for (int i = 0; i < threadCount; i++) {
			final Member guest = guests.get(i);
			executorService.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();

					transactionService.createPendingReservationInTx(request, guest.getId(), "락 미적용 테스트");
					successCount.incrementAndGet();
				} catch (ReservationConflictException e) {
					failCount.incrementAndGet();
				} catch (Exception e) {
					failCount.incrementAndGet();
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
		System.out.println("[락 미적용] 중복 예약 재현 테스트");
		System.out.println("총 시도: " + threadCount);
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예약 실패: " + failCount.get());
		System.out.println("DB 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(reservationCount).as("락 없이는 중복 예약이 발생해야 한다 (2개 이상).")
			.isGreaterThan(1);
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
			.status(AccommodationStatus.PUBLISHED)
			.build());

		ExecutorService executorService = Executors.newFixedThreadPool(2);
		CountDownLatch readyLatch = new CountDownLatch(2);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger unexpectedFailCount = new AtomicInteger(0);

		LocalDate checkInDate = LocalDate.of(2025, 12, 24);
		LocalDate checkOutDate = LocalDate.of(2025, 12, 26);

		Member guestA = guests.get(0);
		Member guestB = guests.get(1);

		// when - 같은 날짜, 다른 숙소
		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(
					new ReservationRequest.Create(accommodation.getId(), checkInDate, checkOutDate, 2),
					guestA.getId()
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
					guestB.getId()
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

		ReservationRequest.Create requestA = new ReservationRequest.Create(
			accommodation.getId(),
			LocalDate.of(2025, 12, 25),
			LocalDate.of(2025, 12, 27),
			2
		);

		ReservationRequest.Create requestB = new ReservationRequest.Create(
			accommodation.getId(),
			LocalDate.of(2025, 12, 24),
			LocalDate.of(2025, 12, 26),
			2
		);

		Member guestA = guests.get(0);
		Member guestB = guests.get(1);

		// when
		executorService.submit(() -> {
			try {
				readyLatch.countDown();
				startLatch.await();
				reservationService.createPendingReservation(requestA, guestA.getId());
				successCount.incrementAndGet();
			} catch (ReservationLockException | ReservationConflictException e) {
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
				reservationService.createPendingReservation(requestB, guestB.getId());
				successCount.incrementAndGet();
			} catch (ReservationLockException | ReservationConflictException e) {
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

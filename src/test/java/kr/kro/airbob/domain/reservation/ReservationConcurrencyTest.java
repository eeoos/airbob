package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationStatusHistoryRepository;
import kr.kro.airbob.domain.reservation.service.ReservationService;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReservationConcurrencyTest {

	@Autowired
	private ReservationService reservationService;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private AccommodationRepository accommodationRepository;
	@Autowired
	private AddressRepository addressRepository;
	@Autowired
	private OccupancyPolicyRepository occupancyPolicyRepository;
	@Autowired
	private ReservationStatusHistoryRepository historyRepository;

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test");

	@Container
	private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@Container
	static final GenericContainer<?> elasticsearchContainer =
		new GenericContainer<>(
			new ImageFromDockerfile("elasticsearch-nori:8.9.0", false)
				.withDockerfile(Paths.get(System.getProperty("user.dir"), "Dockerfile"))
		)
			.withExposedPorts(9200)
			.withEnv("xpack.security.enabled", "false")
			.withEnv("discovery.type", "single-node")
			.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
			.withReuse(true);


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

		registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearchContainer.getHost() + ":" + elasticsearchContainer.getMappedPort(9200));

		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	private Accommodation accommodation;

	@BeforeEach
	void setUp() {
		historyRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
		occupancyPolicyRepository.deleteAllInBatch();

		Member host = memberRepository.save(Member.builder().email("host@test.com").nickname("Host").build());
		Address address = addressRepository.save(Address.builder().country("KR").build());
		OccupancyPolicy policy = occupancyPolicyRepository.save(OccupancyPolicy.builder().maxOccupancy(2).build());

		accommodation = accommodationRepository.save(Accommodation.builder()
			.name("Test Accommodation")
			.basePrice(100000)
			.address(address)
			.occupancyPolicy(policy)
			.member(host)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build());
	}

	@AfterEach
	void tearDown() {
		historyRepository.deleteAllInBatch();
		reservationRepository.deleteAllInBatch();
		accommodationRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		addressRepository.deleteAllInBatch();
		occupancyPolicyRepository.deleteAllInBatch();
	}


	@Test
	@DisplayName("300명의 유저가 동일한 숙소를 동시에 예약할 때 단 1명만 성공해야 한다")
	void reservationConcurrencyTest() throws InterruptedException {

		// given
		int numberOfThreads = 300;
		ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
		CountDownLatch latch = new CountDownLatch(numberOfThreads);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger expectedFailCount = new AtomicInteger(0);

		LocalDate checkInDate = LocalDate.of(2025, 12, 24);
		LocalDate checkOutDate = LocalDate.of(2025, 12, 26);

		// when
		for (int i = 0; i < numberOfThreads; i++) {
			final int userId = i + 1;
			executorService.submit(() -> {
				try {
					Member guest = memberRepository.save(Member.builder().email("guest" + userId + "@test.com").nickname("guest" + userId).build());

					ReservationRequest.Create request = new ReservationRequest.Create(
						accommodation.getId(),
						checkInDate,
						checkOutDate,
						2,
						"Message from user " + userId
					);

					reservationService.createPendingReservation(request, guest.getId());
					successCount.incrementAndGet();

				} catch (ReservationLockException | ReservationConflictException e) {
					expectedFailCount.incrementAndGet();
				} catch (Exception e) {
					System.err.println("Unexpected exception for user " + userId + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
				} finally {
					latch.countDown();
				}
			});
		}

		latch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("부하 테스트 결과");
		System.out.println("총 시도: " + numberOfThreads);
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예상된 실패 (정상): " + expectedFailCount.get());
		System.out.println("DB에 생성된 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(successCount.get()).as("오직 하나의 예약만 성공해야 한다.").isEqualTo(1);
		assertThat(expectedFailCount.get()).as("나머지 모든 예약은 예상된 예외(락 또는 중복)로 실패해야 한다.").isEqualTo(numberOfThreads - 1);
		assertThat(reservationCount).as("DB에도 오직 하나의 예약만 기록되어야 한다.").isEqualTo(1);
	}

	@Test
	@DisplayName("날짜가 겹치는 두 예약을 동시에 진행할 때 데드락이 발생하지 않아야 한다")
	void deadlockAvoidanceTest() throws InterruptedException {
		// given
		int numberOfThreads = 2;
		ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
		CountDownLatch latch = new CountDownLatch(numberOfThreads);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);

		// 유저 A: 25일 ~ 27일 예약 시도 (락 대상: 25, 26일)
		ReservationRequest.Create requestA = new ReservationRequest.Create(
			accommodation.getId(),
			LocalDate.of(2025, 12, 25),
			LocalDate.of(2025, 12, 27),
			2, "Request A"
		);

		// 유저 B: 24일 ~ 26일 예약 시도 (락 대상: 24, 25일)
		ReservationRequest.Create requestB = new ReservationRequest.Create(
			accommodation.getId(),
			LocalDate.of(2025, 12, 24),
			LocalDate.of(2025, 12, 26),
			2, "Request B"
		);

		Member guestA = memberRepository.save(Member.builder().email("guestA@test.com").nickname("guestA").build());
		Member guestB = memberRepository.save(Member.builder().email("guestB@test.com").nickname("guestB").build());

		// when
		// 스레드 1: requestA 실행
		executorService.submit(() -> {
			try {
				reservationService.createPendingReservation(requestA, guestA.getId());
				successCount.incrementAndGet();
			} catch (ReservationLockException | ReservationConflictException e) {
				failCount.incrementAndGet();
			} catch (Exception e) {
				System.err.println("Unexpected exception for User A: " + e.getClass().getSimpleName());
			} finally {
				latch.countDown();
			}
		});

		// 스레드 2: requestB 실행
		executorService.submit(() -> {
			try {
				// 두 스레드가 거의 동시에 락 획득을 시도하도록 딜레이 추가
				Thread.sleep(10);
				reservationService.createPendingReservation(requestB, guestB.getId());
				successCount.incrementAndGet();
			} catch (ReservationLockException | ReservationConflictException e) {
				failCount.incrementAndGet();
			} catch (Exception e) {
				System.err.println("Unexpected exception for User B: " + e.getClass().getSimpleName());
			} finally {
				latch.countDown();
			}
		});

		latch.await();
		executorService.shutdown();

		// then
		long reservationCount = reservationRepository.count();

		System.out.println("======================================");
		System.out.println("데드락 테스트 결과");
		System.out.println("총 시도: 2");
		System.out.println("예약 성공: " + successCount.get());
		System.out.println("예약 실패 (정상): " + failCount.get());
		System.out.println("DB에 생성된 예약 수: " + reservationCount);
		System.out.println("======================================");

		assertThat(successCount.get()).as("두 예약 중 하나는 성공해야 한다.").isEqualTo(1);
		assertThat(failCount.get()).as("두 예약 중 하나는 실패해야 한다.").isEqualTo(1);
		assertThat(reservationCount).as("DB에는 최종적으로 하나의 예약만 있어야 한다.").isEqualTo(1);
	}
}

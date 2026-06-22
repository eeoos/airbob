package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyUsedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

/**
 * 같은 회원이 같은 쿠폰을 동시에 여러 예약에 사용하려 해도 단 1회만 사용 처리되어야 한다.
 * markUsed 의 조건부 UPDATE(used=false 일 때만) 가 중복 사용을 막는다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
class CouponUsageConcurrencyTest {

	private static final int THREAD_COUNT = 20;

	@Autowired
	private CouponUsageService couponUsageService;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private MemberCouponRepository memberCouponRepository;
	@Autowired
	private MemberRepository memberRepository;

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
	private static final GenericContainer<?> redisContainer = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
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

	private Member member;
	private Coupon coupon;

	@BeforeEach
	void setUp() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();

		member = memberRepository.save(Member.builder().email("u@test.com").nickname("u").build());
		coupon = couponRepository.save(Coupon.builder()
			.name("10% 할인")
			.discountType(DiscountType.PERCENTAGE)
			.discountValue(10)
			.startDate(LocalDateTime.now().minusDays(1))
			.endDate(LocalDateTime.now().plusDays(7))
			.isActive(true)
			.issuedQuantity(1)
			.build());
		memberCouponRepository.save(MemberCoupon.issue(member, coupon));
	}

	@AfterEach
	void tearDown() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("같은 쿠폰을 동시에 사용해도 단 1회만 사용 처리된다")
	void concurrentUse_onlyOnce() throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

		AtomicInteger success = new AtomicInteger(0);
		AtomicInteger alreadyUsed = new AtomicInteger(0);
		AtomicInteger unexpected = new AtomicInteger(0);

		for (int i = 0; i < THREAD_COUNT; i++) {
			final long reservationId = i + 1;
			executor.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();
					couponUsageService.use(member.getId(), coupon.getId(), reservationId, 100_000L);
					success.incrementAndGet();
				} catch (CouponAlreadyUsedException e) {
					alreadyUsed.incrementAndGet();
				} catch (Exception e) {
					unexpected.incrementAndGet();
					System.err.println("Unexpected: " + e.getClass().getSimpleName() + " - " + e.getMessage());
				} finally {
					doneLatch.countDown();
				}
			});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executor.shutdown();

		MemberCoupon refreshed = memberCouponRepository.findByMemberIdAndCouponId(member.getId(), coupon.getId())
			.orElseThrow();

		System.out.println("======================================");
		System.out.println("쿠폰 중복 사용 방지 테스트");
		System.out.println("총 시도: " + THREAD_COUNT);
		System.out.println("사용 성공: " + success.get());
		System.out.println("이미 사용됨: " + alreadyUsed.get());
		System.out.println("예상치 못한 실패: " + unexpected.get());
		System.out.println("======================================");

		assertThat(unexpected.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(success.get()).as("단 1회만 사용 성공해야 한다.").isEqualTo(1);
		assertThat(alreadyUsed.get()).as("나머지는 모두 이미 사용됨 처리되어야 한다.").isEqualTo(THREAD_COUNT - 1);
		assertThat(refreshed.isUsed()).as("쿠폰은 사용됨 상태여야 한다.").isTrue();
	}
}

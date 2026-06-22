package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
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
import kr.kro.airbob.domain.coupon.exception.CouponIssueFailedException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.service.CouponIssueService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

/**
 * 선착순 쿠폰 발급 동시성 — 세 방식 비교.
 * THREAD_COUNT 명이 한도 COUPON_LIMIT 개 쿠폰을 동시에 발급 요청한다(모두 서로 다른 회원).
 *  - 무락        : 초과 발급(lost update)이 발생한다(anti-pattern 재현)
 *  - Redisson 락 : 절대 초과발급하지 않는다(정확). 단 극단적 경합에선 일부가 락 획득 타임아웃
 *  - 원자적 카운터: 정확히 한도만큼만, 1인 1매. 대기 없이 즉시 승/패 결정
 * 정확성 불변식(발급 수 == 한도, 1인 1매)은 락 타임아웃 여부와 무관하게 항상 보장된다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
class CouponConcurrencyTest {

	private static final int THREAD_COUNT = 300;
	private static final int COUPON_LIMIT = 10;

	@Autowired
	private CouponIssueService couponIssueService;
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

	private Coupon coupon;
	private List<Member> members;

	@BeforeEach
	void setUp() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();

		coupon = couponRepository.save(Coupon.builder()
			.name("선착순 10% 할인 쿠폰")
			.discountType(DiscountType.PERCENTAGE)
			.discountValue(10)
			.startDate(LocalDateTime.now().minusDays(1))
			.endDate(LocalDateTime.now().plusDays(7))
			.isActive(true)
			.totalQuantity(COUPON_LIMIT)
			.issuedQuantity(0)
			.build());

		members = new ArrayList<>();
		for (int i = 1; i <= THREAD_COUNT; i++) {
			members.add(memberRepository.save(
				Member.builder().email("member" + i + "@test.com").nickname("member" + i).build()
			));
		}
	}

	@AfterEach
	void tearDown() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("동시성 제어 없이 발급하면 한도를 초과해 발급된다 (anti-pattern)")
	void withoutLock_overIssues() throws InterruptedException {
		Result result = runConcurrentIssue((couponId, memberId) ->
			couponIssueService.issueWithoutLock(couponId, memberId));

		long issuedCount = memberCouponRepository.countByCouponId(coupon.getId());
		printResult("[무락] 초과발급 재현", result, issuedCount);

		assertThat(result.lockFailed.get()).as("무락 경로는 락을 쓰지 않는다.").isZero();
		assertThat(issuedCount)
			.as("락이 없으면 발급 수가 한도(%d)를 초과해야 한다.", COUPON_LIMIT)
			.isGreaterThan(COUPON_LIMIT);
	}

	@Test
	@DisplayName("Redisson 분산락은 극단적 경합에서도 절대 초과발급하지 않는다")
	void withLock_neverOverIssues() throws InterruptedException {
		Result result = runConcurrentIssue((couponId, memberId) ->
			couponIssueService.issueWithLock(couponId, memberId));

		long issuedCount = memberCouponRepository.countByCouponId(coupon.getId());
		Coupon refreshed = couponRepository.findById(coupon.getId()).orElseThrow();
		printResult("[Redisson 락] 정확 발급", result, issuedCount);

		assertThat(result.unexpectedFail.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(result.success.get()).as("정확히 한도만큼만 성공해야 한다.").isEqualTo(COUPON_LIMIT);
		assertThat(issuedCount).as("DB 발급 수도 정확히 한도여야 한다(초과발급 0).").isEqualTo(COUPON_LIMIT);
		assertThat(refreshed.getIssuedQuantity()).as("쿠폰 issuedQuantity 도 한도여야 한다.").isEqualTo(COUPON_LIMIT);
		// 성공 + 매진 + 락획득실패(타임아웃) 로 모든 요청이 설명되어야 한다.
		assertThat(result.success.get() + result.soldOut.get() + result.lockFailed.get())
			.as("모든 요청이 성공/매진/락타임아웃 중 하나로 처리되어야 한다.").isEqualTo(THREAD_COUNT);
	}

	@Test
	@DisplayName("Redis 원자적 카운터는 대기 없이 정확히 한도만큼만 발급한다")
	void withAtomicCounter_issuesExactlyLimit() throws InterruptedException {
		couponIssueService.prepareStock(coupon.getId());

		Result result = runConcurrentIssue((couponId, memberId) ->
			couponIssueService.issueWithAtomicCounter(couponId, memberId));

		long issuedCount = memberCouponRepository.countByCouponId(coupon.getId());
		Coupon refreshed = couponRepository.findById(coupon.getId()).orElseThrow();
		printResult("[원자적 카운터] 정확 발급", result, issuedCount);

		assertThat(result.unexpectedFail.get()).as("예상치 못한 예외가 발생하면 안 된다.").isZero();
		assertThat(result.lockFailed.get()).as("카운터 방식은 락 대기가 없어 타임아웃이 없어야 한다.").isZero();
		assertThat(result.success.get()).as("정확히 한도만큼만 성공해야 한다.").isEqualTo(COUPON_LIMIT);
		assertThat(issuedCount).as("DB 발급 수도 정확히 한도여야 한다(초과발급 0).").isEqualTo(COUPON_LIMIT);
		assertThat(refreshed.getIssuedQuantity()).as("쿠폰 issuedQuantity 도 한도여야 한다.").isEqualTo(COUPON_LIMIT);
		assertThat(result.success.get() + result.soldOut.get())
			.as("모든 요청이 성공 또는 매진으로 즉시 처리되어야 한다.").isEqualTo(THREAD_COUNT);
	}

	@FunctionalInterface
	private interface IssueAction {
		void issue(Long couponId, Long memberId);
	}

	private Result runConcurrentIssue(IssueAction action) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

		Result result = new Result();

		for (int i = 0; i < THREAD_COUNT; i++) {
			final Long memberId = members.get(i).getId();
			executor.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();
					action.issue(coupon.getId(), memberId);
					result.success.incrementAndGet();
				} catch (CouponSoldOutException e) {
					result.soldOut.incrementAndGet();
				} catch (CouponIssueFailedException e) {
					// 분산락 획득 타임아웃 — 극단적 경합 시의 정상 결과(가용성 트레이드오프)
					result.lockFailed.incrementAndGet();
				} catch (Exception e) {
					result.unexpectedFail.incrementAndGet();
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
		return result;
	}

	private void printResult(String title, Result result, long issuedCount) {
		System.out.println("======================================");
		System.out.println(title);
		System.out.println("총 시도: " + THREAD_COUNT + " / 한도: " + COUPON_LIMIT);
		System.out.println("발급 성공: " + result.success.get());
		System.out.println("매진 실패: " + result.soldOut.get());
		System.out.println("락 획득 실패(타임아웃): " + result.lockFailed.get());
		System.out.println("예상치 못한 실패: " + result.unexpectedFail.get());
		System.out.println("DB 발급 수: " + issuedCount);
		System.out.println("======================================");
	}

	private static class Result {
		final AtomicInteger success = new AtomicInteger(0);
		final AtomicInteger soldOut = new AtomicInteger(0);
		final AtomicInteger lockFailed = new AtomicInteger(0);
		final AtomicInteger unexpectedFail = new AtomicInteger(0);
	}
}

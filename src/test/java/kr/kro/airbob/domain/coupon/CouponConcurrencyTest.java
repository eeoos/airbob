package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
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
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponLockTimeoutException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponRedisPreparationResult;
import kr.kro.airbob.domain.coupon.service.CouponRedisStockManager;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
class CouponConcurrencyTest {

	private static final int THREAD_COUNT = 100;
	private static final int COUPON_LIMIT = 10;

	@Autowired
	private CouponLockIssueService lockIssueService;
	@Autowired
	private CouponLuaIssueService luaIssueService;
	@Autowired
	private CouponRedisStockManager stockManager;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private MemberCouponRepository memberCouponRepository;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private RedissonClient redissonClient;
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

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_test");

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
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
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
		registry.add("spring.kafka.consumer.enabled", () -> "false");
		registry.add("spring.kafka.producer.enabled", () -> "false");
	}

	private Coupon lockCoupon;
	private Coupon luaCoupon;
	private List<Member> members;

	@BeforeEach
	void setUp() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		redissonClient.getKeys().deleteByPattern("coupon:*");

		LocalDateTime now = LocalDateTime.now();
		lockCoupon = couponRepository.save(coupon("분산 락 쿠폰", now));
		luaCoupon = couponRepository.save(coupon("Lua 쿠폰", now));

		long redisNow = System.currentTimeMillis();
		CouponRedisPreparationResult preparation = stockManager.prepare(
			luaCoupon.getId(),
			COUPON_LIMIT,
			redisNow - TimeUnit.MINUTES.toMillis(1),
			redisNow + TimeUnit.MINUTES.toMillis(10),
			true,
			redisNow + TimeUnit.DAYS.toMillis(7));
		assertThat(preparation).isEqualTo(CouponRedisPreparationResult.PREPARED);

		members = new ArrayList<>();
		for (int i = 1; i <= THREAD_COUNT; i++) {
			members.add(memberRepository.save(
				Member.builder().email("coupon-member" + i + "@test.com").nickname("member" + i).build()));
		}
	}

	@AfterEach
	void tearDown() {
		memberCouponRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		redissonClient.getKeys().deleteByPattern("coupon:*");
	}

	@Test
	@DisplayName("분산 락 경로는 동시 요청에서도 DB 재고 한도를 지킨다")
	void lockPathPreservesIssuanceInvariants() throws InterruptedException {
		Result result = runDistinctMemberRequests(lockCoupon.getId(), lockIssueService::issue);

		assertThat(result.unexpectedFailures).isEmpty();
		assertThat(result.success.get()).isEqualTo(COUPON_LIMIT);
		assertThat(result.success.get() + result.soldOut.get() + result.lockTimeout.get())
			.isEqualTo(THREAD_COUNT);
		assertDatabaseInvariants(lockCoupon.getId());
	}

	@Test
	@DisplayName("Lua 경로는 동시 요청에서도 Redis와 DB 재고 불변식을 지킨다")
	void luaPathPreservesIssuanceInvariants() throws InterruptedException {
		Result result = runDistinctMemberRequests(luaCoupon.getId(), luaIssueService::issue);

		assertThat(result.unexpectedFailures).isEmpty();
		assertThat(result.success.get()).isEqualTo(COUPON_LIMIT);
		assertThat(result.success.get() + result.soldOut.get()).isEqualTo(THREAD_COUNT);
		assertDatabaseInvariants(luaCoupon.getId());
		long issuedCount = memberCouponRepository.countByCouponId(luaCoupon.getId());
		assertThat(stockManager.remainingStock(luaCoupon.getId()) + issuedCount).isEqualTo(COUPON_LIMIT);
	}

	@Test
	@DisplayName("분산 락 경로에서 같은 회원의 동시 요청은 한 건만 발급된다")
	void lockPathIssuesOnlyOnceToSameMember() throws InterruptedException {
		Result result = runSameMemberRequests(lockCoupon.getId(), members.getFirst().getId(), lockIssueService::issue);

		assertThat(result.unexpectedFailures).isEmpty();
		assertThat(result.success.get()).isOne();
		assertThat(memberCouponRepository.countByCouponId(lockCoupon.getId())).isOne();
		assertDatabaseInvariants(lockCoupon.getId());
	}

	@Test
	@DisplayName("Lua 경로에서 같은 회원의 동시 요청은 한 건만 발급된다")
	void luaPathIssuesOnlyOnceToSameMember() throws InterruptedException {
		Result result = runSameMemberRequests(luaCoupon.getId(), members.getFirst().getId(), luaIssueService::issue);

		assertThat(result.unexpectedFailures).isEmpty();
		assertThat(result.success.get()).isOne();
		assertThat(result.duplicate.get()).isEqualTo(THREAD_COUNT - 1);
		assertThat(memberCouponRepository.countByCouponId(luaCoupon.getId())).isOne();
		assertDatabaseInvariants(luaCoupon.getId());
		assertThat(stockManager.remainingStock(luaCoupon.getId())).isEqualTo(COUPON_LIMIT - 1);
	}

	private Coupon coupon(String name, LocalDateTime now) {
		return Coupon.builder()
			.name(name)
			.discountType(DiscountType.PERCENTAGE)
			.discountValue(10)
			.issueStartAt(now.minusMinutes(1))
			.issueEndAt(now.plusMinutes(10))
			.usableFrom(now.minusMinutes(1))
			.usableUntil(now.plusDays(7))
			.isActive(true)
			.totalQuantity(COUPON_LIMIT)
			.issuedQuantity(0)
			.build();
	}

	private Result runDistinctMemberRequests(Long couponId, IssueAction action) throws InterruptedException {
		return runConcurrent(couponId, index -> members.get(index).getId(), action);
	}

	private Result runSameMemberRequests(Long couponId, Long memberId, IssueAction action)
		throws InterruptedException {
		return runConcurrent(couponId, ignored -> memberId, action);
	}

	private Result runConcurrent(Long couponId, MemberIdProvider memberIdProvider, IssueAction action)
		throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(THREAD_COUNT);
		Result result = new Result();

		for (int i = 0; i < THREAD_COUNT; i++) {
			int index = i;
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					action.issue(couponId, memberIdProvider.memberId(index));
					result.success.incrementAndGet();
				} catch (CouponSoldOutException e) {
					result.soldOut.incrementAndGet();
				} catch (CouponAlreadyIssuedException e) {
					result.duplicate.incrementAndGet();
				} catch (CouponLockTimeoutException e) {
					result.lockTimeout.incrementAndGet();
				} catch (Throwable e) {
					result.unexpectedFailures.add(e);
				} finally {
					done.countDown();
				}
			});
		}

		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		executor.shutdownNow();
		return result;
	}

	private void assertDatabaseInvariants(Long couponId) {
		Coupon refreshed = couponRepository.findById(couponId).orElseThrow();
		long issuedCount = memberCouponRepository.countByCouponId(couponId);
		Integer duplicateMemberCount = jdbcTemplate.queryForObject("""
			select count(*)
			from (
			  select member_id
			  from member_coupon
			  where coupon_id = ?
			  group by member_id
			  having count(*) > 1
			) duplicates
			""", Integer.class, couponId);

		assertThat(issuedCount).isEqualTo(refreshed.getIssuedQuantity().longValue());
		assertThat(refreshed.getIssuedQuantity()).isLessThanOrEqualTo(refreshed.getTotalQuantity());
		assertThat(duplicateMemberCount).isZero();
	}

	@FunctionalInterface
	private interface IssueAction {
		void issue(Long couponId, Long memberId);
	}

	@FunctionalInterface
	private interface MemberIdProvider {
		Long memberId(int requestIndex);
	}

	private static class Result {
		private final AtomicInteger success = new AtomicInteger();
		private final AtomicInteger soldOut = new AtomicInteger();
		private final AtomicInteger duplicate = new AtomicInteger();
		private final AtomicInteger lockTimeout = new AtomicInteger();
		private final ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();
	}
}

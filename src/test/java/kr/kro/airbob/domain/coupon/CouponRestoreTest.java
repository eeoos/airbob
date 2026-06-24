package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

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
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

/**
 * 예약 취소 시 쿠폰 복원, 취소 보상(취소 실패) 시 재사용 라운드트립 검증.
 */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
class CouponRestoreTest {

	private static final long RESERVATION_ID = 100L;

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

	@Test
	@DisplayName("사용 → 취소 복원 → 취소 보상 재사용 라운드트립")
	void useRestoreReuse() {
		long discount = couponUsageService.use(member.getId(), coupon.getId(), RESERVATION_ID, 100_000L);
		assertThat(discount).isEqualTo(10_000L);
		assertThat(usedFlag()).as("사용 후 used=true").isTrue();

		couponUsageService.restore(RESERVATION_ID);
		assertThat(usedFlag()).as("취소 복원 후 used=false").isFalse();

		couponUsageService.reuse(RESERVATION_ID);
		assertThat(usedFlag()).as("취소 보상 재사용 후 used=true").isTrue();
	}

	@Test
	@DisplayName("복원은 멱등하다 — 사용되지 않은 쿠폰에 복원해도 안전")
	void restoreIsIdempotent() {
		// 사용하지 않은 상태에서 복원 호출 → 변화 없음
		couponUsageService.restore(RESERVATION_ID);
		assertThat(usedFlag()).isFalse();
	}

	private boolean usedFlag() {
		MemberCoupon mc = memberCouponRepository.findByMemberIdAndCouponId(member.getId(), coupon.getId())
			.orElseThrow();
		return mc.isUsed();
	}
}

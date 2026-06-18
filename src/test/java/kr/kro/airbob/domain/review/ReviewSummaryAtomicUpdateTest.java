package kr.kro.airbob.domain.review;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
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
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("리뷰 요약 원자적 갱신(낙관적 락 → upsert) 테스트")
class ReviewSummaryAtomicUpdateTest {

	@Autowired private AccommodationReviewSummaryRepository summaryRepository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager txManager;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

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

	private TransactionTemplate tx;
	private long accId;

	@BeforeEach
	void setup() {
		clean();
		tx = new TransactionTemplate(txManager);
		long host = insertMember("host");
		accId = insertAccommodation(host);
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("동시에 N개의 첫 리뷰가 들어와도 PK 중복/낙관적 락 예외 없이 전건 반영된다")
	void concurrentFirstReviewsAreMergedAtomically() throws InterruptedException {
		int threadCount = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < threadCount; i++) {
			pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
					tx.executeWithoutResult(s -> summaryRepository.applyNewReview(accId, 5));
				} catch (Throwable t) {
					errors.add(t);
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown(); // 동시에 출발 → 빈 테이블에 동시 첫 INSERT 유도
		done.await(30, TimeUnit.SECONDS);
		pool.shutdownNow();

		assertThat(errors).isEmpty(); // OptimisticLockingFailureException / 중복키 예외 없음
		assertSummary(threadCount, 5L * threadCount, "5.00");
		Integer rowCount = jdbc.queryForObject(
			"SELECT COUNT(*) FROM accommodation_review_summary WHERE accommodation_id = ?", Integer.class, accId);
		assertThat(rowCount).isEqualTo(1); // 행은 정확히 1개
	}

	@Test
	@DisplayName("생성/평점수정/삭제가 rating_sum과 average_rating에 원자적으로 반영된다")
	void createUpdateDeleteMaintainsAggregate() {
		tx.executeWithoutResult(s -> summaryRepository.applyNewReview(accId, 5));
		assertSummary(1, 5L, "5.00");

		tx.executeWithoutResult(s -> summaryRepository.applyNewReview(accId, 3));
		assertSummary(2, 8L, "4.00");

		// 평점 수정: 3 → 1 (개수 불변)
		tx.executeWithoutResult(s -> summaryRepository.applyRatingChange(accId, 3, 1));
		assertSummary(2, 6L, "3.00");

		// 리뷰 1건 삭제(rating=1): 남은 1건은 유지
		tx.executeWithoutResult(s -> {
			summaryRepository.removeReview(accId, 1);
			summaryRepository.deleteIfEmpty(accId);
		});
		assertSummary(1, 5L, "5.00");

		// 마지막 리뷰 삭제: 0건이 되어 요약 행 제거
		tx.executeWithoutResult(s -> {
			summaryRepository.removeReview(accId, 5);
			summaryRepository.deleteIfEmpty(accId);
		});
		Integer rowCount = jdbc.queryForObject(
			"SELECT COUNT(*) FROM accommodation_review_summary WHERE accommodation_id = ?", Integer.class, accId);
		assertThat(rowCount).isZero();
	}

	// ===== helpers =====

	private void assertSummary(int expectedCount, long expectedSum, String expectedAvg) {
		Map<String, Object> row = jdbc.queryForMap(
			"SELECT total_review_count, rating_sum, average_rating "
				+ "FROM accommodation_review_summary WHERE accommodation_id = ?", accId);
		assertThat(((Number)row.get("total_review_count")).intValue()).isEqualTo(expectedCount);
		assertThat(((Number)row.get("rating_sum")).longValue()).isEqualTo(expectedSum);
		assertThat((java.math.BigDecimal)row.get("average_rating")).isEqualByComparingTo(expectedAvg);
	}

	private void clean() {
		jdbc.update("DELETE FROM accommodation_review_summary");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private long insertMember(String nickname) {
		jdbc.update("INSERT INTO member (nickname, status, updated_at) VALUES (?, 'ACTIVE', NOW(6))", nickname);
		return jdbc.queryForObject("SELECT id FROM member ORDER BY id DESC LIMIT 1", Long.class);
	}

	private long insertAccommodation(long memberId) {
		jdbc.update("""
			INSERT INTO accommodation
				(member_id, check_in_time, check_out_time, accommodation_uid, status, base_price, updated_at)
			VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(UUID()), 'PUBLISHED', 100000, NOW(6))
			""", memberId);
		return jdbc.queryForObject("SELECT id FROM accommodation ORDER BY id DESC LIMIT 1", Long.class);
	}
}

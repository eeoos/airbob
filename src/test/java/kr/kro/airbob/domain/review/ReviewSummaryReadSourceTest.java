package kr.kro.airbob.domain.review;

import static org.assertj.core.api.Assertions.*;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.service.ReviewService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("리뷰 요약 조회 source(raw 집계 vs summary 반정규화) 일치 테스트")
class ReviewSummaryReadSourceTest {

	@Autowired private ReviewService reviewService;
	@Autowired private JdbcTemplate jdbc;

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

	private long accId;

	@BeforeEach
	void setup() {
		clean();
		long member = insertMember("guest");
		accId = insertAccommodation(member);

		// 게시 리뷰 3건(5,4,3) → count 3, avg 4.00 / 삭제 1건(rating 1)은 제외돼야 함
		insertReview(accId, member, 5, "PUBLISHED");
		insertReview(accId, member, 4, "PUBLISHED");
		insertReview(accId, member, 3, "PUBLISHED");
		insertReview(accId, member, 1, "DELETE");

		// 반정규화 요약(after): count 3, sum 12, avg 4.00
		jdbc.update("""
			INSERT INTO accommodation_review_summary
				(accommodation_id, total_review_count, rating_sum, average_rating, updated_at)
			VALUES (?, 3, 12, 4.00, NOW(6))
			""", accId);
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("raw(review 직접 집계)와 summary(반정규화)가 동일한 결과를 낸다")
	void rawEqualsSummary() {
		ReviewResponse.ReviewSummary raw = reviewService.findReviewSummary(accId, ReviewService.SOURCE_RAW);
		ReviewResponse.ReviewSummary summary = reviewService.findReviewSummary(accId, ReviewService.SOURCE_SUMMARY);

		// raw: 삭제 리뷰 제외, 게시 3건 평균 4.00
		assertThat(raw.totalCount()).isEqualTo(3);
		assertThat(raw.averageRating()).isEqualByComparingTo("4.00");

		// 두 경로 결과 일치
		assertThat(raw.totalCount()).isEqualTo(summary.totalCount());
		assertThat(raw.averageRating()).isEqualByComparingTo(summary.averageRating());
	}

	// ===== helpers =====

	private void clean() {
		jdbc.update("DELETE FROM accommodation_review_summary");
		jdbc.update("DELETE FROM review");
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

	private void insertReview(long accommodationId, long memberId, int rating, String status) {
		jdbc.update("""
			INSERT INTO review (rating, accommodation_id, member_id, content, status, created_at, updated_at)
			VALUES (?, ?, ?, '리뷰', ?, NOW(6), NOW(6))
			""", rating, accommodationId, memberId, status);
	}
}

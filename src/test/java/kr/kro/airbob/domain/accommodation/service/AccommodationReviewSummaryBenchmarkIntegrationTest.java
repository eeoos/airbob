package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;

@DataJpaTest
@Testcontainers
@ActiveProfiles({"test", "read-model-benchmark"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, AccommodationDetailReader.class,
	AccommodationDetailBenchmarkService.class, AccommodationReviewSummaryBenchmarkIntegrationTest.SqlConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("숙소 상세 리뷰 반정규화 비교 MySQL 테스트")
class AccommodationReviewSummaryBenchmarkIntegrationTest {

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired AccommodationDetailBenchmarkService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired SqlCapture capture;

	@BeforeEach
	void setUp() {
		jdbc.update("INSERT INTO member (id, nickname, status, updated_at) VALUES (1, 'host', 'ACTIVE', NOW(6))");
		jdbc.update("""
			INSERT INTO accommodation
				(id, member_id, name, accommodation_uid, status, check_in_time, check_out_time, updated_at)
			VALUES (11, 1, '서울 숙소', UUID_TO_BIN(UUID()), 'PUBLISHED', '15:00:00', '11:00:00', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_image (accommodation_id, image_url, updated_at)
			VALUES (11, '/first.jpg', NOW(6)), (11, '/second.jpg', NOW(6))
			""");
		capture.sql.clear();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("원본과 요약 테이블 조회는 로그인 여부와 상세 응답이 같고 원본은 요약 테이블을 읽지 않는다")
	void rawMatchesSummaryAndDoesNotReadSummaryTable(boolean signedIn) {
		insertReviews();
		jdbc.update("""
			INSERT INTO accommodation_review_summary
				(accommodation_id, total_review_count, rating_sum, average_rating, updated_at)
			VALUES (11, 3, 13, 4.33, NOW(6))
			""");
		jdbc.update("INSERT INTO wishlist (id, name, member_id, status, updated_at) VALUES (21, '여행', 1, 'ACTIVE', NOW(6))");
		jdbc.update("INSERT INTO wishlist_accommodation (wishlist_id, accommodation_id, updated_at) VALUES (21, 11, NOW(6))");
		Long viewerId = signedIn ? 1L : null;

		var before = service.findAccommodationBeforeReviewSummary(11L, viewerId);

		assertThat(before.reviewSummary().totalCount()).isEqualTo(3);
		assertThat(before.reviewSummary().averageRating()).isEqualByComparingTo("4.33");
		assertThat(before.isInWishlist()).isEqualTo(signedIn);
		assertThat(before.images()).hasSize(2);
		assertThat(capture.sql).hasSize(signedIn ? 5 : 4)
			.noneMatch(sql -> sql.contains("accommodation_review_summary"));
		assertThat(capture.sql.stream().filter(sql -> sql.contains("FROM review")).count()).isOne();
		capture.sql.clear();

		var after = service.findAccommodationBefore(11L, viewerId);

		assertThat(before).usingRecursiveComparison()
			.withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(after);
		assertThat(capture.sql).hasSize(signedIn ? 4 : 3)
			.anyMatch(sql -> sql.contains("accommodation_review_summary"));

		jdbc.update("UPDATE accommodation_review_summary SET total_review_count = 9, rating_sum = 45, average_rating = 5 WHERE accommodation_id = 11");
		assertThat(service.findAccommodationBeforeReviewSummary(11L, viewerId).reviewSummary())
			.isEqualTo(before.reviewSummary());
	}

	@Test
	@DisplayName("게시 리뷰가 없으면 원본과 요약 조회 모두 리뷰 수와 평점이 0이다")
	void noPublishedReviewsMatch() {
		jdbc.update("""
			INSERT INTO review (accommodation_id, member_id, rating, status, updated_at)
			VALUES (11, 1, 1, 'DELETE', NOW(6)), (11, 1, 5, 'HIDDEN', NOW(6))
			""");
		var before = service.findAccommodationBeforeReviewSummary(11L, null);
		var after = service.findAccommodationBefore(11L, null);
		assertThat(before).usingRecursiveComparison()
			.withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(after);
		assertThat(before.reviewSummary().totalCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"DRAFT", "UNPUBLISHED", "DELETED"})
	@DisplayName("비공개 숙소는 원본 리뷰 집계 전에 조회를 거절한다")
	void rejectsUnpublishedBeforeAggregation(String status) {
		jdbc.update("UPDATE accommodation SET status = ? WHERE id = 11", status);
		assertThatThrownBy(() -> service.findAccommodationBeforeReviewSummary(11L, null))
			.isInstanceOf(AccommodationNotFoundException.class);
		assertThat(capture.sql).hasSize(1);
	}

	private void insertReviews() {
		jdbc.update("""
			INSERT INTO review (accommodation_id, member_id, rating, status, updated_at)
			VALUES (11, 1, 5, 'PUBLISHED', NOW(6)), (11, 1, 4, 'PUBLISHED', NOW(6)),
				(11, 1, 4, 'PUBLISHED', NOW(6)), (11, 1, 1, 'DELETE', NOW(6)), (11, 1, 2, 'HIDDEN', NOW(6))
			""");
	}

	static class SqlCapture implements StatementInspector {
		final List<String> sql = new ArrayList<>();
		@Override
		public String inspect(String statement) {
			sql.add(statement);
			return statement;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SqlConfig {
		@Bean SqlCapture sqlCapture() { return new SqlCapture(); }
		@Bean HibernatePropertiesCustomizer sqlInspector(SqlCapture capture) {
			return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
		}
	}
}

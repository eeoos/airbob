package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.image.dto.ImageResponse;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, AccommodationDetailReader.class,
	PublicAccommodationDetailReadIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicAccommodationDetailReadIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_public_detail_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@Autowired private AccommodationDetailReader reader;
	@Autowired private ObjectMapper objectMapper;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, email, password, nickname, role, status, thumbnail_image_url, updated_at)
			VALUES (20, 'public-host@test.invalid', 'synthetic-hash', '공개 상세 호스트', 'MEMBER', 'ACTIVE',
				'/contract/public-host.jpg', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO address (id, country, state, city, district, street, detail, postal_code,
				latitude, longitude, updated_at)
			VALUES (40, '대한민국', '서울특별시', '서울', '마포구', '응답에 없는 도로', '응답에 없는 상세',
				'04000', 37.55, 126.92, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO occupancy_policy (id, max_occupancy, infant_occupancy, pet_occupancy, updated_at)
			VALUES (50, 4, 1, 0, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, member_id, accommodation_uid, name, description, type, base_price,
				currency, address_id, occupancy_policy_id, status, check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (30, 20, UUID_TO_BIN(UUID()), '공개 상세 계약 숙소', '창가에서 쉬어가는 숙소입니다.', 'APARTMENT',
				150000, 'KRW', 40, 50, 'PUBLISHED', '15:30:00', '11:00:00', 'Asia/Seoul', NOW(6)),
			       (31, 20, UUID_TO_BIN(UUID()), '다른 숙소', '다른 내용', 'HOUSE', 90000,
				'KRW', null, null, 'PUBLISHED', '16:00:00', '12:00:00', 'Asia/Seoul', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_review_summary
				(accommodation_id, total_review_count, rating_sum, average_rating, updated_at)
			VALUES (30, 4, 18, 4.50, NOW(6)), (31, 7, 35, 5.00, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_image (id, accommodation_id, image_url, updated_at)
			VALUES (62, 30, '/contract/public-second.jpg', NOW(6)),
			       (60, 30, '/contract/public-first.jpg', NOW(6)), (63, 31, '/contract/other.jpg', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_amenity (id, accommodation_id, amenity_code, count, updated_at)
			VALUES (70, 30, 'WIFI', 1, NOW(6)), (71, 30, 'TV', 2, NOW(6)), (72, 31, 'OTHER', 9, NOW(6))
			""");
	}

	@Test
	void preservesPublicJsonAndIndependentCollections() throws Exception {
		Statistics statistics = prepareMeasurement();
		var response = AccommodationResponse.DetailInfo.from(reader.load(30L), false);

		try (var fixture = new ClassPathResource("contracts/public-accommodation-detail.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertThat(response.images()).extracting(ImageResponse.ImageInfo::id).containsExactly(60L, 62L);
		assertThat(response.amenities()).containsExactlyInAnyOrder(
			new AmenityResponse.AmenityInfo("WIFI", 1), new AmenityResponse.AmenityInfo("TV", 2));
		assertReadQueries(statistics);
	}

	@Test
	void keepsEmptyCollectionsNullOptionalDataAndZeroReviewSummary() {
		jdbc.update("UPDATE accommodation SET address_id = null, occupancy_policy_id = null WHERE id = 30");
		jdbc.update("DELETE FROM accommodation_review_summary WHERE accommodation_id = 30");
		jdbc.update("DELETE FROM accommodation_image WHERE accommodation_id = 30");
		jdbc.update("DELETE FROM accommodation_amenity WHERE accommodation_id = 30");
		Statistics statistics = prepareMeasurement();

		var snapshot = reader.load(30L);

		assertThat(snapshot.addressSummary().country()).isNull();
		assertThat(snapshot.coordinate().latitude()).isNull();
		assertThat(snapshot.coordinate().longitude()).isNull();
		assertThat(snapshot.policy().maxOccupancy()).isNull();
		assertThat(snapshot.images()).isEmpty();
		assertThat(snapshot.amenities()).isEmpty();
		assertThat(snapshot.reviewSummary().totalCount()).isZero();
		assertThat(snapshot.reviewSummary().averageRating()).isEqualByComparingTo("0");
		assertReadQueries(statistics);
	}

	@Test
	void preservesZerosAndReflectsCurrentValuesOnUncachedRead() {
		reader.load(30L);
		jdbc.update("UPDATE accommodation SET name = '변경된 숙소', base_price = 0, time_zone_id = 'UTC' WHERE id = 30");
		jdbc.update("UPDATE address SET latitude = 0, longitude = 0 WHERE id = 40");
		jdbc.update("UPDATE occupancy_policy SET max_occupancy = 2, infant_occupancy = 0 WHERE id = 50");
		jdbc.update("UPDATE member SET nickname = '변경된 호스트', thumbnail_image_url = null WHERE id = 20");
		jdbc.update("UPDATE accommodation_review_summary SET total_review_count = 0, rating_sum = 0, average_rating = 0 WHERE accommodation_id = 30");
		jdbc.update("UPDATE accommodation_amenity SET count = 0 WHERE id = 71");
		jdbc.update("UPDATE accommodation_image SET image_url = '/contract/changed.jpg' WHERE id = 60");
		Statistics statistics = prepareMeasurement();

		var snapshot = reader.load(30L);

		assertThat(snapshot.name()).isEqualTo("변경된 숙소");
		assertThat(snapshot.basePrice()).isZero();
		assertThat(snapshot.timeZoneId()).isEqualTo("UTC");
		assertThat(snapshot.coordinate().latitude()).isZero();
		assertThat(snapshot.coordinate().longitude()).isZero();
		assertThat(snapshot.host().nickname()).isEqualTo("변경된 호스트");
		assertThat(snapshot.host().thumbnailImageUrl()).isNull();
		assertThat(snapshot.policy().maxOccupancy()).isEqualTo(2);
		assertThat(snapshot.policy().infantOccupancy()).isZero();
		assertThat(snapshot.reviewSummary().totalCount()).isZero();
		assertThat(snapshot.amenities()).contains(new AmenityResponse.AmenityInfo("TV", 0));
		assertThat(snapshot.images().getFirst().imageUrl()).isEqualTo("/contract/changed.jpg");
		assertReadQueries(statistics);
	}

	@ParameterizedTest
	@EnumSource(value = AccommodationStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
	void rejectsUnpublishedAccommodationBeforeLoadingCollections(AccommodationStatus status) {
		jdbc.update("UPDATE accommodation SET status = ? WHERE id = 30", status.name());
		Statistics statistics = prepareMeasurement();

		assertThatThrownBy(() -> reader.load(30L)).isInstanceOf(AccommodationNotFoundException.class);
		assertRejectedQuery(statistics);
	}

	@Test
	void rejectsUnknownAccommodationWithoutLoadingAnotherStay() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> reader.load(999L)).isInstanceOf(AccommodationNotFoundException.class);
		assertRejectedQuery(statistics);
	}

	private void assertReadQueries(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
		assertThat(sqlCapture.statements).hasSize(3);
		assertThat(sqlCapture.statements.getFirst()).doesNotContain("accommodation_image", "accommodation_amenity");
		assertThat(sqlCapture.statements).noneMatch(sql -> sql.contains(" from review ") || sql.contains("wishlist"));
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(sqlCapture.statements.stream()
			.map(sql -> sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length))
			.containsExactly(23, 2, 2);
		assertThat(sqlCapture.statements.getFirst())
			.doesNotContain(".password", ".email", ".street", ".postal_code", ".created_at", ".updated_at");

	}

	private void assertRejectedQuery(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(sqlCapture.statements.getFirst()).doesNotContain("accommodation_image", "accommodation_amenity");
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		return statistics;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ReadTestConfig {
		@Bean
		SqlCapture sqlCapture() {
			return new SqlCapture();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(SqlCapture capture) {
			return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
		}
	}

	static class SqlCapture implements StatementInspector {
		private final List<String> statements = new ArrayList<>();

		@Override
		public String inspect(String sql) {
			statements.add(sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim());
			return sql;
		}
	}
}

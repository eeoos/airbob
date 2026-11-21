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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.image.dto.ImageResponse;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, AccommodationDetailReader.class, AccommodationQueryService.class,
	HostAccommodationDetailReadIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HostAccommodationDetailReadIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_host_detail_read");

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
	@Autowired private AccommodationQueryService service;
	@MockitoBean private CursorPageInfoCreator cursorPageInfoCreator;
	@MockitoBean private ReservationInventoryService inventoryService;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;
	@MockitoBean private AccommodationDetailCache detailCache;
	@Autowired private ObjectMapper objectMapper;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (202, 'editor-host@test.invalid', '호스트', 'MEMBER', 'ACTIVE', NOW(6)),
			       (203, 'other-host@test.invalid', '다른 호스트', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO address (id, country, state, city, district, street, detail, postal_code,
				latitude, longitude, updated_at)
			VALUES (40, '대한민국', '서울특별시', '서울', '마포구', '월드컵북로', '101호', '04000',
				37.556, 126.923, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO occupancy_policy (id, max_occupancy, infant_occupancy, pet_occupancy, updated_at)
			VALUES (50, 4, 1, 0, NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, member_id, accommodation_uid, name, description, type, base_price,
				currency, address_id, occupancy_policy_id, status, check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (31, 202, UUID_TO_BIN(UUID()), '합정 테스트 숙소', '조용한 숙소', 'APARTMENT',
				125000, 'KRW', 40, 50, 'DRAFT', '15:00:00', '11:00:00', 'Asia/Seoul', NOW(6)),
			       (32, 203, UUID_TO_BIN(UUID()), '다른 숙소', '다른 내용', 'HOUSE', 90000,
				'KRW', null, null, 'PUBLISHED', '16:00:00', '12:00:00', 'Asia/Seoul', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_image (id, accommodation_id, image_url, updated_at)
			VALUES (301, 31, '/room-301.png', NOW(6)), (400, 32, '/other-room.png', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation_amenity (id, accommodation_id, amenity_code, count, updated_at)
			VALUES (70, 31, 'WIFI', 1, NOW(6)), (71, 32, 'OTHER', 9, NOW(6))
			""");
	}

	@ParameterizedTest
	@EnumSource(AccommodationStatus.class)
	void preservesOwnedEditorContractForEveryExistingStatus(AccommodationStatus status) throws Exception {
		jdbc.update("UPDATE accommodation SET status = ? WHERE id = 31", status.name());
		Statistics statistics = prepareMeasurement();

		var response = service.findHostAccommodationDetail(31L, 202L);

		try (var fixture = new ClassPathResource("contracts/host-accommodation-editor.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertReadQueries(statistics);
	}

	@Test
	void preservesIncompleteDraftWithoutSubstitutingAnotherStaysData() {
		jdbc.update("""
			UPDATE accommodation SET address_id = null, occupancy_policy_id = null,
				name = null, description = null, type = null, base_price = null, currency = null WHERE id = 31
			""");
		jdbc.update("DELETE FROM accommodation_image WHERE accommodation_id = 31");
		jdbc.update("DELETE FROM accommodation_amenity WHERE accommodation_id = 31");
		Statistics statistics = prepareMeasurement();

		var response = service.findHostAccommodationDetail(31L, 202L);

		assertThat(response.id()).isEqualTo(31L);
		assertThat(response.name()).isNull();
		assertThat(response.description()).isNull();
		assertThat(response.basePrice()).isNull();
		assertThat(response.address().country()).isNull();
		assertThat(response.address().street()).isNull();
		assertThat(response.policy().maxOccupancy()).isNull();
		assertThat(response.policy().infantOccupancy()).isNull();
		assertThat(response.policy().petOccupancy()).isNull();
		assertThat(response.images()).isEmpty();
		assertThat(response.amenities()).isEmpty();
		assertReadQueries(statistics);
	}

	@Test
	void preservesPartialPolicyZeroValuesLocalTimesAndImageOrder() {
		jdbc.update("UPDATE occupancy_policy SET infant_occupancy = null, pet_occupancy = 0 WHERE id = 50");
		jdbc.update("UPDATE accommodation SET base_price = 0, check_in_time = '00:00:00', time_zone_id = 'America/New_York' WHERE id = 31");
		jdbc.update("UPDATE address SET state = null, district = null, detail = '' WHERE id = 40");
		jdbc.update("UPDATE accommodation_amenity SET count = 0 WHERE id = 70");
		jdbc.update("""
			INSERT INTO accommodation_image (id, accommodation_id, image_url, updated_at)
			VALUES (305, 31, '/last.png', NOW(6)), (300, 31, '/first.png', NOW(6))
			""");
		Statistics statistics = prepareMeasurement();

		var response = service.findHostAccommodationDetail(31L, 202L);

		assertThat(response.policy().maxOccupancy()).isEqualTo(4);
		assertThat(response.policy().infantOccupancy()).isNull();
		assertThat(response.policy().petOccupancy()).isZero();
		assertThat(response.basePrice()).isZero();
		assertThat(response.checkInTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
		assertThat(response.timeZoneId()).isEqualTo("America/New_York");
		assertThat(response.address().state()).isNull();
		assertThat(response.address().detail()).isEmpty();
		assertThat(response.amenities()).containsExactly(new AmenityResponse.AmenityInfo("WIFI", 0));
		assertThat(response.images()).extracting(ImageResponse.ImageInfo::id).containsExactly(300L, 301L, 305L);
		assertReadQueries(statistics);
	}

	@Test
	void readsCurrentStoredValuesOnSubsequentUncachedLookup() {
		service.findHostAccommodationDetail(31L, 202L);
		jdbc.update("UPDATE accommodation SET name = '수정된 숙소', base_price = 170000 WHERE id = 31");
		jdbc.update("UPDATE address SET detail = '수정된 상세주소' WHERE id = 40");
		jdbc.update("UPDATE occupancy_policy SET max_occupancy = 6, infant_occupancy = 2 WHERE id = 50");
		Statistics statistics = prepareMeasurement();

		var response = service.findHostAccommodationDetail(31L, 202L);

		assertThat(response.name()).isEqualTo("수정된 숙소");
		assertThat(response.basePrice()).isEqualTo(170000L);
		assertThat(response.address().detail()).isEqualTo("수정된 상세주소");
		assertThat(response.policy().maxOccupancy()).isEqualTo(6);
		assertThat(response.policy().infantOccupancy()).isEqualTo(2);
		assertReadQueries(statistics);
	}

	@ParameterizedTest
	@ValueSource(longs = {203L, 999L})
	void rejectsOtherMembersBeforeLoadingCollections(long memberId) {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findHostAccommodationDetail(31L, memberId))
			.isInstanceOf(AccommodationNotFoundException.class);
		assertRejectedQuery(statistics);
	}

	@Test
	void rejectsUnknownAccommodation() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.findHostAccommodationDetail(999L, 202L))
			.isInstanceOf(AccommodationNotFoundException.class);
		assertRejectedQuery(statistics);
	}

	private void assertReadQueries(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
		assertThat(sqlCapture.statements).hasSize(3);
		assertThat(sqlCapture.statements.getFirst()).doesNotContain("accommodation_image", "accommodation_amenity");
		assertThat(sqlCapture.statements).noneMatch(sql -> sql.contains("review") || sql.contains("wishlist") || sql.contains(" join member "));
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(sqlCapture.statements.stream()
			.map(sql -> sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length))
			.containsExactly(19, 2, 2);
		assertThat(sqlCapture.statements.getFirst())
			.doesNotContain(".latitude", ".longitude", ".created_at", ".updated_at", ".thumbnail_url", " for update");
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

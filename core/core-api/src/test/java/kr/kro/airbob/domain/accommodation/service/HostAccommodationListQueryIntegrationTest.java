package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest.CursorPageRequest;
import kr.kro.airbob.cursor.dto.CursorResponse.PageInfo;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.HostAccommodationInfo;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.HostAccommodationInfos;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse.AddressSummaryInfo;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({
	ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, AccommodationQueryService.class,
	CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class,
	HostAccommodationListQueryIntegrationTest.SqlCaptureConfig.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("호스트 숙소 목록 projection MySQL 통합 테스트")
class HostAccommodationListQueryIntegrationTest {

	private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-09-07T01:02:03.123456");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_host_accommodation_list");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private AccommodationQueryService service;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CursorDecoder cursorDecoder;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@MockitoBean private ReservationInventoryService inventoryService;
	@MockitoBean private BookingWindowProvider bookingWindowProvider;
	@MockitoBean private AccommodationDetailReader detailReader;
	@MockitoBean private AccommodationDetailCache detailCache;

	@BeforeEach
	void setUp() {
		jdbc.update("""
			INSERT INTO member (id, nickname, status, updated_at)
			VALUES (7, 'host', 'ACTIVE', NOW(6)), (8, 'other', 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO address (id, country, state, city, district, street, detail, postal_code, updated_at)
			VALUES (21, '대한민국', '서울특별시', '서울', '마포구', '양화로', '101호', '04000', NOW(6))
			""");
	}

	@ParameterizedTest
	@EnumSource(value = AccommodationStatus.class, names = {"PUBLISHED", "DRAFT", "UNPUBLISHED"})
	@DisplayName("각 상태에서 열 개 컬럼만 읽고 호스트·정렬·커서·마지막 페이지를 보존한다")
	void paginatesWithOnlyResponseAndCursorColumns(AccommodationStatus status) {
		for (long id = 31; id <= 34; id++) {
			insertAccommodation(id, 7, status, id == 34);
		}
		jdbc.update("UPDATE accommodation SET created_at = ? WHERE id = 31", CREATED_AT.minusDays(1));
		insertAccommodation(35, 8, status, false);
		insertAccommodation(36, 7, status == AccommodationStatus.PUBLISHED
			? AccommodationStatus.DRAFT : AccommodationStatus.PUBLISHED, false);
		insertAccommodation(37, 7, AccommodationStatus.DELETED, false);

		HostAccommodationInfos first = find(7, status, null, 2);
		assertThat(first.accommodations()).extracting(HostAccommodationInfo::id).containsExactly(34L, 33L);
		assertThat(first.pageInfo().hasNext()).isTrue();
		assertThat(first.pageInfo().currentSize()).isEqualTo(2);
		assertThat(first.accommodations().getFirst().addressSummary())
			.isEqualTo(new AddressSummaryInfo(null, null, null, null));
		assertThat(first.accommodations().getFirst().name()).isNull();
		assertThat(first.accommodations().getLast().name()).isEqualTo("서울 하우스 33");
		assertThat(first.accommodations().getLast().thumbnailUrl()).isEqualTo("/stay.jpg");
		assertThat(first.accommodations().getLast().type()).isEqualTo("APARTMENT");
		assertThat(first.accommodations().getLast().status()).isEqualTo(status);
		assertThat(first.accommodations().getLast().createdAt())
			.isEqualTo(Instant.parse("2026-09-07T01:02:03.123456Z"));
		CursorData cursor = cursorDecoder.decode(first.pageInfo().nextCursor(), CursorData.class);
		assertThat(cursor).isEqualTo(new CursorData(33L, CREATED_AT));

		HostAccommodationInfos second = find(7, status, cursor, 2);
		assertThat(second.accommodations()).extracting(HostAccommodationInfo::id).containsExactly(32L, 31L);
		assertThat(second.pageInfo()).isEqualTo(new PageInfo(false, null, 2));

		HostAccommodationInfos tail = find(7, status, new CursorData(31L, CREATED_AT.minusDays(1)), 2);
		assertEmpty(tail);
		assertOnlyResponseAndCursorColumns();
	}

	@Test
	@DisplayName("상태 필터가 없으면 삭제된 숙소를 제외한 응답이 프론트 계약과 일치한다")
	void unfilteredListMatchesFrontendContract() throws Exception {
		insertAccommodation(31, 7, AccommodationStatus.PUBLISHED, false);
		insertAccommodation(32, 7, AccommodationStatus.UNPUBLISHED, false);
		insertAccommodation(33, 7, AccommodationStatus.DRAFT, true);
		insertAccommodation(34, 7, AccommodationStatus.DELETED, false);
		insertAccommodation(35, 8, AccommodationStatus.PUBLISHED, false);

		HostAccommodationInfos response = find(7, null, null, 20);

		try (var fixture = new ClassPathResource("contracts/host-accommodation-list.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertOnlyResponseAndCursorColumns();
	}

	@Test
	@DisplayName("삭제 상태 요청과 숙소 없는 호스트는 빈 마지막 페이지를 반환한다")
	void deletedFilterAndHostWithoutListingsReturnEmptyPage() {
		insertAccommodation(31, 7, AccommodationStatus.DELETED, false);
		assertEmpty(find(7, AccommodationStatus.DELETED, null, 20));
		assertEmpty(find(8, null, null, 20));
		assertOnlyResponseAndCursorColumns();
	}

	private HostAccommodationInfos find(long hostId, AccommodationStatus status, CursorData cursor, int size) {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		HostAccommodationInfos result = service.findMyAccommodations(hostId,
			CursorPageRequest.builder().size(size)
				.lastId(cursor == null ? null : cursor.id())
				.lastCreatedAt(cursor == null ? null : cursor.lastCreatedAt()).build(), status);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		return result;
	}

	private void assertOnlyResponseAndCursorColumns() {
		assertThat(sqlCapture.statements).hasSize(1);
		String sql = sqlCapture.statements.getFirst();
		String select = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(select.split(",")).hasSize(10);
		assertThat(sql).contains("left join address");
		assertThat(entityManagerFactory.unwrap(SessionFactory.class).getStatistics().getEntityLoadCount()).isZero();
	}

	private void assertEmpty(HostAccommodationInfos result) {
		assertThat(result.accommodations()).isEmpty();
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 0));
	}

	private void insertAccommodation(long id, long hostId, AccommodationStatus status, boolean incomplete) {
		jdbc.update("""
			INSERT INTO accommodation
				(id, name, member_id, address_id, thumbnail_url, type, status, accommodation_uid,
				check_in_time, check_out_time, time_zone_id, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, UUID_TO_BIN(UUID()),
				'15:00:00', '11:00:00', 'Asia/Seoul', ?, NOW(6))
			""", id, incomplete ? null : "서울 하우스 " + id, hostId, incomplete ? null : 21L,
			incomplete ? null : "/stay.jpg", incomplete ? null : "APARTMENT", status.name(), CREATED_AT);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SqlCaptureConfig {
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

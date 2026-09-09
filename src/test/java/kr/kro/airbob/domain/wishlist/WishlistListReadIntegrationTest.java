package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.params.provider.ValueSource;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, WishlistService.class,
	CursorPageInfoCreator.class, CursorEncoder.class, CursorDecoder.class,
	WishlistListReadIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("위시리스트 목록·저장 선택 MySQL 조회 계약")
class WishlistListReadIntegrationTest {

	private static final long OWNER_ID = 7L;

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_wishlist_list_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private WishlistService service;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CursorDecoder cursorDecoder;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, nickname, status, updated_at)
			VALUES (7, 'owner', 'ACTIVE', NOW(6)), (8, 'other', 'ACTIVE', NOW(6)), (9, 'empty', 'ACTIVE', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO accommodation (id, name, member_id, thumbnail_url, status, accommodation_uid,
				check_in_time, check_out_time, time_zone_id, updated_at)
			VALUES (31, '공개 숙소', 7, '/stay.jpg', 'PUBLISHED', UUID_TO_BIN(UUID()), '15:00:00', '11:00:00', 'Asia/Seoul', NOW(6)),
				(32, '비공개 숙소', 7, '/private.jpg', 'UNPUBLISHED', UUID_TO_BIN(UUID()), '15:00:00', '11:00:00', 'Asia/Seoul', NOW(6)),
				(33, '사진 없는 숙소', 7, NULL, 'PUBLISHED', UUID_TO_BIN(UUID()), '15:00:00', '11:00:00', 'Asia/Seoul', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO wishlist (id, name, member_id, status, accommodation_count, representative_accommodation_id,
				created_at, updated_at)
			VALUES (44, '빈 여행', 7, 'ACTIVE', 0, NULL, '2026-07-02 00:00:00', NOW(6)),
				(43, '함께 여행', 7, 'ACTIVE', 1, 31, '2026-07-02 00:00:00', NOW(6)),
				(42, '겨울 여행', 7, 'ACTIVE', 2, 32, '2026-07-02 00:00:00', NOW(6)),
				(41, '지난 여행', 7, 'ACTIVE', 1, 33, '2026-07-01 00:00:00', NOW(6)),
				(45, '삭제 여행', 7, 'DELETED', 1, 31, '2026-07-03 00:00:00', NOW(6)),
				(46, '타인 여행', 8, 'ACTIVE', 1, 31, '2026-07-03 00:00:00', NOW(6))
			""");
		jdbc.update("""
			INSERT INTO wishlist_accommodation (id, wishlist_id, accommodation_id, created_at, updated_at)
			VALUES (501, 43, 31, '2026-07-02 00:00:00', NOW(6)),
				(502, 42, 31, '2026-07-01 00:00:00', NOW(6)), (503, 42, 32, '2026-07-02 00:00:00', NOW(6)),
				(504, 41, 33, '2026-07-01 00:00:00', NOW(6)), (505, 45, 31, '2026-07-03 00:00:00', NOW(6)),
				(506, 46, 31, '2026-07-03 00:00:00', NOW(6))
			""");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("일반 목록과 선택 모달의 공유 JSON·사진·저장 개수·연결 ID를 보존한다")
	void returnsSharedFrontendContract(boolean selection) throws Exception {
		var result = read(OWNER_ID, selection ? 31L : null, firstPage(20));

		try (var fixture = new ClassPathResource("contracts/wishlist-list-with-membership.json").getInputStream()) {
			ObjectNode expected = (ObjectNode)objectMapper.readTree(fixture);
			if (!selection) {
				expected.get("wishlists").forEach(row -> {
					((ObjectNode)row).putNull("is_contained");
					((ObjectNode)row).putNull("wishlist_accommodation_id");
				});
			}
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result))).isEqualTo(expected);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("같은 생성시각과 더 오래된 시각의 경계를 중복·누락 없이 넘는다")
	void preservesCursorAcrossTiedTimes(boolean selection) {
		Long accommodationId = selection ? 31L : null;
		var first = read(OWNER_ID, accommodationId, firstPage(2));
		assertThat(first.wishlists()).extracting(WishlistResponse.WishlistInfo::id).containsExactly(44L, 43L);
		assertThat(first.pageInfo().hasNext()).isTrue();
		CursorData cursor = cursorDecoder.decode(first.pageInfo().nextCursor(), CursorData.class);
		assertThat(cursor.id()).isEqualTo(43L);

		var second = read(OWNER_ID, accommodationId, CursorPageRequest.builder().size(2)
			.lastId(cursor.id()).lastCreatedAt(cursor.lastCreatedAt()).build());
		assertThat(second.wishlists()).extracting(WishlistResponse.WishlistInfo::id).containsExactly(42L, 41L);
		assertThat(second.pageInfo()).isEqualTo(new PageInfo(false, null, 2));
		if (selection) {
			assertThat(second.wishlists().getFirst().wishlistAccommodationId()).isEqualTo(502L);
			assertThat(second.wishlists().getLast().isContained()).isFalse();
		}
	}

	@ParameterizedTest
	@EnumSource(AccommodationStatus.class)
	@DisplayName("대표 사진과 선택한 숙소의 저장 관계에 공개 상태 필터를 추가하지 않는다")
	void preservesStoredRepresentativeAndMembershipAcrossAccommodationStatuses(AccommodationStatus status) {
		jdbc.update("UPDATE accommodation SET status = ? WHERE id = 31", status.name());

		var result = read(OWNER_ID, 31L, firstPage(20));

		assertThat(result.wishlists()).filteredOn(info -> info.id() == 43L).singleElement().satisfies(info -> {
			assertThat(info.thumbnailImageUrl()).isEqualTo("/stay.jpg");
			assertThat(info.isContained()).isTrue();
			assertThat(info.wishlistAccommodationId()).isEqualTo(501L);
		});
	}

	@Test
	@DisplayName("대표 없음·삭제된 대표 ID·사진 null도 위시리스트를 누락시키지 않는다")
	void keepsListsWithMissingRepresentativesAndNullImages() {
		jdbc.update("UPDATE wishlist SET representative_accommodation_id = 999 WHERE id = 42");
		jdbc.update("UPDATE accommodation SET thumbnail_url = NULL WHERE id = 31");

		var result = read(OWNER_ID, 31L, firstPage(20));

		assertThat(result.wishlists()).extracting(WishlistResponse.WishlistInfo::id).containsExactly(44L, 43L, 42L, 41L);
		assertThat(result.wishlists()).allMatch(info -> info.thumbnailImageUrl() == null);
		assertThat(result.wishlists()).extracting(WishlistResponse.WishlistInfo::wishlistItemCount)
			.containsExactly(0L, 1L, 2L, 1L);
	}

	@Test
	@DisplayName("여러 목록이 같은 대표 숙소를 참조해도 각 위시리스트를 한 번만 반환한다")
	void sharedRepresentativeDoesNotMultiplyRows() {
		jdbc.update("UPDATE wishlist SET representative_accommodation_id = 31 WHERE id = 42");

		var result = read(OWNER_ID, 31L, firstPage(20));

		assertThat(result.wishlists()).extracting(WishlistResponse.WishlistInfo::id).containsExactly(44L, 43L, 42L, 41L);
		assertThat(result.wishlists()).filteredOn(info -> info.id() == 43L || info.id() == 42L)
			.extracting(WishlistResponse.WishlistInfo::thumbnailImageUrl).containsExactly("/stay.jpg", "/stay.jpg");
	}

	@ParameterizedTest
	@ValueSource(longs = {32L, 999L})
	@DisplayName("저장되지 않은 숙소는 false·null이고 비공개 숙소도 정확한 연결 ID를 반환한다")
	void distinguishesSelectedAccommodation(long accommodationId) {
		var result = read(OWNER_ID, accommodationId, firstPage(20));

		assertThat(result.wishlists()).allSatisfy(info -> {
			boolean contained = accommodationId == 32L && info.id() == 42L;
			assertThat(info.isContained()).isEqualTo(contained);
			assertThat(info.wishlistAccommodationId()).isEqualTo(contained ? 503L : null);
		});
	}

	@ParameterizedTest
	@ValueSource(longs = {9L, 999L})
	@DisplayName("보유 목록이 없거나 미존재 회원이면 빈 마지막 페이지를 반환한다")
	void returnsEmptyPageWithoutOtherMembersLists(long memberId) {
		var result = read(memberId, 31L, firstPage(20));

		assertThat(result.wishlists()).isEmpty();
		assertThat(result.pageInfo()).isEqualTo(new PageInfo(false, null, 0));
	}

	@Test
	@DisplayName("이름 변경과 마지막 저장 삭제 후 개수·사진·선택 상태를 다시 읽는다")
	void reflectsRenameAndLastMembershipRemoval() {
		read(OWNER_ID, 31L, firstPage(20));
		service.updateWishlist(43L, new WishlistRequest.Update("변경한 여행"), OWNER_ID);
		service.deleteWishlistAccommodation(501L, OWNER_ID);

		var result = read(OWNER_ID, 31L, firstPage(20));

		assertThat(result.wishlists()).filteredOn(info -> info.id() == 43L).singleElement().satisfies(info -> {
			assertThat(info.name()).isEqualTo("변경한 여행");
			assertThat(info.wishlistItemCount()).isZero();
			assertThat(info.thumbnailImageUrl()).isNull();
			assertThat(info.isContained()).isFalse();
			assertThat(info.wishlistAccommodationId()).isNull();
		});
	}

	private CursorPageRequest firstPage(int size) {
		return CursorPageRequest.builder().size(size).build();
	}

	private WishlistResponse.WishlistInfos read(long memberId, Long accommodationId, CursorPageRequest request) {
		entityManager.flush();
		entityManager.clear();
		entityManagerFactory.unwrap(SessionFactory.class).getStatistics().clear();
		sqlCapture.statements.clear();
		var result = service.findWishlists(request, memberId, accommodationId);
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(statistics.getEntityLoadCount()).isZero();
		assertThat(selectedColumnCounts()).containsExactly(6);
		String sql = sqlCapture.statements.getFirst();
		assertThat(sql).contains("left join accommodation ").doesNotContain(" join member ", " for update");
		if (accommodationId == null) {
			assertThat(sql).doesNotContain("wishlist_accommodation");
		} else {
			assertThat(sql).contains("left join wishlist_accommodation ");
		}
		String selected = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(selected).doesNotContain(".member_id", ".status", ".representative_accommodation_id",
			".updated_at", ".created_by", ".updated_by", ".description", ".base_price");
		return result;
	}

	private List<Integer> selectedColumnCounts() {
		return sqlCapture.statements.stream()
			.map(sql -> sql.substring("select ".length(), sql.indexOf(" from ")).split(",").length).toList();
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

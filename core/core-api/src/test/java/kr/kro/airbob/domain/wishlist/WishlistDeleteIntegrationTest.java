package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.*;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
import jakarta.persistence.EntityManager;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.QueryCountContext;
import kr.kro.airbob.common.monitoring.QueryCountContextHolder;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"accommodation.detail-cache.enabled=false",
	"spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("위시리스트 벌크 삭제 MySQL 통합 테스트")
class WishlistDeleteIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_wishlist_delete_test");

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
	}

	@Autowired private WishlistService wishlistService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private EntityManager entityManager;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	private long ownerId;

	@BeforeEach
	void setUp() {
		ownerId = insertMember();
		UserContext.set(new UserInfo(ownerId, "127.0.0.1", "TEST"));
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		QueryCountContextHolder.clear();
		jdbcTemplate.update("DELETE FROM wishlist_accommodation");
		jdbcTemplate.update("DELETE FROM wishlist");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}


	@ParameterizedTest
	@ValueSource(ints = {0, 3, 1000})
	@DisplayName("위시리스트를 soft delete하고 대상 연결만 최대 한 번의 DELETE로 제거한다")
	void deletesOnlyTargetMemberships(int size) {
		Fixture fixture = createFixture(size);
		Map<String, Object> control = wishlistSnapshot(fixture.controlWishlistId());
		Map<String, Object> target = wishlistSnapshot(fixture.targetWishlistId());
		QueryCountContext context = new QueryCountContext("DELETE", "/api/v1/wishlists/{id}");
		QueryCountContextHolder.initContext(context);
		try {
			wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId);
		} finally {
			QueryCountContextHolder.clear();
		}
		assertThat(context.snapshot().countOf(SqlQueryType.SELECT)).isEqualTo(2);
		assertThat(context.snapshot().countOf(SqlQueryType.DELETE)).isEqualTo(size == 0 ? 0 : 1);
		assertThat(context.snapshot().countOf(SqlQueryType.UPDATE)).isEqualTo(1);
		Map<String, Object> deleted = wishlistSnapshot(fixture.targetWishlistId());
		assertThat(deleted.get("status")).isEqualTo("DELETED");
		assertThat(deleted.get("accommodation_count")).isEqualTo(target.get("accommodation_count"));
		assertThat(deleted.get("representative_accommodation_id")).isEqualTo(target.get("representative_accommodation_id"));
		assertThat(deleted.get("updated_by")).isEqualTo(ownerId);
		assertThat(membershipCount(fixture.targetWishlistId())).isZero();
		assertThat(wishlistSnapshot(fixture.controlWishlistId())).isEqualTo(control);
		assertThat(membershipCount(fixture.controlWishlistId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accommodation", Long.class))
			.isEqualTo(Math.max(size, 1));
	}

	@Test
	@DisplayName("flush 후 예외가 발생하면 연결 삭제와 위시리스트 soft delete가 함께 롤백된다")
	void rollsBackBulkDeleteAndSoftDeleteTogether() {
		Fixture fixture = createFixture(2);
		Map<String, Object> target = wishlistSnapshot(fixture.targetWishlistId());
		Map<String, Object> control = wishlistSnapshot(fixture.controlWishlistId());
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId);
			entityManager.flush();
			throw new IntentionalRollbackException();
		})).isInstanceOf(IntentionalRollbackException.class);
		assertThat(wishlistSnapshot(fixture.targetWishlistId())).isEqualTo(target);
		assertThat(membershipCount(fixture.targetWishlistId())).isEqualTo(2);
		assertThat(wishlistSnapshot(fixture.controlWishlistId())).isEqualTo(control);
		assertThat(membershipCount(fixture.controlWishlistId())).isEqualTo(1);
	}

	@Test
	@DisplayName("없는 위시리스트와 타인 소유 위시리스트는 삭제하지 않는다")
	void preservesExistingNotFoundAndOwnershipFailures() {
		Fixture fixture = createFixture(1);

		assertThatThrownBy(() -> wishlistService.deleteWishlist(Long.MAX_VALUE, ownerId))
			.isInstanceOf(WishlistNotFoundException.class);
		assertThatThrownBy(() -> wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId + 1))
			.isInstanceOf(WishlistAccessDeniedException.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?",
			Long.class,
			fixture.targetWishlistId()
		)).isEqualTo(1L);
	}

	private Fixture createFixture(int size) {
		List<Long> accommodations = new ArrayList<>();
		for (int i = 0; i < Math.max(size, 1); i++) {
			accommodations.add(insert("""
				INSERT INTO accommodation (member_id, name, accommodation_uid, status, time_zone_id,
				  check_in_time, check_out_time, created_at, updated_at)
				VALUES (?, 'wishlist-test', UUID_TO_BIN(UUID()), 'DRAFT', 'UTC', '15:00:00', '11:00:00', NOW(6), NOW(6))
				""", ownerId));
		}
		long target = insertWishlist("target", size, size == 0 ? null : accommodations.getLast());
		long control = insertWishlist("control", 1, accommodations.getFirst());
		for (int i = 0; i < size; i++) {
			insertMembership(target, accommodations.get(i));
		}
		insertMembership(control, accommodations.getFirst());
		return new Fixture(target, control);
	}

	private long insertWishlist(String name, int count, Long representativeId) {
		return insert("""
			INSERT INTO wishlist (name, member_id, status, accommodation_count, representative_accommodation_id,
			  created_at, updated_at, created_by, updated_by)
			VALUES (?, ?, 'ACTIVE', ?, ?, NOW(6), NOW(6), ?, ?)
			""", name, ownerId, count, representativeId, ownerId, ownerId);
	}

	private void insertMembership(long wishlistId, long accommodationId) {
		jdbcTemplate.update("""
			INSERT INTO wishlist_accommodation (wishlist_id, accommodation_id, created_at, updated_at, created_by, updated_by)
			VALUES (?, ?, NOW(6), NOW(6), ?, ?)
			""", wishlistId, accommodationId, ownerId, ownerId);
	}

	private long insert(String sql, Object... values) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int i = 0; i < values.length; i++) {
				statement.setObject(i + 1, values[i]);
			}
			return statement;
		}, key);
		return key.getKey().longValue();
	}

	private long membershipCount(long wishlistId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?",
			Long.class, wishlistId);
	}

	private Map<String, Object> wishlistSnapshot(long wishlistId) {
		return jdbcTemplate.queryForMap("SELECT * FROM wishlist WHERE id = ?", wishlistId);
	}

	private record Fixture(long targetWishlistId, long controlWishlistId) {
	}

	private long insertMember() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
				"""
				INSERT INTO member (email, nickname, role, status, created_at, updated_at)
				VALUES (?, ?, 'MEMBER', 'ACTIVE', NOW(6), NOW(6))
				""",
				Statement.RETURN_GENERATED_KEYS
			);
			statement.setString(1, "wishlist-test-owner@example.test");
			statement.setString(2, "wishlist-test-owner");
			return statement;
		}, keyHolder);
		assertThat(keyHolder.getKey()).isNotNull();
		return keyHolder.getKey().longValue();
	}

	static class IntentionalRollbackException extends RuntimeException {
	}
}

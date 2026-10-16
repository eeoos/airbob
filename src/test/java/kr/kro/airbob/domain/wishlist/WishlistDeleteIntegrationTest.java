package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.*;

import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
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
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteFixtureService;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteFixtureService.Fixture;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({WishlistDeleteIntegrationTest.RollbackProbeConfiguration.class, WishlistDeleteFixtureService.class})
@DisplayName("위시리스트 벌크 삭제 MySQL 통합 테스트")
class WishlistDeleteIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_bulk_write_benchmark");

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.load()
			.migrate();

		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
	}

	@Autowired private WishlistDeleteFixtureService fixtureService;
	@Autowired private WishlistService wishlistService;
	@Autowired private BulkOperationMonitor bulkOperationMonitor;
	@Autowired private RollbackProbe rollbackProbe;
	@Autowired private JdbcTemplate jdbcTemplate;

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
		jdbcTemplate.update("DELETE FROM wishlist_accommodation");
		jdbcTemplate.update("DELETE FROM wishlist");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}






	@ParameterizedTest
	@ValueSource(ints = {0, 3, 1000})
	@DisplayName("연결 숙소 수와 관계없이 벌크 삭제하고 다른 위시리스트와 숙소를 보존한다")
	void deletesMembershipsInOneStatement(int size) {
		Fixture fixture = fixtureService.createFixture(ownerId, size);
		var snapshot = bulkOperationMonitor.monitor("wishlist-delete-regression",
			() -> wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId));
		var verification = fixtureService.verify(fixture);
		assertThat(verification.succeeded()).isTrue();
		assertThat(verification.verifiedRows()).isEqualTo(size);
		assertThat(verification.targetWishlistDeleted()).isTrue();
		assertThat(verification.targetMembershipsDeleted()).isTrue();
		assertThat(verification.targetDenormalizedStatePreserved()).isTrue();
		assertThat(verification.controlWishlistPreserved()).isTrue();
		assertThat(verification.controlMembershipPreserved()).isTrue();
		assertThat(verification.accommodationsPreserved()).isTrue();
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.SELECT)).isEqualTo(2);
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.UPDATE)).isOne();
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.DELETE)).isEqualTo(size == 0 ? 0 : 1);
		fixtureService.cleanup(fixture);
	}

	@Test
	@DisplayName("flush 후 예외가 발생하면 bulk DELETE와 Wishlist soft delete가 함께 rollback된다")
	void rollsBackBulkDeleteAndSoftDeleteTogether() {
		Fixture fixture = fixtureService.createFixture(ownerId, 2);

		assertThatThrownBy(() -> rollbackProbe.deleteFlushAndFail(
			fixture.targetWishlistId(),
			ownerId
		)).isInstanceOf(IntentionalRollbackException.class);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM wishlist WHERE id = ?",
			String.class,
			fixture.targetWishlistId()
		)).isEqualTo("ACTIVE");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?",
			Long.class,
			fixture.targetWishlistId()
		)).isEqualTo(2L);

		fixtureService.cleanup(fixture);
	}

	@Test
	@DisplayName("존재하지 않거나 다른 회원 소유인 Wishlist는 기존 예외 의미를 유지한다")
	void preservesExistingNotFoundAndOwnershipFailures() {
		Fixture fixture = fixtureService.createFixture(ownerId, 1);

		assertThatThrownBy(() -> wishlistService.deleteWishlist(Long.MAX_VALUE, ownerId))
			.isInstanceOf(WishlistNotFoundException.class);
		assertThatThrownBy(() -> wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId + 1))
			.isInstanceOf(WishlistAccessDeniedException.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?",
			Long.class,
			fixture.targetWishlistId()
		)).isEqualTo(1L);

		fixtureService.cleanup(fixture);
	}

	private long insertMember() {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(
				"""
				INSERT INTO member (email, nickname, role, status, created_at, updated_at)
				VALUES (?, ?, 'ADMIN', 'ACTIVE', NOW(6), NOW(6))
				""",
				Statement.RETURN_GENERATED_KEYS
			);
			statement.setString(1, "bulk-write-admin@example.test");
			statement.setString(2, "bulk-write-admin");
			return statement;
		}, keyHolder);
		assertThat(keyHolder.getKey()).isNotNull();
		return keyHolder.getKey().longValue();
	}



	@TestConfiguration(proxyBeanMethods = false)
	static class RollbackProbeConfiguration {

		@Bean
		RollbackProbe rollbackProbe(WishlistService wishlistService, EntityManager entityManager) {
			return new RollbackProbe(wishlistService, entityManager);
		}
	}

	static class RollbackProbe {

		private final WishlistService wishlistService;
		private final EntityManager entityManager;

		RollbackProbe(WishlistService wishlistService, EntityManager entityManager) {
			this.wishlistService = wishlistService;
			this.entityManager = entityManager;
		}

		@Transactional
		public void deleteFlushAndFail(long wishlistId, long ownerId) {
			wishlistService.deleteWishlist(wishlistId, ownerId);
			entityManager.flush();
			throw new IntentionalRollbackException();
		}
	}

	static class IntentionalRollbackException extends RuntimeException {
	}
}

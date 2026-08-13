package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.*;

import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import jakarta.persistence.EntityManager;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkFixtureService;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkFixtureService.Fixture;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBeforeBenchmarkService;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkService;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.bulk-write.enabled=true",
	"benchmark.bulk-write.token=bulk-write-integration-token-123456789",
	"benchmark.bulk-write.allowed-schema=airbob_bulk_write_benchmark"
})
@ActiveProfiles({"test", "bulk-write-benchmark"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(WishlistDeleteBenchmarkIntegrationTest.RollbackProbeConfiguration.class)
@DisplayName("Wishlist 삭제 Before/After 벌크 쓰기 비교 통합 테스트")
class WishlistDeleteBenchmarkIntegrationTest {

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

	@Autowired private WishlistDeleteBenchmarkService benchmarkService;
	@Autowired private WishlistDeleteBeforeBenchmarkService beforeService;
	@Autowired private WishlistDeleteBenchmarkFixtureService fixtureService;
	@Autowired private WishlistService wishlistService;
	@Autowired private RollbackProbe rollbackProbe;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private ObjectMapper objectMapper;

	@MockitoBean private ElasticsearchClient elasticsearchClient;
	@MockitoBean private ElasticsearchOperations elasticsearchOperations;
	@MockitoBean private AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean private S3Template s3Template;

	private long ownerId;

	@BeforeEach
	void setUp() {
		ownerId = insertMember();
		UserContext.set(new UserInfo(ownerId, "127.0.0.1", "BENCHMARK"));
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
		jdbcTemplate.update("DELETE FROM wishlist_accommodation");
		jdbcTemplate.update("DELETE FROM wishlist");
		jdbcTemplate.update("DELETE FROM accommodation");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("N개 membership을 실제 서비스로 삭제하고 fixture 밖 SQL을 제외한 Before snapshot을 반환한다")
	void measuresActualBeforeDeleteAndPreservesUnrelatedFixture() throws Exception {
		Fixture unrelated = fixtureService.createFixture(ownerId, 1);

		var response = benchmarkService.run(
			ownerId,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 3)
		);

		assertThat(AopUtils.isAopProxy(beforeService)).isTrue();
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.targetWishlistDeleted()).isTrue();
		assertThat(response.targetMembershipsDeleted()).isTrue();
		assertThat(response.targetDenormalizedStatePreserved()).isTrue();
		assertThat(response.controlWishlistPreserved()).isTrue();
		assertThat(response.controlMembershipPreserved()).isTrue();
		assertThat(response.accommodationsPreserved()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.DELETE, 3)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, 6);
		assertThat(response.operation().jdbcBatchCalls()).isZero();
		assertThat(response.operation().jdbcSubmittedRows()).isZero();
		assertThat(response.operation().jdbcConfiguredBatchSize()).isNull();
		assertThat(response.operation().jdbcAffectedRows()).isNull();

		assertThat(countById("wishlist", unrelated.targetWishlistId())).isOne();
		assertThat(countById("wishlist", unrelated.controlWishlistId())).isOne();
		assertThat(countById("wishlist_accommodation", unrelated.controlMembershipId())).isOne();
		assertThat(countById("accommodation", unrelated.controlAccommodationId())).isOne();
		assertThat(countRows("wishlist")).isEqualTo(2);
		assertThat(countRows("wishlist_accommodation")).isEqualTo(2);
		assertThat(countRows("accommodation")).isOne();

		String serialized = objectMapper.writeValueAsString(response);
		assertThat(serialized)
			.doesNotContain("target_wishlist_id")
			.doesNotContain("control_wishlist_id")
			.doesNotContain("owner_id")
			.doesNotContain("bulk-write-integration-token");

		fixtureService.cleanup(unrelated);
		fixtureService.cleanup(unrelated);
	}

	@Test
	@DisplayName("0개 membership도 2 SELECT와 Wishlist soft delete UPDATE만으로 완료한다")
	void supportsEmptyTargetWishlist() {
		var response = benchmarkService.run(
			ownerId,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 0)
		);

		assertThat(response.verifiedRows()).isZero();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.TOTAL, 3);
	}

	@Test
	@DisplayName("After 벤치마크는 N개 membership을 단일 bulk DELETE로 제거한다")
	void measuresActualAfterDeleteWithSingleBulkDelete() {
		var response = benchmarkService.run(
			ownerId,
			new WishlistDeleteBenchmarkRequest(Variant.AFTER, 3)
		);

		assertThat(AopUtils.isAopProxy(wishlistService)).isTrue();
		assertThat(response.variant()).isEqualTo(Variant.AFTER);
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().operationName()).isEqualTo("wishlist-delete-after");
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.DELETE, 1)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, 4);
		assertThat(response.operation().jdbcBatchCalls()).isZero();
		assertThat(response.operation().jdbcSubmittedRows()).isZero();
		assertThat(response.operation().jdbcConfiguredBatchSize()).isNull();
		assertThat(response.operation().jdbcAffectedRows()).isNull();
	}

	@Test
	@DisplayName("After의 0개 membership은 bulk DELETE 없이 완료한다")
	void supportsEmptyTargetWishlistAfterImprovement() {
		var response = benchmarkService.run(
			ownerId,
			new WishlistDeleteBenchmarkRequest(Variant.AFTER, 0)
		);

		assertThat(response.verifiedRows()).isZero();
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.TOTAL, 3);
	}

	@Test
	@DisplayName("After는 최대 허용 크기 1000개도 단일 bulk DELETE로 처리한다")
	void supportsMaximumDatasetWithSingleBulkDelete() {
		var response = benchmarkService.run(
			ownerId,
			new WishlistDeleteBenchmarkRequest(Variant.AFTER, 1000)
		);

		assertThat(response.verifiedRows()).isEqualTo(1000);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.UPDATE, 1)
			.containsEntry(SqlQueryType.DELETE, 1)
			.containsEntry(SqlQueryType.TOTAL, 4);
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

	private long countById(String tableName, long id) {
		Long count = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM " + tableName + " WHERE id = ?",
			Long.class,
			id
		);
		return count == null ? 0 : count;
	}

	private long countRows(String tableName) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
		return count == null ? 0 : count;
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

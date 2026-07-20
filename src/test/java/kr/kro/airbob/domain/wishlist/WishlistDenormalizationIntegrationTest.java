package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

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
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistBenchmarkService;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(properties = {
	"spring.cloud.aws.s3.enabled=false",
	"benchmark.read-model.enabled=true",
	"benchmark.read-model.token=test-token"
})
@ActiveProfiles({"test", "read-model-benchmark"})
@DisplayName("위시리스트 반정규화(개수/대표 썸네일) 통합 테스트")
class WishlistDenormalizationIntegrationTest {

	@Autowired private WishlistService wishlistService;
	@Autowired private WishlistBenchmarkService benchmarkService;
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

	private long host;
	private long acc1;
	private long acc2;
	private long acc3;
	private long wishlistId;

	@BeforeEach
	void setup() {
		clean();
		host = insertMember("host");
		acc1 = insertAccommodation("url-1");
		acc2 = insertAccommodation("url-2");
		acc3 = insertAccommodation("url-3");
		wishlistId = wishlistService.createWishlist(new WishlistRequest.Create("내 위시리스트"), host).id();
	}

	@AfterEach
	void tearDown() {
		clean();
	}

	@Test
	@DisplayName("숙소 추가 시 개수 +1, 대표는 가장 최근 추가 숙소로 설정된다")
	void addIncrementsCountAndSetsLatestAsRepresentative() {
		add(acc1);
		add(acc2);
		add(acc3);

		assertCountAndRepresentative(3, acc3);
	}

	@Test
	@DisplayName("목록 조회는 반정규화 컬럼으로 개수와 대표 썸네일을 반환한다")
	void findWishlistsUsesDenormalizedColumns() {
		add(acc1);
		add(acc2);
		add(acc3);

		WishlistResponse.WishlistInfos result = wishlistService.findWishlists(
			CursorRequest.CursorPageRequest.builder().size(20).build(), host, null);

		assertThat(result.wishlists()).hasSize(1);
		WishlistResponse.WishlistInfo info = result.wishlists().get(0);
		assertThat(info.wishlistItemCount()).isEqualTo(3L);
		assertThat(info.thumbnailImageUrl()).isEqualTo("url-3"); // 대표 = 최신(acc3)
	}

	@Test
	@DisplayName("before 원시 집계와 after 반정규화 목록은 동일한 결과를 반환한다")
	void beforeAndAfterReturnSameWishlistInfos() {
		add(acc1);
		add(acc2);
		add(acc3);
		CursorRequest.CursorPageRequest request =
			CursorRequest.CursorPageRequest.builder().size(20).build();

		WishlistResponse.WishlistInfos before =
			benchmarkService.findWishlistsBefore(request, host, acc2);
		WishlistResponse.WishlistInfos after =
			wishlistService.findWishlists(request, host, acc2);

		assertThat(before).isEqualTo(after);
	}

	@Test
	@DisplayName("대표가 아닌 숙소 삭제: 개수만 감소하고 대표는 유지된다")
	void removeNonRepresentativeKeepsRepresentative() {
		long wa1 = add(acc1);
		add(acc2);
		add(acc3); // 대표 = acc3

		wishlistService.deleteWishlistAccommodation(wa1, host);

		assertCountAndRepresentative(2, acc3);
	}

	@Test
	@DisplayName("대표 숙소 삭제: 다음 최신으로 대표를 재선정하고, 마지막 삭제 시 대표는 NULL")
	void removeRepresentativeRecomputesNextLatest() {
		add(acc1);
		add(acc2);
		long wa3 = add(acc3); // 대표 = acc3

		// acc3(대표) 삭제 → 다음 최신 acc2
		wishlistService.deleteWishlistAccommodation(wa3, host);
		assertCountAndRepresentative(2, acc2);

		// acc2(대표) 삭제 → 다음 최신 acc1
		long wa2 = latestWaId(acc2);
		wishlistService.deleteWishlistAccommodation(wa2, host);
		assertCountAndRepresentative(1, acc1);

		// acc1(대표, 마지막) 삭제 → 대표 NULL, 개수 0
		long wa1 = latestWaId(acc1);
		wishlistService.deleteWishlistAccommodation(wa1, host);
		assertCountAndRepresentative(0, null);
	}

	// ===== helpers =====

	private long add(long accommodationId) {
		return wishlistService.createWishlistAccommodation(
			wishlistId, new WishlistAccommodationRequest.Create(accommodationId), host).id();
	}

	private long latestWaId(long accommodationId) {
		return jdbc.queryForObject(
			"SELECT id FROM wishlist_accommodation WHERE wishlist_id = ? AND accommodation_id = ?",
			Long.class, wishlistId, accommodationId);
	}

	private void assertCountAndRepresentative(int expectedCount, Long expectedRepresentative) {
		Map<String, Object> row = jdbc.queryForMap(
			"SELECT accommodation_count, representative_accommodation_id FROM wishlist WHERE id = ?", wishlistId);
		assertThat(((Number)row.get("accommodation_count")).intValue()).isEqualTo(expectedCount);
		Object rep = row.get("representative_accommodation_id");
		if (expectedRepresentative == null) {
			assertThat(rep).isNull();
		} else {
			assertThat(((Number)rep).longValue()).isEqualTo(expectedRepresentative);
		}
	}

	private void clean() {
		jdbc.update("DELETE FROM wishlist_accommodation");
		jdbc.update("DELETE FROM wishlist");
		jdbc.update("DELETE FROM accommodation");
		jdbc.update("DELETE FROM member");
	}

	private long insertMember(String nickname) {
		jdbc.update("INSERT INTO member (nickname, status, updated_at) VALUES (?, 'ACTIVE', NOW(6))", nickname);
		return jdbc.queryForObject("SELECT id FROM member ORDER BY id DESC LIMIT 1", Long.class);
	}

	private long insertAccommodation(String thumbnailUrl) {
		jdbc.update("""
			INSERT INTO accommodation
				(member_id, check_in_time, check_out_time, accommodation_uid, status, base_price, thumbnail_url, updated_at)
			VALUES (?, '15:00:00', '11:00:00', UUID_TO_BIN(UUID()), 'PUBLISHED', 100000, ?, NOW(6))
			""", host, thumbnailUrl);
		return jdbc.queryForObject("SELECT id FROM accommodation ORDER BY id DESC LIMIT 1", Long.class);
	}
}

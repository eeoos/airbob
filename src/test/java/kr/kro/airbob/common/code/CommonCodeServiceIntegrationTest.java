package kr.kro.airbob.common.code;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

/**
 * 순수 공통 코드 테이블 동작 검증.
 * V6 시드 → Flyway 적재 → 캐시 로딩 → 조회/검증이 정상 동작하는지 확인한다.
 * (enum 제거 후, 정합성의 단일 소스는 DB 시드이며 검증은 CommonCodeService 캐시가 담당)
 */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("공통 코드 서비스 통합 테스트")
class CommonCodeServiceIntegrationTest {

	@Autowired
	private CommonCodeService commonCodeService;

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

	@Test
	@DisplayName("AMENITY_TYPE: 시드된 활성 코드를 sort_order 순으로 조회한다")
	void getAmenityCodes() {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(CommonCodeGroups.AMENITY_TYPE);

		assertThat(codes).hasSize(30); // V6 시드 기준(UNKNOWN 제외)
		assertThat(codes.get(0).code()).isEqualTo("WIFI");
		assertThat(codes.get(0).name()).isEqualTo("무선 인터넷");
		assertThat(codes).extracting(CommonCodeResponse::code).contains("KITCHEN", "PARKING", "BALCONY");
	}

	@Test
	@DisplayName("ACCOMMODATION_TYPE: 시드된 활성 코드를 조회한다")
	void getAccommodationCodes() {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(CommonCodeGroups.ACCOMMODATION_TYPE);

		assertThat(codes).hasSize(16); // V6 시드 기준
		assertThat(codes).extracting(CommonCodeResponse::code)
			.contains("ENTIRE_PLACE", "HOTEL_ROOM", "CASTLE");
	}

	@Test
	@DisplayName("isValidCode: 유효 코드는 통과, 무효/존재하지 않는 코드는 거부한다")
	void validateCode() {
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, "WIFI")).isTrue();
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.ACCOMMODATION_TYPE, "HOTEL_ROOM")).isTrue();

		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, "UNKNOWN")).isFalse();
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, "NOT_EXIST")).isFalse();
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, null)).isFalse();
	}

	@Test
	@DisplayName("getLabel: 코드의 표시명을 반환하고, 없으면 코드 원본을 폴백한다")
	void getLabel() {
		assertThat(commonCodeService.getLabel(CommonCodeGroups.ACCOMMODATION_TYPE, "HOTEL_ROOM"))
			.isEqualTo("호텔 객실");
		assertThat(commonCodeService.getLabel(CommonCodeGroups.ACCOMMODATION_TYPE, "NOT_EXIST"))
			.isEqualTo("NOT_EXIST"); // 폴백
	}
}

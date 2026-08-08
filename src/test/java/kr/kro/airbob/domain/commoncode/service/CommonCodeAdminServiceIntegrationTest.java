package kr.kro.airbob.domain.commoncode.service;

import static org.assertj.core.api.Assertions.*;

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
import kr.kro.airbob.domain.commoncode.common.CommonCodeGroups;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeNotFoundException;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

/**
 * 공통 코드 관리 서비스 통합 테스트.
 * 관리자 쓰기는 DB에 즉시 반영하고, 이미 적재된 조회 캐시는 즉시 무효화하지 않는지 검증한다.
 */
@Testcontainers
@SpringBootTest(properties = "spring.cloud.aws.s3.enabled=false")
@ActiveProfiles("test")
@DisplayName("공통 코드 관리 서비스 통합 테스트")
class CommonCodeAdminServiceIntegrationTest {

	@Autowired private CommonCodeAdminService adminService;
	@Autowired private CommonCodeService commonCodeService;

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
	@DisplayName("새 코드를 생성해도 이미 적재된 조회 캐시는 즉시 무효화되지 않는다")
	void createDoesNotInvalidateCachedSnapshot() {
		// 캐시 워밍업(생성 전 상태 적재)
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, "SAUNA")).isFalse();

		adminService.create(CommonCodeGroups.AMENITY_TYPE,
			new CommonCodeRequest.Create("SAUNA", "사우나", null, 99, true));

		assertThat(adminService.getAll(CommonCodeGroups.AMENITY_TYPE))
			.anyMatch(code -> code.code().equals("SAUNA") && code.name().equals("사우나"));
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, "SAUNA")).isFalse();
		assertThat(commonCodeService.getLabel(CommonCodeGroups.AMENITY_TYPE, "SAUNA")).isEqualTo("SAUNA");
	}

	@Test
	@DisplayName("라벨을 수정해도 이미 적재된 조회 캐시는 즉시 무효화되지 않는다")
	void updateLabelDoesNotInvalidateCachedSnapshot() {
		assertThat(commonCodeService.getLabel(CommonCodeGroups.ACCOMMODATION_TYPE, "HOTEL_ROOM"))
			.isEqualTo("호텔 객실");

		adminService.update(CommonCodeGroups.ACCOMMODATION_TYPE, "HOTEL_ROOM",
			new CommonCodeRequest.Update("호텔룸", null, null, null));

		assertThat(adminService.getAll(CommonCodeGroups.ACCOMMODATION_TYPE))
			.anyMatch(code -> code.code().equals("HOTEL_ROOM") && code.name().equals("호텔룸"));
		assertThat(commonCodeService.getLabel(CommonCodeGroups.ACCOMMODATION_TYPE, "HOTEL_ROOM"))
			.isEqualTo("호텔 객실");
	}

	@Test
	@DisplayName("코드를 비활성화해도 이미 적재된 조회 캐시는 즉시 무효화되지 않는다")
	void deactivateDoesNotInvalidateCachedSnapshot() {
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.ACCOMMODATION_TYPE, "CASTLE")).isTrue();

		adminService.update(CommonCodeGroups.ACCOMMODATION_TYPE, "CASTLE",
			new CommonCodeRequest.Update(null, null, null, false));

		assertThat(adminService.getAll(CommonCodeGroups.ACCOMMODATION_TYPE))
			.anyMatch(code -> code.code().equals("CASTLE") && !code.active());
		assertThat(commonCodeService.isValidCode(CommonCodeGroups.ACCOMMODATION_TYPE, "CASTLE")).isTrue();
	}

	@Test
	@DisplayName("관리자 조회는 비활성 코드도 포함한다")
	void adminListIncludesInactive() {
		adminService.update(CommonCodeGroups.AMENITY_TYPE, "BALCONY",
			new CommonCodeRequest.Update(null, null, null, false));

		assertThat(adminService.getAll(CommonCodeGroups.AMENITY_TYPE))
			.anyMatch(c -> c.code().equals("BALCONY") && !c.active());
	}

	@Test
	@DisplayName("중복 코드 생성·없는 그룹·없는 코드 수정은 예외")
	void invalidCases() {
		assertThatThrownBy(() -> adminService.create(CommonCodeGroups.AMENITY_TYPE,
			new CommonCodeRequest.Create("WIFI", "중복", null, 1, true)))
			.isInstanceOf(CommonCodeDuplicateException.class);

		assertThatThrownBy(() -> adminService.getAll("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		assertThatThrownBy(() -> adminService.update(CommonCodeGroups.AMENITY_TYPE, "NOPE",
			new CommonCodeRequest.Update("x", null, null, null)))
			.isInstanceOf(CommonCodeNotFoundException.class);
	}
}

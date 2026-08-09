package kr.kro.airbob.domain.commoncode.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeId;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeNotFoundException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeRepository;
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
	@Autowired private CommonCodeGroupRepository groupRepository;
	@Autowired private CommonCodeRepository commonCodeRepository;

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

	@Test
	@DisplayName("그룹 목록은 비활성을 포함해 그룹 코드 순으로 반환한다")
	void listGroupsInCodeOrder() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"ZZ_LIST_GROUP", "목록 그룹", null, false));

		List<CommonCodeGroupResponse> groups = adminService.getGroups();

		assertThat(groups)
			.extracting(CommonCodeGroupResponse::groupCode)
			.isSorted()
			.contains("AMENITY_TYPE", "ACCOMMODATION_TYPE", "ZZ_LIST_GROUP");
		assertThat(groups)
			.anyMatch(group -> group.groupCode().equals("ZZ_LIST_GROUP") && !group.active());
	}

	@Test
	@DisplayName("그룹 생성은 코드를 정규화하고 활성 기본값을 적용한다")
	void createGroup() {
		CommonCodeGroupResponse created = adminService.createGroup(
			new CommonCodeGroupRequest.Create(" payment_method ", "결제 수단", null, null));

		assertThat(created.groupCode()).isEqualTo("PAYMENT_METHOD");
		assertThat(created.groupName()).isEqualTo("결제 수단");
		assertThat(created.active()).isTrue();

		CommonCodeGroup saved = groupRepository.findById("PAYMENT_METHOD").orElseThrow();
		assertThat(saved.getGroupName()).isEqualTo("결제 수단");
		assertThat(saved.isActive()).isTrue();
	}

	@Test
	@DisplayName("이미 존재하는 그룹 코드는 생성할 수 없다")
	void rejectDuplicateGroup() {
		assertThatThrownBy(() -> adminService.createGroup(
			new CommonCodeGroupRequest.Create("amenity_type", "중복 그룹", null, true)))
			.isInstanceOf(CommonCodeGroupDuplicateException.class);
	}

	@Test
	@DisplayName("중복 그룹 INSERT는 기존 그룹을 덮어쓰지 않는다")
	void duplicateGroupInsertDoesNotOverwriteExistingGroup() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"INSERT_ONLY_GROUP", "최초 이름", "최초 설명", true));

		CommonCodeGroup duplicate = CommonCodeGroup.builder()
			.groupCode("INSERT_ONLY_GROUP")
			.groupName("덮어쓴 이름")
			.description("덮어쓴 설명")
			.active(false)
			.build();

		assertThatThrownBy(() -> groupRepository.insert(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);

		CommonCodeGroup saved = groupRepository.findById("INSERT_ONLY_GROUP").orElseThrow();
		assertThat(saved.getGroupName()).isEqualTo("최초 이름");
		assertThat(saved.getDescription()).isEqualTo("최초 설명");
		assertThat(saved.isActive()).isTrue();
	}

	@Test
	@DisplayName("중복 코드 INSERT는 기존 공통 코드를 덮어쓰지 않는다")
	void duplicateCodeInsertDoesNotOverwriteExistingCode() {
		CommonCode duplicate = CommonCode.builder()
			.groupCode(CommonCodeGroups.AMENITY_TYPE)
			.code("WIFI")
			.name("덮어쓴 이름")
			.description("덮어쓴 설명")
			.sortOrder(999)
			.active(false)
			.build();

		assertThatThrownBy(() -> commonCodeRepository.insert(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);

		CommonCode saved = commonCodeRepository.findById(
			new CommonCodeId(CommonCodeGroups.AMENITY_TYPE, "WIFI")).orElseThrow();
		assertThat(saved.getName()).isEqualTo("무선 인터넷");
		assertThat(saved.getDescription()).isNull();
		assertThat(saved.getSortOrder()).isEqualTo(1);
		assertThat(saved.isActive()).isTrue();
	}

	@Test
	@DisplayName("그룹 수정은 전달된 필드만 변경한다")
	void updateGroup() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"GROUP_UPDATE", "기존 이름", "기존 설명", true));

		CommonCodeGroupResponse updated = adminService.updateGroup(
			"group_update", new CommonCodeGroupRequest.Update("새 이름", null, false));

		assertThat(updated.groupCode()).isEqualTo("GROUP_UPDATE");
		assertThat(updated.groupName()).isEqualTo("새 이름");
		assertThat(updated.description()).isEqualTo("기존 설명");
		assertThat(updated.active()).isFalse();

		CommonCodeGroup saved = groupRepository.findById("GROUP_UPDATE").orElseThrow();
		assertThat(saved.getGroupName()).isEqualTo("새 이름");
		assertThat(saved.getDescription()).isEqualTo("기존 설명");
		assertThat(saved.isActive()).isFalse();
	}

	@Test
	@DisplayName("빈 설명으로 그룹 설명을 제거할 수 있다")
	void clearGroupDescription() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"GROUP_CLEAR_DESCRIPTION", "설명 제거 그룹", "기존 설명", true));

		CommonCodeGroupResponse updated = adminService.updateGroup(
			"GROUP_CLEAR_DESCRIPTION", new CommonCodeGroupRequest.Update(null, "", null));

		assertThat(updated.groupName()).isEqualTo("설명 제거 그룹");
		assertThat(updated.description()).isEmpty();
		assertThat(updated.active()).isTrue();

		CommonCodeGroup saved = groupRepository.findById("GROUP_CLEAR_DESCRIPTION").orElseThrow();
		assertThat(saved.getDescription()).isEmpty();
	}

	@Test
	@DisplayName("존재하지 않는 그룹은 수정할 수 없다")
	void rejectMissingGroupUpdate() {
		assertThatThrownBy(() -> adminService.updateGroup(
			"MISSING_GROUP", new CommonCodeGroupRequest.Update("새 이름", null, null)))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);
	}

	@Test
	@DisplayName("새 활성 그룹에 등록한 코드는 공개 조회에서 사용할 수 있다")
	void useNewGroupFromPublicCache() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"DELIVERY_METHOD", "배송 방식", null, true));
		adminService.create("DELIVERY_METHOD",
			new CommonCodeRequest.Create("QUICK", "퀵 배송", null, 1, true));

		assertThat(commonCodeService.getCodes("DELIVERY_METHOD"))
			.extracting(CommonCodeResponse::code)
			.containsExactly("QUICK");
	}

	@Test
	@DisplayName("비활성 그룹에 등록한 코드는 공개 조회에서 사용하지 않는다")
	void hideCodesOfInactiveGroup() {
		adminService.createGroup(new CommonCodeGroupRequest.Create(
			"INACTIVE_DYNAMIC_GROUP", "비활성 동적 그룹", null, false));
		adminService.create("INACTIVE_DYNAMIC_GROUP",
			new CommonCodeRequest.Create("HIDDEN", "숨김 코드", null, 1, true));

		assertThat(commonCodeService.getCodes("INACTIVE_DYNAMIC_GROUP")).isEmpty();
	}
}

package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.PolicyRequest;
import kr.kro.airbob.domain.accommodation.dto.PolicyResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedService;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.TimeZoneResolver;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, AccommodationCommandService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("숙소 정책 저장 후 호스트 조회 MySQL 계약")
class AccommodationPolicyIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_accommodation_policy");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private AccommodationCommandService commandService;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private OccupancyPolicyRepository policyRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private CommonCodeService commonCodeService;
	@MockitoBean private GeocodingService geocodingService;
	@MockitoBean private TimeZoneResolver timeZoneResolver;
	@MockitoBean private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@MockitoBean private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	@MockitoBean private AccommodationInventorySeedService inventorySeedService;

	@Test
	@DisplayName("정책이 없는 초안에 기본값을 저장하고 다시 수정해도 같은 정책 행을 사용한다")
	void createsMissingPolicyAndUpdatesItInPlace() {
		Fixture fixture = createAccommodation(null);
		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(null, null, null));
		long beforeCount = policyRepository.count();

		updatePolicy(fixture, 1, 0, 0);
		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(1, 0, 0));
		long policyId = policyId(fixture);

		updatePolicy(fixture, 6, 2, 3);
		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(6, 2, 3));
		assertThat(policyId(fixture)).isEqualTo(policyId);
		assertThat(policyRepository.count()).isEqualTo(beforeCount + 1);
	}

	@Test
	@DisplayName("일부 누락 정책을 보완하고 정책이 생략된 후속 PATCH는 기존 값을 보존한다")
	void completesPartialPolicyAndPreservesOmittedPolicy() {
		Fixture fixture = createAccommodation(OccupancyPolicy.builder()
			.maxOccupancy(4).petOccupancy(2).build());
		long policyId = policyId(fixture);
		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(4, null, 2));

		updatePolicy(fixture, 4, 0, 2);
		commandService.updateAccommodation(fixture.id(),
			AccommodationRequest.Update.builder().name("수정된 이름").build(), fixture.hostId());

		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(4, 0, 2));
		assertThat(policyId(fixture)).isEqualTo(policyId);
	}

	@Test
	@DisplayName("정책 수정 트랜잭션이 롤백되면 기존 정책이 다시 조회된다")
	void rollbackPreservesStoredPolicy() {
		Fixture fixture = createAccommodation(OccupancyPolicy.builder()
			.maxOccupancy(4).infantOccupancy(1).petOccupancy(0).build());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			updatePolicy(fixture, 6, 2, 3);
			accommodationRepository.flush();
			status.setRollbackOnly();
		});

		assertThat(readPolicy(fixture)).isEqualTo(new PolicyResponse.PolicyInfo(4, 1, 0));
	}

	private void updatePolicy(Fixture fixture, int max, int infant, int pet) {
		commandService.updateAccommodation(fixture.id(), AccommodationRequest.Update.builder()
			.occupancyPolicyInfo(new PolicyRequest.OccupancyPolicyInfo(max, infant, pet)).build(), fixture.hostId());
	}

	private PolicyResponse.PolicyInfo readPolicy(Fixture fixture) {
		return new TransactionTemplate(transactionManager).execute(status -> AccommodationResponse.HostDetail.from(
			accommodationRepository.findWithDetailsByIdAndHostId(fixture.id(), fixture.hostId()).orElseThrow(),
			List.of(), List.of(), null).policy());
	}

	private long policyId(Fixture fixture) {
		return new TransactionTemplate(transactionManager).execute(status -> accommodationRepository
			.findWithDetailsByIdAndHostId(fixture.id(), fixture.hostId()).orElseThrow().getOccupancyPolicy().getId());
	}

	private Fixture createAccommodation(OccupancyPolicy policy) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			Member host = memberRepository.save(Member.builder()
				.email(UUID.randomUUID() + "@example.test").nickname("policy-host").build());
			Accommodation accommodation = Accommodation.createAccommodation(host);
			accommodation.updateOccupancyPolicy(policy);
			Accommodation saved = accommodationRepository.save(accommodation);
			return new Fixture(saved.getId(), host.getId());
		});
	}

	private record Fixture(long id, long hostId) { }
}

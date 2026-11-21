package kr.kro.airbob.domain.accommodation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("숙소 예약용 경량 조회 테스트")
class AccommodationBookingProjectionRepositoryTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_accommodation_booking_projection");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired
	private AccommodationRepository accommodationRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Test
	@DisplayName("게시 숙소의 시간대만 예약용 projection으로 조회한다")
	void findsPublishedAccommodationTimeZoneOnly() {
		Member host = memberRepository.save(Member.builder()
			.email("booking-projection@test.com")
			.nickname("booking-projection")
			.build());
		Accommodation published = saveAccommodation(host, AccommodationStatus.PUBLISHED, "America/New_York");
		Accommodation draft = saveAccommodation(host, AccommodationStatus.DRAFT, "Asia/Seoul");

		assertThat(accommodationRepository.findBookingProjectionByIdAndStatus(
			published.getId(), AccommodationStatus.PUBLISHED))
			.hasValueSatisfying(projection ->
				assertThat(projection.timeZoneId()).isEqualTo("America/New_York"));
		assertThat(accommodationRepository.findBookingProjectionByIdAndStatus(
			draft.getId(), AccommodationStatus.PUBLISHED))
			.isEmpty();
	}

	private Accommodation saveAccommodation(
		Member host,
		AccommodationStatus status,
		String timeZoneId
	) {
		return accommodationRepository.save(Accommodation.builder()
			.member(host)
			.name("booking-projection-" + status)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(timeZoneId)
			.status(status)
			.build());
	}
}

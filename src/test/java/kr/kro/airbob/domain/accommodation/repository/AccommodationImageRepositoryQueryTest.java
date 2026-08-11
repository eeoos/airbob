package kr.kro.airbob.domain.accommodation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingConfig.class,
    QueryDslConfig.class,
    AccommodationImageRepositoryQueryTest.SqlCaptureConfig.class
})
@DisplayName("숙소 이미지 저장소 쿼리 테스트")
class AccommodationImageRepositoryQueryTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
        .withDatabaseName("airbobdb_accommodation_image_query");

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
    private AccommodationImageRepository accommodationImageRepository;

    @Autowired
    private AccommodationRepository accommodationRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CapturingStatementInspector sqlInspector;

    @Test
    @DisplayName("숙소 ID로 이미지를 ID 오름차순 조회하고 숙소 조인 없이 빈 결과도 반환한다")
    void findsImagesByAccommodationIdWithoutAccommodationJoin() {
        Member member = memberRepository.save(Member.builder()
            .email("accommodation-image-query@test.com")
            .nickname("accommodation-image-query")
            .build());
        Accommodation target = saveAccommodation(member, "target");
        Accommodation withoutImages = saveAccommodation(member, "without-images");
        AccommodationImage first = accommodationImageRepository.save(AccommodationImage.builder()
            .accommodation(target)
            .imageUrl("https://example.com/first.jpg")
            .build());
        AccommodationImage second = accommodationImageRepository.save(AccommodationImage.builder()
            .accommodation(target)
            .imageUrl("https://example.com/second.jpg")
            .build());
        accommodationImageRepository.flush();

        sqlInspector.clear();
        List<AccommodationImage> images =
            accommodationImageRepository.findByAccommodationIdOrderByIdAsc(target.getId());

        assertThat(images)
            .extracting(AccommodationImage::getId)
            .containsExactly(first.getId(), second.getId());
        assertThat(sqlInspector.singleSelect())
            .contains(".accommodation_id=?")
            .contains(" order by ")
            .doesNotContain(" join accommodation ");

        assertThat(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(withoutImages.getId()))
            .isEmpty();
    }

    private Accommodation saveAccommodation(Member member, String name) {
        return accommodationRepository.save(Accommodation.builder()
            .member(member)
            .name(name)
            .checkInTime(LocalTime.of(15, 0))
            .checkOutTime(LocalTime.of(11, 0))
            .status(AccommodationStatus.PUBLISHED)
            .build());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfig {

        @Bean
        CapturingStatementInspector capturingStatementInspector() {
            return new CapturingStatementInspector();
        }

        @Bean
        HibernatePropertiesCustomizer testStatementInspectorCustomizer(
            CapturingStatementInspector inspector
        ) {
            return (Map<String, Object> properties) -> properties.put(
                "hibernate.session_factory.statement_inspector", inspector);
        }
    }

    static class CapturingStatementInspector implements StatementInspector {

        private final List<String> statements = new ArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(normalize(sql));
            return sql;
        }

        void clear() {
            statements.clear();
        }

        String singleSelect() {
            List<String> selects = statements.stream()
                .filter(sql -> sql.startsWith("select "))
                .toList();
            assertThat(selects).hasSize(1);
            return selects.getFirst();
        }

        private String normalize(String sql) {
            return sql.replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        }
    }
}

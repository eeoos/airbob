package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingConfig.class,
    QueryDslConfig.class,
    ReservationRepositoryQueryTest.SqlCaptureConfig.class
})
@DisplayName("예약 QueryDSL 저장소 테스트")
class ReservationRepositoryQueryTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
        .withDatabaseName("airbobdb_reservation_query");

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
    private ReservationRepository reservationRepository;

    @Autowired
    private AccommodationRepository accommodationRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CapturingStatementInspector sqlInspector;

    @Test
    @DisplayName("ID와 UUID 조회는 대상 숙소의 미래 확정 예약만 동일하게 반환하고 ID 조회는 숙소를 조인하지 않는다")
    void idAndUidQueriesReturnSameEligibleReservationsWithoutIdPathJoin() {
        Member member = memberRepository.save(Member.builder()
            .email("reservation-query@test.com")
            .nickname("reservation-query")
            .build());
        Accommodation target = saveAccommodation(member, "target");
        Accommodation other = saveAccommodation(member, "other");
        LocalDateTime now = LocalDateTime.now();

        Reservation expected = saveReservation(
            target, member, ReservationStatus.CONFIRMED,
            now.plusDays(1), now.plusDays(3));
        saveReservation(
            target, member, ReservationStatus.CONFIRMED,
            now.minusDays(3), now.minusDays(1));
        saveReservation(
            target, member, ReservationStatus.PAYMENT_PENDING,
            now.plusDays(1), now.plusDays(3));
        saveReservation(
            other, member, ReservationStatus.CONFIRMED,
            now.plusDays(1), now.plusDays(3));
        reservationRepository.flush();

        sqlInspector.clear();
        List<Reservation> byId =
            reservationRepository.findFutureCompletedReservationsByAccommodationId(target.getId());

        assertThat(byId)
            .extracting(Reservation::getId)
            .containsExactly(expected.getId());
        assertThat(sqlInspector.singleSelect())
            .contains(".accommodation_id=?")
            .doesNotContain(" join accommodation ");

        List<Reservation> byUid =
            reservationRepository.findFutureCompletedReservations(target.getAccommodationUid());

        assertThat(byUid)
            .extracting(Reservation::getId)
            .containsExactly(expected.getId());
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

    private Reservation saveReservation(
        Accommodation accommodation,
        Member guest,
        ReservationStatus status,
        LocalDateTime checkIn,
        LocalDateTime checkOut
    ) {
        return reservationRepository.save(Reservation.builder()
            .accommodation(accommodation)
            .guest(guest)
            .checkIn(checkIn)
            .checkOut(checkOut)
            .guestCount(1)
            .totalPrice(100_000L)
            .currency("KRW")
            .status(status)
            .expiresAt(LocalDateTime.now().plusMinutes(15))
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

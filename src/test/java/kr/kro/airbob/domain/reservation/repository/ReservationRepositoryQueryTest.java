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
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
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
    @DisplayName("ID 조회는 지정 기간과 겹치는 확정 예약만 날짜 구간으로 반환한다")
    void idQueryReturnsConfirmedReservationRangesOverlappingWindow() {
        Member member = memberRepository.save(Member.builder()
            .email("reservation-window-query@test.com")
            .nickname("reservation-window-query")
            .build());
        Accommodation target = saveAccommodation(member, "window-target");
        Accommodation other = saveAccommodation(member, "window-other");
        LocalDateTime windowStart = LocalDateTime.of(2030, 1, 1, 0, 0);
        LocalDateTime windowEndExclusive = LocalDateTime.of(2030, 4, 1, 0, 0);

        ReservationDateRange overlapsStart = new ReservationDateRange(
            windowStart.minusDays(2), windowStart.plusDays(1));
        ReservationDateRange inside = new ReservationDateRange(
            windowStart.plusDays(10), windowStart.plusDays(12));
        ReservationDateRange overlapsEnd = new ReservationDateRange(
            windowEndExclusive.minusDays(1), windowEndExclusive.plusDays(2));

        saveReservation(target, member, ReservationStatus.CONFIRMED,
            overlapsStart.checkIn(), overlapsStart.checkOut());
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            windowStart.minusDays(2), windowStart);
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            inside.checkIn(), inside.checkOut());
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            overlapsEnd.checkIn(), overlapsEnd.checkOut());
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            windowEndExclusive, windowEndExclusive.plusDays(2));
        saveReservation(target, member, ReservationStatus.PAYMENT_PENDING,
            inside.checkIn(), inside.checkOut());
        saveReservation(other, member, ReservationStatus.CONFIRMED,
            inside.checkIn(), inside.checkOut());
        reservationRepository.flush();

        sqlInspector.clear();
        List<ReservationDateRange> result = reservationRepository
            .findConfirmedReservationRangesByAccommodationId(
                target.getId(), windowStart, windowEndExclusive);
        String sql = sqlInspector.singleSelect();

		assertThat(result).containsExactlyInAnyOrder(overlapsStart, inside, overlapsEnd);
        assertDateRangeProjection(sql);
        assertThat(sql)
            .contains(".accommodation_id=?")
            .contains(".check_in<?")
            .contains(".check_out>?")
			.doesNotContain(" join accommodation ")
			.doesNotContain(" order by ");
    }

    @Test
    @DisplayName("UUID 조회는 날짜 두 컬럼만 projection하고 대상 숙소의 모든 미래 확정 예약을 반환한다")
    void uidQueryProjectsAllFutureConfirmedReservationRanges() {
        Member member = memberRepository.save(Member.builder()
            .email("reservation-query@test.com")
            .nickname("reservation-query")
            .build());
        Accommodation target = saveAccommodation(member, "target");
        Accommodation other = saveAccommodation(member, "other");
        LocalDateTime base = LocalDateTime.now().withNano(0);

        ReservationDateRange first = new ReservationDateRange(
            base.plusDays(1), base.plusDays(3));
        ReservationDateRange second = new ReservationDateRange(
            base.plusDays(4), base.plusDays(6));

        saveReservation(target, member, ReservationStatus.CONFIRMED,
            first.checkIn(), first.checkOut());
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            second.checkIn(), second.checkOut());
        saveReservation(
            target, member, ReservationStatus.CONFIRMED,
            base.minusDays(3), base.minusDays(1));
        saveReservation(
            target, member, ReservationStatus.PAYMENT_PENDING,
            base.plusDays(1), base.plusDays(3));
        saveReservation(
            other, member, ReservationStatus.CONFIRMED,
            base.plusDays(1), base.plusDays(3));
        reservationRepository.flush();

        sqlInspector.clear();
        List<ReservationDateRange> byUid = reservationRepository
            .findFutureConfirmedReservationRangesByAccommodationUid(
                target.getAccommodationUid());
        String uidSql = sqlInspector.singleSelect();

        assertThat(byUid).containsExactlyInAnyOrder(first, second);
        assertDateRangeProjection(uidSql);
        assertThat(uidSql)
            .contains(".check_out>=?")
            .doesNotContain(" order by ");
    }

    private void assertDateRangeProjection(String sql) {
        int fromIndex = sql.indexOf(" from ");
        assertThat(fromIndex).isPositive();

        List<String> selectedColumns = List.of(
            sql.substring("select ".length(), fromIndex).split(","));

        assertThat(selectedColumns).hasSize(2);
        assertThat(selectedColumns.get(0)).contains(".check_in");
        assertThat(selectedColumns.get(1)).contains(".check_out");
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

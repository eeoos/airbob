# Accommodation Detail ID Query Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 숙소 상세 조회의 이미지와 예약 불가 날짜 조회가 이미 확보한 `accommodationId`를 사용하도록 바꿔 불필요한 `accommodation` 조인을 제거한다.

**Architecture:** 예약 저장소에는 상세 조회용 명시적 ID 메서드를 추가하고, Elasticsearch 색인 경로가 사용하는 기존 UUID 메서드는 유지한다. 두 예약 메서드는 숙소 식별 조건만 다르게 전달하고 `CONFIRMED` 및 미래 체크아웃 조건은 하나의 private QueryDSL 조회로 공유한다. 공개·호스트 상세의 이미지는 기존 ID 기반 Spring Data 메서드로 통일한다.

**Tech Stack:** Java 21, Spring Boot 3.5.8, Spring Data JPA, QueryDSL, MySQL 8.0, JUnit 5, Mockito, AssertJ, Testcontainers

## Global Constraints

- 이번 변경은 SQL 실행 횟수를 줄이지 않으며 이미지·예약 조회의 숙소 조인만 제거한다.
- 공개 API 요청·응답, Kafka 이벤트, Elasticsearch 문서 스키마를 변경하지 않는다.
- 예약 상태 조건은 `CONFIRMED`, 시간 조건은 `checkOut >= LocalDateTime.now()`를 유지한다.
- 예약 저장소 조회에는 새로운 정렬을 추가하지 않는다.
- `AccommodationDocumentBuilder`와 `AccommodationIndexUpdater`는 기존 UUID 예약 조회를 계속 사용한다.
- 이미지 순서, 빈 목록 동작, 예약 날짜의 `[checkIn, checkOut)` 펼침·중복 제거·오름차순 정렬을 유지한다.
- 예약 projection, 복합 인덱스, `CANCELLATION_FAILED` 상태 처리, 이벤트 payload 변경은 범위에서 제외한다.
- 모든 동작 변경은 실패 테스트를 먼저 확인하고 최소 구현으로 통과시킨다.

---

## File Map

### Create

- `src/test/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryQueryTest.java`: ID/UUID 예약 조회의 동등한 필터 동작과 ID SQL의 무조인 형태 검증

### Modify

- `src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryCustom.java`: 상세용 ID 조회 계약 추가
- `src/main/java/kr/kro/airbob/domain/reservation/repository/impl/ReservationRepositoryImpl.java`: ID·UUID 식별 조건과 공통 상태·시간 조건 분리
- `src/test/java/kr/kro/airbob/domain/accommodation/service/AccommodationServiceTest.java`: 공개·호스트 상세가 ID 기반 이미지·예약 조회를 사용하는지 검증
- `src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationService.java`: 공개·호스트 상세 helper를 ID 기반으로 전환
- `src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationImageRepository.java`: 사용되지 않는 UUID 이미지 조회 제거

### Preserve Unchanged

- `src/main/java/kr/kro/airbob/search/service/AccommodationDocumentBuilder.java`: UUID 예약 범위 조회 유지
- `src/main/java/kr/kro/airbob/search/service/AccommodationIndexUpdater.java`: UUID 예약 날짜 조회 유지
- `src/main/java/kr/kro/airbob/search/event/AccommodationIndexingEvents.java`: `ReservationChangedEvent`의 UUID 이벤트 계약 유지

---

### Task 1: 예약 저장소에 ID 기반 미래 확정 예약 조회 추가

**Files:**
- Create: `src/test/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryQueryTest.java`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryCustom.java:21`
- Modify: `src/main/java/kr/kro/airbob/domain/reservation/repository/impl/ReservationRepositoryImpl.java:80-90`
- Verify unchanged: `src/main/java/kr/kro/airbob/search/service/AccommodationDocumentBuilder.java:85-94`
- Verify unchanged: `src/main/java/kr/kro/airbob/search/service/AccommodationIndexUpdater.java:69-79`

**Interfaces:**
- Consumes: `Long accommodationId`, `UUID accommodationUid`, `QReservation.reservation`
- Produces: `ReservationRepositoryCustom.findFutureCompletedReservationsByAccommodationId(Long): List<Reservation>`
- Preserves: `ReservationRepositoryCustom.findFutureCompletedReservations(UUID): List<Reservation>`

- [ ] **Step 1: Write the failing repository integration test**

Create `ReservationRepositoryQueryTest.java`. The fixture uses normal JPA repositories, and a test-only `StatementInspector` captures only the QueryDSL select executed after `clear()`.

```java
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
```

- [ ] **Step 2: Run the new repository test and verify RED**

```bash
./gradlew test --tests 'kr.kro.airbob.domain.reservation.repository.ReservationRepositoryQueryTest'
```

Expected: Java compilation fails because `findFutureCompletedReservationsByAccommodationId(Long)` does not exist.

- [ ] **Step 3: Add the explicit ID repository contract**

Add this method next to the retained UUID method in `ReservationRepositoryCustom`:

```java
List<Reservation> findFutureCompletedReservationsByAccommodationId(Long accommodationId);

List<Reservation> findFutureCompletedReservations(UUID accommodationUid);
```

- [ ] **Step 4: Implement ID and UUID entry points over one shared QueryDSL query**

Replace the current UUID-only implementation in `ReservationRepositoryImpl` with:

```java
@Override
public List<Reservation> findFutureCompletedReservationsByAccommodationId(Long accommodationId) {
    return findFutureCompletedReservationsByCondition(
        reservation.accommodation.id.eq(accommodationId));
}

@Override
public List<Reservation> findFutureCompletedReservations(UUID accommodationUid) {
    return findFutureCompletedReservationsByCondition(
        reservation.accommodation.accommodationUid.eq(accommodationUid));
}

private List<Reservation> findFutureCompletedReservationsByCondition(
    BooleanExpression accommodationCondition
) {
    return queryFactory
        .selectFrom(reservation)
        .where(
            accommodationCondition,
            reservation.status.eq(ReservationStatus.CONFIRMED),
            reservation.checkOut.goe(LocalDateTime.now())
        )
        .fetch();
}
```

Do not add an `orderBy`, projection, index, or extra status to this query.

- [ ] **Step 5: Run the repository test and verify GREEN**

```bash
./gradlew test --tests 'kr.kro.airbob.domain.reservation.repository.ReservationRepositoryQueryTest'
```

Expected: the test passes; the ID-generated SQL contains `reservation.accommodation_id = ?` and no `join accommodation`, while ID and UUID paths return the same eligible reservation.

- [ ] **Step 6: Verify the UUID search callers still compile unchanged**

```bash
./gradlew compileJava
rg -n 'findFutureCompletedReservations\(' \
  src/main/java/kr/kro/airbob/search/service/AccommodationDocumentBuilder.java \
  src/main/java/kr/kro/airbob/search/service/AccommodationIndexUpdater.java
```

Expected: compilation passes and both search classes still call `findFutureCompletedReservations(UUID)`.

- [ ] **Step 7: Commit the repository slice**

```bash
git add \
  src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryCustom.java \
  src/main/java/kr/kro/airbob/domain/reservation/repository/impl/ReservationRepositoryImpl.java \
  src/test/java/kr/kro/airbob/domain/reservation/repository/ReservationRepositoryQueryTest.java
git commit -m "refactor: 예약 상세 조회에 숙소 ID 사용"
```

---

### Task 2: 숙소 공개·호스트 상세의 이미지와 예약 조회를 ID로 전환

**Files:**
- Modify: `src/test/java/kr/kro/airbob/domain/accommodation/service/AccommodationServiceTest.java:168-210`
- Modify: `src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationService.java:159-180,285-300,421-448`
- Modify: `src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationImageRepository.java:1-19`

**Interfaces:**
- Consumes: `Accommodation#getId()`, `AccommodationImageRepository.findByAccommodationIdOrderByIdAsc(Long)`, `ReservationRepository.findFutureCompletedReservationsByAccommodationId(Long)`
- Produces: 변경 없는 `AccommodationResponse.DetailInfo`와 `AccommodationResponse.HostDetail`
- Removes: `AccommodationImageRepository.findByAccommodation_AccommodationUidOrderByIdAsc(UUID)`

- [ ] **Step 1: Write failing service routing tests**

Add these tests to `AccommodationServiceTest`:

```java
@Test
@DisplayName("숙소 상세 조회는 이미지와 미래 예약을 숙소 ID로 조회한다")
void accommodationDetailUsesAccommodationIdForImagesAndReservations() {
    givenPublishedAccommodation(1L);

    accommodationService.findAccommodation(1L, null);

    verify(accommodationImageRepository)
        .findByAccommodationIdOrderByIdAsc(1L);
    verify(reservationRepository)
        .findFutureCompletedReservationsByAccommodationId(1L);
}

@Test
@DisplayName("호스트 숙소 상세 조회는 이미지를 숙소 ID로 조회한다")
void hostAccommodationDetailUsesAccommodationIdForImages() {
    Accommodation accommodation = mock(Accommodation.class);
    when(accommodationRepository.findWithDetailsByIdAndHostId(1L, 7L))
        .thenReturn(Optional.of(accommodation));
    when(accommodation.getId()).thenReturn(1L);
    when(accommodationAmenityRepository.findAllByAccommodationId(1L))
        .thenReturn(List.of());
    when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
        .thenReturn(List.of());
    when(reviewSummaryRepository.findByAccommodationId(1L))
        .thenReturn(Optional.empty());

    accommodationService.findHostAccommodationDetail(1L, 7L);

    verify(accommodationImageRepository)
        .findByAccommodationIdOrderByIdAsc(1L);
}
```

Change `givenPublishedAccommodation` so it no longer creates or stubs a UUID:

```java
private void givenPublishedAccommodation(Long accommodationId) {
    Accommodation accommodation = mock(Accommodation.class);

    when(accommodationRepository.findWithDetailsByAccommodationIdAndStatus(
        accommodationId, AccommodationStatus.PUBLISHED))
        .thenReturn(Optional.of(accommodation));
    when(accommodation.getId()).thenReturn(accommodationId);
    when(accommodationAmenityRepository.findAllByAccommodationId(accommodationId))
        .thenReturn(List.of());
    when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodationId))
        .thenReturn(List.of());
    when(reviewSummaryRepository.findByAccommodationId(accommodationId))
        .thenReturn(Optional.empty());
    when(reservationRepository.findFutureCompletedReservationsByAccommodationId(accommodationId))
        .thenReturn(List.of());
}
```

Remove the now-unused `java.util.UUID` import from the test.

- [ ] **Step 2: Run the service test and verify RED**

```bash
./gradlew test --tests 'kr.kro.airbob.domain.accommodation.service.AccommodationServiceTest'
```

Expected: Mockito reports that the ID repository methods were not invoked, because the service still routes image and reservation lookups through UUID.

- [ ] **Step 3: Switch public and host detail helpers to the route accommodation ID**

In `AccommodationService#findAccommodation`, change the two calls to:

```java
List<ImageResponse.ImageInfo> imageInfos = getImageUrls(accommodationId);
List<LocalDate> unavailableDates = getUnavailableDates(accommodationId);
```

In `AccommodationService#findHostAccommodationDetail`, change the image call to:

```java
List<ImageResponse.ImageInfo> imageInfos = getImageUrls(accommodationId);
```

Replace the two helpers with:

```java
private List<ImageResponse.ImageInfo> getImageUrls(Long accommodationId) {
    return accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodationId)
        .stream()
        .map(ImageResponse.ImageInfo::from)
        .toList();
}

private List<LocalDate> getUnavailableDates(Long accommodationId) {
    List<Reservation> futureReservations =
        reservationRepository.findFutureCompletedReservationsByAccommodationId(accommodationId);

    return futureReservations.stream()
        .flatMap(reservation -> {
            LocalDate checkIn = reservation.getCheckIn().toLocalDate();
            LocalDate checkOut = reservation.getCheckOut().toLocalDate();
            return checkIn.datesUntil(checkOut);
        })
        .distinct()
        .sorted()
        .toList();
}
```

Remove the now-unused `java.util.UUID` import from `AccommodationService`.

- [ ] **Step 4: Remove the unused UUID image repository method**

Leave `AccommodationImageRepository` as:

```java
package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.image.entity.AccommodationImage;

public interface AccommodationImageRepository extends JpaRepository<AccommodationImage, Long> {

    long countByAccommodationId(Long accommodationId);

    List<AccommodationImage> findByAccommodationIdOrderByIdAsc(Long accommodationId);
}
```

This also removes the already-unused `Query` and `Param` imports.

- [ ] **Step 5: Run service and repository tests and verify GREEN**

```bash
./gradlew test \
  --tests 'kr.kro.airbob.domain.accommodation.service.AccommodationServiceTest' \
  --tests 'kr.kro.airbob.domain.reservation.repository.ReservationRepositoryQueryTest'
```

Expected: both test classes pass; existing anonymous/authenticated wishlist assertions remain unchanged.

- [ ] **Step 6: Verify call-site boundaries and contracts**

```bash
rg -n 'findByAccommodation_AccommodationUidOrderByIdAsc' src/main src/test
rg -n 'findFutureCompletedReservationsByAccommodationId' src/main src/test
rg -n 'findFutureCompletedReservations\(' \
  src/main/java/kr/kro/airbob/search/service/AccommodationDocumentBuilder.java \
  src/main/java/kr/kro/airbob/search/service/AccommodationIndexUpdater.java
```

Expected:

- the removed UUID image method has no matches;
- the ID reservation method appears only in the custom repository contract/implementation, accommodation detail service, and their tests;
- both search classes still call the retained UUID reservation method.

- [ ] **Step 7: Run final compilation and whitespace checks**

```bash
./gradlew compileJava
git diff --check
```

Expected: QueryDSL compilation succeeds without entity/Q-class changes, and `git diff --check` prints nothing.

- [ ] **Step 8: Commit the service slice**

```bash
git add \
  src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationService.java \
  src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationImageRepository.java \
  src/test/java/kr/kro/airbob/domain/accommodation/service/AccommodationServiceTest.java
git commit -m "refactor: 숙소 상세 연관 조회에 ID 사용"
```

---

## Final Verification

- [ ] Run the two focused test classes together.
- [ ] Run `./gradlew compileJava`.
- [ ] Confirm the ID reservation SQL has no `join accommodation`.
- [ ] Confirm `AccommodationDocumentBuilder` and `AccommodationIndexUpdater` still call the UUID repository method.
- [ ] Confirm no API DTO, Kafka event, Elasticsearch document, migration, index, or reservation status file changed.
- [ ] Run `git status --short` and verify only intended implementation-plan bookkeeping remains.

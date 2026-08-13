package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.data.domain.PageRequest;
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
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import jakarta.persistence.EntityManager;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ClockConfig.class,
    JpaAuditingConfig.class,
    QueryDslConfig.class,
    ReservationRepositoryQueryTest.SqlCaptureConfig.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
	private PaymentRepository paymentRepository;

    @Autowired
    private CapturingStatementInspector sqlInspector;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("만료 시각이 지난 결제 대기 예약은 충돌로 판단하지 않는다")
	void expiredPendingReservationDoesNotConflict() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-conflict@test.com")
			.nickname("reservation-conflict")
			.build());
		Accommodation accommodation = saveAccommodation(member, "conflict-target");
		Instant now = Instant.parse("2030-01-01T00:00:00Z");
		Instant requestedCheckInAt = Instant.parse("2030-02-01T06:00:00Z");
		Instant requestedCheckOutAt = Instant.parse("2030-02-03T02:00:00Z");

		Reservation expired = saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3),
			requestedCheckInAt, requestedCheckOutAt, now);
		reservationRepository.flush();

		assertThat(reservationRepository.existsConflictingReservation(
			accommodation.getId(), LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3), now
		)).isFalse();

		Reservation active = saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3),
			requestedCheckInAt, requestedCheckOutAt, now.plus(1, ChronoUnit.MICROS));
		reservationRepository.flush();

		assertThat(reservationRepository.existsConflictingReservation(
			accommodation.getId(), LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3), now
		)).isTrue();
		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			member.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 10)
		).getContent())
			.contains(active)
			.doesNotContain(expired);

		Accommodation processingAccommodation = saveAccommodation(member, "processing-conflict-target");
		Reservation processing = saveReservation(
			processingAccommodation, member, ReservationStatus.PAYMENT_PROCESSING,
			LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 3),
			Instant.parse("2030-03-01T06:00:00Z"), Instant.parse("2030-03-03T02:00:00Z"),
			now.minusSeconds(1));
		reservationRepository.flush();

		assertThat(reservationRepository.existsConflictingReservation(
			processingAccommodation.getId(), LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 3), now
		)).isTrue();
		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			member.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 10)
		).getContent()).contains(processing);
	}

	@Test
	@DisplayName("체크인 시각이나 시간대가 바뀌어도 같은 숙박 날짜는 충돌한다")
	void sameStayDatesConflictEvenWhenInstantsDoNotOverlap() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-date-inventory@test.com")
			.nickname("reservation-date-inventory")
			.build());
		Accommodation accommodation = saveAccommodation(member, "date-inventory-target");
		LocalDate checkIn = LocalDate.of(2030, 2, 1);
		LocalDate checkOut = LocalDate.of(2030, 2, 3);
		Instant now = Instant.parse("2030-01-01T00:00:00Z");
		saveReservation(
			accommodation,
			member,
			ReservationStatus.CONFIRMED,
			checkIn,
			checkOut,
			Instant.parse("2030-02-01T20:00:00Z"),
			Instant.parse("2030-02-02T02:00:00Z"),
			now.minusSeconds(1)
		);
		reservationRepository.flush();

		assertThat(reservationRepository.existsConflictingReservation(
			accommodation.getId(), checkIn, checkOut, now
		)).isTrue();
	}

	@Test
	@DisplayName("확정·취소 처리 중·취소 실패 예약은 모두 재고 충돌로 판단한다")
	void everyActiveReservationStatusConflicts() {
		Member member = memberRepository.save(Member.builder()
			.email("active-reservation-status@test.com")
			.nickname("active-reservation-status")
			.build());
		Accommodation accommodation = saveAccommodation(member, "active-status-target");
		LocalDate checkIn = LocalDate.of(2030, 2, 1);
		LocalDate checkOut = LocalDate.of(2030, 2, 3);
		Instant now = Instant.parse("2030-01-01T00:00:00Z");

		for (ReservationStatus status : List.of(
			ReservationStatus.CONFIRMED,
			ReservationStatus.CANCELLATION_PENDING,
			ReservationStatus.CANCELLATION_FAILED
		)) {
			Reservation reservation = saveReservation(
				accommodation, member, status, checkIn, checkOut);
			reservationRepository.flush();

			assertThat(reservationRepository.existsConflictingReservation(
				accommodation.getId(), checkIn, checkOut, now))
				.as("status=%s", status)
				.isTrue();

			reservationRepository.delete(reservation);
			reservationRepository.flush();
		}
	}

	@Test
	@DisplayName("체크아웃 시각과 현재 시각이 같으면 과거 예약이고 예정 예약이 아니다")
	void exactCheckoutIsPastAndNotUpcoming() {
		Member host = memberRepository.save(Member.builder()
			.email("reservation-boundary-host@test.com")
			.nickname("reservation-boundary-host")
			.build());
		Member guest = memberRepository.save(Member.builder()
			.email("reservation-boundary-guest@test.com")
			.nickname("reservation-boundary-guest")
			.build());
		Accommodation accommodation = saveAccommodation(host, "boundary-target");
		Instant now = Instant.parse("2030-02-03T02:00:00Z");
		Reservation reservation = saveReservation(
			accommodation, guest, ReservationStatus.CONFIRMED,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3),
			Instant.parse("2030-02-01T06:00:00Z"), now, now.minusSeconds(1)
		);
		reservationRepository.flush();

		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			guest.getId(), null, null, ReservationFilterType.PAST, now, PageRequest.of(0, 10)
		).getContent()).containsExactly(reservation);
		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			guest.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 10)
		).getContent()).isEmpty();
		assertThat(reservationRepository.findHostReservationsByHostIdWithCursor(
			host.getId(), null, null, ReservationFilterType.PAST, now, PageRequest.of(0, 10)
		).getContent()).containsExactly(reservation);
		assertThat(reservationRepository.findHostReservationsByHostIdWithCursor(
			host.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 10)
		).getContent()).isEmpty();
		assertThat(reservationRepository.existsPastCompletedReservationByGuest(
			accommodation.getId(), guest.getId(), now
		)).isTrue();
	}

	@Test
	@DisplayName("리뷰 자격은 체크아웃한 확정·취소 실패 예약에만 부여한다")
	void reviewEligibilityExcludesCancellationPending() {
		Member host = memberRepository.save(Member.builder()
			.email("review-status-host@test.com")
			.nickname("review-status-host")
			.build());
		Member guest = memberRepository.save(Member.builder()
			.email("review-status-guest@test.com")
			.nickname("review-status-guest")
			.build());
		Instant now = Instant.parse("2030-02-03T02:00:00Z");

		for (ReservationStatus status : ReservationStatus.values()) {
			Accommodation accommodation = saveAccommodation(host, "review-status-" + status.name());
			saveReservation(
				accommodation, guest, status,
				LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3),
				Instant.parse("2030-02-01T06:00:00Z"), now, now.minusSeconds(1));
			reservationRepository.flush();

			assertThat(reservationRepository.existsPastCompletedReservationByGuest(
				accommodation.getId(), guest.getId(), now))
				.as("status=%s", status)
				.isEqualTo(status == ReservationStatus.CONFIRMED
					|| status == ReservationStatus.CANCELLATION_FAILED);
		}
	}

	@Test
	@DisplayName("과거·예정 목록은 활성 상태를 포함하고 게스트 취소 목록은 배치 전 만료 예약도 포함한다")
	void reservationFiltersUseActiveAndTerminalStatusSets() {
		Member host = memberRepository.save(Member.builder()
			.email("filter-status-host@test.com")
			.nickname("filter-status-host")
			.build());
		Member guest = memberRepository.save(Member.builder()
			.email("filter-status-guest@test.com")
			.nickname("filter-status-guest")
			.build());
		Accommodation accommodation = saveAccommodation(host, "filter-status-target");
		Instant now = Instant.parse("2030-02-03T02:00:00Z");
		List<Reservation> pastActive = new ArrayList<>();
		List<Reservation> upcomingActive = new ArrayList<>();

		for (ReservationStatus status : List.of(
			ReservationStatus.CONFIRMED,
			ReservationStatus.CANCELLATION_PENDING,
			ReservationStatus.CANCELLATION_FAILED
		)) {
			pastActive.add(saveReservation(
				accommodation, guest, status,
				LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3),
				Instant.parse("2030-02-01T06:00:00Z"), now, now.minusSeconds(1)));
			upcomingActive.add(saveReservation(
				accommodation, guest, status,
				LocalDate.of(2030, 2, 4), LocalDate.of(2030, 2, 5),
				Instant.parse("2030-02-04T06:00:00Z"),
				Instant.parse("2030-02-05T02:00:00Z"), now.minusSeconds(1)));
		}
		Reservation cancelled = saveReservation(
			accommodation, guest, ReservationStatus.CANCELLED,
			LocalDate.of(2030, 2, 4), LocalDate.of(2030, 2, 5));
		Reservation expired = saveReservation(
			accommodation, guest, ReservationStatus.EXPIRED,
			LocalDate.of(2030, 2, 5), LocalDate.of(2030, 2, 6));
		Reservation expiredPending = saveReservation(
			accommodation, guest, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 6), LocalDate.of(2030, 2, 7),
			Instant.parse("2030-02-06T06:00:00Z"),
			Instant.parse("2030-02-07T02:00:00Z"), now);
		Reservation processing = saveReservation(
			accommodation, guest, ReservationStatus.PAYMENT_PROCESSING,
			LocalDate.of(2030, 2, 7), LocalDate.of(2030, 2, 8),
			Instant.parse("2030-02-07T06:00:00Z"),
			Instant.parse("2030-02-08T02:00:00Z"), now.minusSeconds(1));
		reservationRepository.flush();
		List<Reservation> guestUpcoming = new ArrayList<>(upcomingActive);
		guestUpcoming.add(processing);

		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			guest.getId(), null, null, ReservationFilterType.PAST, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrderElementsOf(pastActive);
		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			guest.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrderElementsOf(guestUpcoming);
		assertThat(reservationRepository.findMyReservationsByGuestIdWithCursor(
			guest.getId(), null, null, ReservationFilterType.CANCELLED, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrder(cancelled, expired, expiredPending);

		assertThat(reservationRepository.findHostReservationsByHostIdWithCursor(
			host.getId(), null, null, ReservationFilterType.PAST, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrderElementsOf(pastActive);
		assertThat(reservationRepository.findHostReservationsByHostIdWithCursor(
			host.getId(), null, null, ReservationFilterType.UPCOMING, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrderElementsOf(upcomingActive);
		assertThat(reservationRepository.findHostReservationsByHostIdWithCursor(
			host.getId(), null, null, ReservationFilterType.CANCELLED, now, PageRequest.of(0, 20)
		).getContent()).containsExactlyInAnyOrder(cancelled, expired);
	}

	@Test
	@DisplayName("만료 경계 이전과 정확한 경계의 결제 대기 예약만 조회한다")
	void findsExpiredPendingReservationsAtOrBeforeCutoff() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-expiry-query@test.com")
			.nickname("reservation-expiry-query")
			.build());
		Accommodation accommodation = saveAccommodation(member, "expiry-target");
		Instant cutoff = Instant.parse("2030-01-01T00:00:00.123456Z");
		Reservation before = saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 2),
			Instant.parse("2030-02-01T06:00:00Z"), Instant.parse("2030-02-02T02:00:00Z"),
			cutoff.minus(1, ChronoUnit.MICROS));
		Reservation exact = saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 2), LocalDate.of(2030, 2, 3),
			Instant.parse("2030-02-02T06:00:00Z"), Instant.parse("2030-02-03T02:00:00Z"), cutoff);
		saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),
			Instant.parse("2030-02-03T06:00:00Z"), Instant.parse("2030-02-04T02:00:00Z"),
			cutoff.plus(1, ChronoUnit.MICROS));
		reservationRepository.flush();

		assertThat(reservationRepository.findAllByStatusAndExpiresAtLessThanEqual(
			ReservationStatus.PAYMENT_PENDING, cutoff
		)).containsExactlyInAnyOrder(before, exact);
	}

	@Test
	@DisplayName("예약의 절대 시각은 MySQL 왕복 후에도 마이크로초 정밀도로 유지된다")
	void instantColumnsRoundTripWithMicrosecondPrecision() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-instant-roundtrip@test.com")
			.nickname("reservation-instant-roundtrip")
			.build());
		Accommodation accommodation = saveAccommodation(member, "instant-roundtrip-target");
		Instant checkInAt = Instant.parse("2030-02-01T06:00:00.123456Z");
		Instant checkOutAt = Instant.parse("2030-02-03T02:00:00.234567Z");
		Instant expiresAt = Instant.parse("2030-01-01T00:15:00.345678Z");
		Reservation saved = saveReservation(accommodation, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 3), checkInAt, checkOutAt, expiresAt);
		reservationRepository.flush();
		entityManager.clear();

		Reservation reloaded = reservationRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getCheckInAt()).isEqualTo(checkInAt);
		assertThat(reloaded.getCheckOutAt()).isEqualTo(checkOutAt);
		assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
	}

	@Test
	@DisplayName("상태 변경용 예약 조회는 비관적 쓰기 잠금을 건다")
	void lockedLookupUsesForUpdate() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-lock-query@test.com")
			.nickname("reservation-lock-query")
			.build());
		Accommodation accommodation = saveAccommodation(member, "lock-target");
		Reservation reservation = saveReservation(
			accommodation,
			member,
			ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 1),
			LocalDate.of(2030, 2, 2)
		);
		reservationRepository.flush();
		sqlInspector.clear();

		assertThat(reservationRepository.findByReservationUidWithLock(
			reservation.getReservationUid())).contains(reservation);

		assertThat(sqlInspector.singleSelect()).contains(" for update");
	}

	@Test
	@DisplayName("예약 생성용 숙소 조회는 비관적 쓰기 잠금을 건다")
	void reservationAccommodationLookupUsesForUpdate() {
		Member member = memberRepository.save(Member.builder()
			.email("reservation-accommodation-lock@test.com")
			.nickname("reservation-accommodation-lock")
			.build());
		Accommodation accommodation = saveAccommodation(member, "reservation-accommodation-lock-target");
		accommodationRepository.flush();
		entityManager.clear();
		sqlInspector.clear();

		assertThat(accommodationRepository.findByIdAndStatusForUpdate(
			accommodation.getId(), AccommodationStatus.PUBLISHED)).isPresent();

		assertThat(sqlInspector.singleSelect()).contains(" for update");
	}

	@Test
	@DisplayName("숙소 수정용 소유자 조회는 비관적 쓰기 잠금을 건다")
	void updateAccommodationLookupUsesForUpdate() {
		Member member = memberRepository.save(Member.builder()
			.email("update-accommodation-lock@test.com")
			.nickname("update-accommodation-lock")
			.build());
		Accommodation accommodation = saveAccommodation(member, "update-accommodation-lock-target");
		accommodationRepository.flush();
		entityManager.clear();
		sqlInspector.clear();

		assertThat(accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			accommodation.getId(), member.getId(), AccommodationStatus.DELETED)).isPresent();

		assertThat(sqlInspector.singleSelect()).contains(" for update");
	}

	@Test
	@DisplayName("미래 재고를 점유하는 상태와 미만료 결제 대기만 시간 설정 변경을 차단한다")
	void futureInventoryReservationUsesStatusExpiryAndCheckoutBoundaries() {
		Member member = memberRepository.save(Member.builder()
			.email("future-inventory-guard@test.com")
			.nickname("future-inventory-guard")
			.build());
		Instant now = Instant.parse("2030-02-03T02:00:00Z");

		for (ReservationStatus status : List.of(
			ReservationStatus.PAYMENT_PROCESSING,
			ReservationStatus.CONFIRMED,
			ReservationStatus.CANCELLATION_PENDING,
			ReservationStatus.CANCELLATION_FAILED
		)) {
			Accommodation accommodation = saveAccommodation(member, "future-active-" + status);
			saveReservation(
				accommodation, member, status,
				LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),
				now, now.plusSeconds(1), now.minusSeconds(1));
			reservationRepository.flush();

			assertThat(reservationRepository.existsFutureInventoryReservation(
				accommodation.getId(), now))
				.as("status=%s", status)
				.isTrue();
		}

		Accommodation pending = saveAccommodation(member, "future-pending");
		saveReservation(
			pending, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),
			now, now.plusSeconds(1), now.plusSeconds(1));
		reservationRepository.flush();
		assertThat(reservationRepository.existsFutureInventoryReservation(pending.getId(), now)).isTrue();

		Accommodation expiredPending = saveAccommodation(member, "expired-pending-boundary");
		saveReservation(
			expiredPending, member, ReservationStatus.PAYMENT_PENDING,
			LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),
			now, now.plusSeconds(1), now);
		reservationRepository.flush();
		assertThat(reservationRepository.existsFutureInventoryReservation(
			expiredPending.getId(), now)).isFalse();

		Accommodation checkoutBoundary = saveAccommodation(member, "checkout-boundary");
		saveReservation(
			checkoutBoundary, member, ReservationStatus.CONFIRMED,
			LocalDate.of(2030, 2, 2), LocalDate.of(2030, 2, 3),
			now.minusSeconds(1), now, now.minusSeconds(1));
		reservationRepository.flush();
		assertThat(reservationRepository.existsFutureInventoryReservation(
			checkoutBoundary.getId(), now)).isFalse();

		for (ReservationStatus status : List.of(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED)) {
			Accommodation accommodation = saveAccommodation(member, "terminal-" + status);
			saveReservation(
				accommodation, member, status,
				LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),
				now, now.plusSeconds(1), now.plusSeconds(1));
			reservationRepository.flush();

			assertThat(reservationRepository.existsFutureInventoryReservation(
				accommodation.getId(), now))
				.as("status=%s", status)
				.isFalse();
		}
	}

	@Test
	@DisplayName("결제 취소 결과 반영용 결제 조회는 비관적 쓰기 잠금을 건다")
	void paymentCancellationLookupUsesForUpdate() {
		Member member = memberRepository.save(Member.builder()
			.email("payment-lock-query@test.com")
			.nickname("payment-lock-query")
			.build());
		Accommodation accommodation = saveAccommodation(member, "payment-lock-target");
		Reservation reservation = saveReservation(
			accommodation, member, ReservationStatus.CANCELLATION_PENDING,
			LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 2));
		Payment payment = paymentRepository.save(Payment.builder()
			.paymentKey("payment-lock-key")
			.orderId(reservation.getReservationUid().toString())
			.amount(100_000L)
			.method(PaymentMethod.CARD)
			.approvedAt(Instant.parse("2030-01-01T12:00:00Z"))
			.reservation(reservation)
			.status(PaymentStatus.DONE)
			.balanceAmount(100_000L)
			.build());
		paymentRepository.flush();
		sqlInspector.clear();

		assertThat(paymentRepository.findByReservationReservationUidWithLock(
			reservation.getReservationUid())).contains(payment);

		assertThat(sqlInspector.singleSelect()).contains(" for update");
	}

    @Test
    @DisplayName("ID 조회는 지정 기간과 겹치는 확정 예약만 날짜 구간으로 반환한다")
    void idQueryReturnsConfirmedReservationRangesOverlappingWindow() {
        Member member = memberRepository.save(Member.builder()
            .email("reservation-window-query@test.com")
            .nickname("reservation-window-query")
            .build());
        Accommodation target = saveAccommodation(member, "window-target");
        Accommodation other = saveAccommodation(member, "window-other");
        LocalDate windowStart = LocalDate.of(2030, 1, 1);
        LocalDate windowEndExclusive = LocalDate.of(2030, 4, 1);

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
		saveReservation(target, member, ReservationStatus.CANCELLATION_PENDING,
			inside.checkIn().plusDays(20), inside.checkOut().plusDays(20));
		saveReservation(target, member, ReservationStatus.CANCELLATION_FAILED,
			inside.checkIn().plusDays(30), inside.checkOut().plusDays(30));
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            overlapsEnd.checkIn(), overlapsEnd.checkOut());
        saveReservation(target, member, ReservationStatus.CONFIRMED,
            windowEndExclusive, windowEndExclusive.plusDays(2));
		saveReservation(target, member, ReservationStatus.PAYMENT_PENDING,
			inside.checkIn(), inside.checkOut());
		saveReservation(target, member, ReservationStatus.CANCELLED,
			inside.checkIn(), inside.checkOut());
		saveReservation(target, member, ReservationStatus.EXPIRED,
			inside.checkIn(), inside.checkOut());
        saveReservation(other, member, ReservationStatus.CONFIRMED,
            inside.checkIn(), inside.checkOut());
        reservationRepository.flush();

        sqlInspector.clear();
        List<ReservationDateRange> result = reservationRepository
			.findActiveReservationRangesByAccommodationId(
                target.getId(), windowStart, windowEndExclusive);
        String sql = sqlInspector.singleSelect();

		assertThat(result).containsExactlyInAnyOrder(
			overlapsStart,
			inside,
			overlapsEnd,
			new ReservationDateRange(inside.checkIn().plusDays(20), inside.checkOut().plusDays(20)),
			new ReservationDateRange(inside.checkIn().plusDays(30), inside.checkOut().plusDays(30))
		);
        assertDateRangeProjection(sql);
        assertThat(sql)
            .contains(".accommodation_id=?")
			.contains(".check_in_date<?")
			.contains(".check_out_date>?")
			.doesNotContain(" join accommodation ")
			.doesNotContain(" order by ");
    }

    @Test
	@DisplayName("UUID 조회도 지정 기간과 겹치는 활성 예약만 날짜 두 컬럼으로 반환한다")
	void uidQueryProjectsOnlyActiveReservationRangesOverlappingWindow() {
        Member member = memberRepository.save(Member.builder()
            .email("reservation-query@test.com")
            .nickname("reservation-query")
            .build());
        Accommodation target = saveAccommodation(member, "target");
        Accommodation other = saveAccommodation(member, "other");
		LocalDate base = LocalDate.of(2030, 6, 1);

        ReservationDateRange first = new ReservationDateRange(
            base.plusDays(1), base.plusDays(3));
        ReservationDateRange second = new ReservationDateRange(
            base.plusDays(4), base.plusDays(6));
		ReservationDateRange past = new ReservationDateRange(
			base.minusDays(3), base.minusDays(1));
		ReservationDateRange overlapsStart = new ReservationDateRange(
			base.minusDays(2), base.plusDays(1));
		LocalDate windowEndExclusive = base.plusDays(10);

        saveReservation(target, member, ReservationStatus.CONFIRMED,
            first.checkIn(), first.checkOut());
		saveReservation(target, member, ReservationStatus.CONFIRMED,
			second.checkIn(), second.checkOut());
		ReservationDateRange cancellationPending = new ReservationDateRange(
			base.plusDays(7), base.plusDays(9));
		ReservationDateRange cancellationFailed = new ReservationDateRange(
			base.plusDays(10), base.plusDays(12));
		saveReservation(target, member, ReservationStatus.CANCELLATION_PENDING,
			cancellationPending.checkIn(), cancellationPending.checkOut());
		saveReservation(target, member, ReservationStatus.CANCELLATION_FAILED,
			cancellationFailed.checkIn(), cancellationFailed.checkOut());
		saveReservation(
			target, member, ReservationStatus.CONFIRMED,
			past.checkIn(), past.checkOut());
		saveReservation(target, member, ReservationStatus.CONFIRMED,
			overlapsStart.checkIn(), overlapsStart.checkOut());
		saveReservation(target, member, ReservationStatus.CONFIRMED,
			base.minusDays(2), base);
		saveReservation(
			target, member, ReservationStatus.PAYMENT_PENDING,
			base.plusDays(1), base.plusDays(3));
		saveReservation(target, member, ReservationStatus.CANCELLED,
			base.plusDays(13), base.plusDays(14));
		saveReservation(target, member, ReservationStatus.EXPIRED,
			base.plusDays(15), base.plusDays(16));
        saveReservation(
            other, member, ReservationStatus.CONFIRMED,
            base.plusDays(1), base.plusDays(3));
        reservationRepository.flush();

        sqlInspector.clear();
		List<ReservationDateRange> byUid = reservationRepository
			.findActiveReservationRangesByAccommodationUid(
				target.getAccommodationUid(), base, windowEndExclusive);
        String uidSql = sqlInspector.singleSelect();

		assertThat(byUid).containsExactlyInAnyOrder(
			overlapsStart, first, second, cancellationPending);
		assertDateRangeProjection(uidSql);
		assertThat(uidSql)
			.contains(".check_in_date<?")
			.contains(".check_out_date>?")
			.doesNotContain(".check_out_at")
            .doesNotContain(" order by ");
    }

    private void assertDateRangeProjection(String sql) {
        int fromIndex = sql.indexOf(" from ");
        assertThat(fromIndex).isPositive();

        List<String> selectedColumns = List.of(
            sql.substring("select ".length(), fromIndex).split(","));

        assertThat(selectedColumns).hasSize(2);
		assertThat(selectedColumns.get(0)).contains(".check_in_date");
		assertThat(selectedColumns.get(1)).contains(".check_out_date");
    }

    private Accommodation saveAccommodation(Member member, String name) {
        return accommodationRepository.save(Accommodation.builder()
            .member(member)
            .name(name)
            .checkInTime(LocalTime.of(15, 0))
            .checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("UTC")
            .status(AccommodationStatus.PUBLISHED)
            .build());
    }

    private Reservation saveReservation(
        Accommodation accommodation,
        Member guest,
        ReservationStatus status,
		LocalDate checkIn,
		LocalDate checkOut
    ) {
		return saveReservation(
			accommodation,
			guest,
			status,
			checkIn,
			checkOut,
			checkIn.atTime(15, 0).toInstant(ZoneOffset.UTC),
			checkOut.atTime(11, 0).toInstant(ZoneOffset.UTC),
			Instant.parse("2029-01-01T00:15:00Z")
		);
	}

	private Reservation saveReservation(
		Accommodation accommodation,
		Member guest,
		ReservationStatus status,
		LocalDate checkIn,
		LocalDate checkOut,
		Instant checkInAt,
		Instant checkOutAt,
		Instant expiresAt
	) {
        return reservationRepository.save(Reservation.builder()
            .accommodation(accommodation)
            .guest(guest)
			.checkInDate(checkIn)
			.checkOutDate(checkOut)
			.checkInAt(checkInAt)
			.checkOutAt(checkOutAt)
			.timeZoneId("UTC")
            .guestCount(1)
            .totalPrice(100_000L)
            .currency("KRW")
            .status(status)
			.expiresAt(expiresAt)
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

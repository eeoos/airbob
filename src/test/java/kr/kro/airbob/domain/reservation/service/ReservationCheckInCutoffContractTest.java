package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class ReservationCheckInCutoffContractTest {

	private static final long ACCOMMODATION_ID = 1L;
	private static final long GUEST_ID = 10L;
	private static final LocalDate CHECK_IN_DATE = LocalDate.of(2026, 8, 26);
	private static final LocalDate CHECK_OUT_DATE = LocalDate.of(2026, 8, 28);
	private static final Instant CHECK_IN_AT = Instant.parse("2026-08-26T06:00:00Z");
	private static final String ACCOMMODATION_TIME_ZONE = "Asia/Seoul";

	@Mock private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock private MemberRepository memberRepository;
	@Mock private ReviewRepository reviewRepository;
	@Mock private PaymentRepository paymentRepository;
	@Mock private ReservationRepository reservationRepository;
	@Mock private AccommodationRepository accommodationRepository;
	@Mock private PaymentTransactionRepository paymentTransactionRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private BookingWindowProvider bookingWindowProvider;
	@Mock private ReservationCheckoutRequestStore checkoutRequestStore;
	@Mock private ReservationQuoteRepository quoteRepository;

	@Test
	void rejectsANewReservationAtTheAccommodationLocalCheckInInstant() {
		ReservationTransactionService service = serviceAt(CHECK_IN_AT);
		ReservationRequest.Create request = request();
		stubValidMemberAccommodationAndWindow(request);

		Throwable thrown = catchThrowable(() -> service.createPendingReservationInTx(
			request, GUEST_ID, "체크인 시각 경계 예약"));

		assertCheckInClosedContract(thrown);
		then(reservationRepository).shouldHaveNoInteractions();
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
	}

	@Test
	void rejectsANewReservationAfterTheAccommodationLocalCheckInInstant() {
		ReservationTransactionService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		ReservationRequest.Create request = request();
		stubValidMemberAccommodationAndWindow(request);

		Throwable thrown = catchThrowable(() -> service.createPendingReservationInTx(
			request, GUEST_ID, "체크인 이후 예약"));

		assertCheckInClosedContract(thrown);
		then(reservationRepository).shouldHaveNoInteractions();
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
	}

	@Test
	void rejectsAPastCheckInAfterTheAccommodationLocalDateHasRolledOver() {
		ReservationTransactionService service = serviceAt(CHECK_IN_AT.plusSeconds(10 * 60 * 60));
		ReservationRequest.Create request = request();
		stubValidMemberAccommodationAndWindow(request);

		Throwable thrown = catchThrowable(() -> service.createPendingReservationInTx(
			request, GUEST_ID, "체크인 다음 현지 날짜 예약"));

		assertCheckInClosedContract(thrown);
		then(reservationRepository).shouldHaveNoInteractions();
	}

	@Test
	void allowsANewReservationImmediatelyBeforeTheAccommodationLocalCheckInInstant() {
		ReservationTransactionService service = serviceAt(CHECK_IN_AT.minusNanos(1));
		ReservationRequest.Create request = request();
		stubValidMemberAccommodationAndWindow(request);
		given(reservationRepository.existsConflictingReservation(
			ACCOMMODATION_ID, CHECK_IN_DATE, CHECK_OUT_DATE, CHECK_IN_AT.minusNanos(1)))
			.willReturn(false);
		given(reservationRepository.existsByReservationCode(any(String.class))).willReturn(false);

		Reservation reservation = service.createPendingReservationInTx(
			request, GUEST_ID, "체크인 직전 예약");

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(reservation.getCheckInAt()).isEqualTo(CHECK_IN_AT);
		then(reservationRepository).should().save(reservation);
		then(historyRepository).should().save(any());
	}

	private ReservationTransactionService serviceAt(Instant now) {
		return new ReservationTransactionService(
			searchRefreshPublisher,
			cursorPageInfoCreator,
			memberRepository,
			reviewRepository,
			paymentRepository,
			reservationRepository,
			accommodationRepository,
			paymentTransactionRepository,
			historyRepository,
			couponUsageService,
			bookingWindowProvider,
			ReservationHoldPolicy.defaultPolicy(),
			quoteRepository,
			checkoutRequestStore,
			Clock.fixed(now, ZoneOffset.UTC)
		);
	}

	private void stubValidMemberAccommodationAndWindow(ReservationRequest.Create request) {
		given(memberRepository.findByIdAndStatus(GUEST_ID, MemberStatus.ACTIVE))
			.willReturn(Optional.of(guest()));
		given(accommodationRepository.findByIdAndStatusForUpdate(
			request.accommodationId(), AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(accommodation()));
		given(bookingWindowProvider.currentFor(
			eq(ACCOMMODATION_TIME_ZONE), any(Instant.class)))
			.willReturn(BookingWindow.startingOn(CHECK_IN_DATE));
	}

	private ReservationRequest.Create request() {
		return new ReservationRequest.Create(
			ACCOMMODATION_ID,
			CHECK_IN_DATE,
			CHECK_OUT_DATE,
			2
		);
	}

	private Accommodation accommodation() {
		return Accommodation.builder()
			.id(ACCOMMODATION_ID)
			.accommodationUid(UUID.fromString("b9157c88-6e41-4264-b5f0-60f98cb15872"))
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(ACCOMMODATION_TIME_ZONE)
			.status(AccommodationStatus.PUBLISHED)
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(2).build())
			.build();
	}

	private Member guest() {
		return Member.builder().id(GUEST_ID).build();
	}

	private void assertCheckInClosedContract(Throwable thrown) {
		assertThat(thrown).isInstanceOf(BaseException.class);
		assertThat(thrown.getClass().getSimpleName())
			.isEqualTo("ReservationCheckInClosedException");
		BaseException exception = (BaseException)thrown;
		assertThat(exception.getErrorCode().name()).isEqualTo("RESERVATION_CHECK_IN_CLOSED");
		assertThat(exception.getErrorCode().getCode()).isEqualTo("R014");
		assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.CONFLICT);
	}
}

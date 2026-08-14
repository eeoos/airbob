package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationAccessDeniedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationTransactionService 테스트")
class ReservationTransactionServiceTest {
	private static final String TIME_ZONE_ID = "America/New_York";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 11);
	private static final Instant NOW = Instant.parse("2026-08-14T15:00:00Z");

	private ReservationTransactionService transactionService;

	@Mock
	private OutboxEventPublisher outboxEventPublisher;
	@Mock
	private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private PaymentTransactionRepository paymentTransactionRepository;
	@Mock
	private ReservationHistoryRepository historyRepository;
	@Mock
	private CouponUsageService couponUsageService;
	@Mock
	private BookingWindowProvider bookingWindowProvider;

	@Captor
	private ArgumentCaptor<Reservation> reservationCaptor;
	@Captor
	private ArgumentCaptor<ReservationHistory> historyCaptor;

	private Member guest;
	private Accommodation accommodation;
	private ReservationRequest.Create validRequest;
	private Long memberId;

	private Member host;

	@BeforeEach
	void setUp() {
		transactionService = new ReservationTransactionService(
			outboxEventPublisher,
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
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		memberId = 1L;

		guest = Member.builder()
			.id(memberId)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();

		host = Member.builder()
			.id(2L)
			.email("host@test.com")
			.nickname("TestHost")
			.build();

		accommodation = Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.randomUUID())
			.name("Test Accommodation")
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.member(host)
			.build();

		validRequest = new ReservationRequest.Create(
			1L,
			LocalDate.of(2026, 8, 12),
			LocalDate.of(2026, 8, 14),
			2
		);

		lenient().when(bookingWindowProvider.currentFor(TIME_ZONE_ID))
			.thenReturn(BookingWindow.startingOn(WINDOW_START));
	}

	@Test
	@DisplayName("체크아웃 시각과 현재 시각이 같으면 리뷰를 작성할 수 있다")
	void allowsReviewAtExactCheckoutInstant() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2026, 8, 12))
			.checkOutDate(LocalDate.of(2026, 8, 14))
			.checkInAt(Instant.parse("2026-08-12T19:00:00Z"))
			.checkOutAt(NOW)
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(ReservationStatus.CONFIRMED)
			.expiresAt(Instant.parse("2026-08-12T18:15:00Z"))
			.build();
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			accommodation.getId(), memberId, kr.kro.airbob.domain.review.entity.ReviewStatus.PUBLISHED))
			.willReturn(false);

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isTrue();
	}

	@Test
	@DisplayName("체크아웃했어도 취소 처리 중이면 리뷰를 작성할 수 없다")
	void cancellationPendingIsNotReviewable() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = createReservationWithStatus(
			reservationUid, ReservationStatus.CANCELLATION_PENDING);
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isFalse();
		then(reviewRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("취소 실패로 예약이 유효하면 체크아웃 후 리뷰를 작성할 수 있다")
	void cancellationFailedIsReviewable() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = createReservationWithStatus(
			reservationUid, ReservationStatus.CANCELLATION_FAILED);
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			accommodation.getId(), memberId, kr.kro.airbob.domain.review.entity.ReviewStatus.PUBLISHED))
			.willReturn(false);

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isTrue();
	}

	@Nested
	@DisplayName("예약 생성 트랜잭션 테스트")
	class CreatePendingReservationInTxTest {

		@Test
		@DisplayName("정상적인 예약 생성 시 Reservation이 저장되고 이벤트가 발행된다")
		void 정상_예약_생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(
				anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
				.willReturn(false);
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환 (실제 JPA에서 @PrePersist로 설정됨)
			given(reservationRepository.save(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					// 리플렉션으로 reservationUid 설정 (실제 @PrePersist 동작 모방)
					if (reservation.getReservationUid() == null) {
						java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
						uidField.setAccessible(true);
						uidField.set(reservation, UUID.randomUUID());
					}
					return reservation;
				});

			// when
			Reservation result = transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");

			// then
			assertThat(result).isNotNull();
			assertThat(result.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(result.getTotalPrice()).isEqualTo(200_000L); // 2박 * 100,000원
			assertThat(result.getGuestCount()).isEqualTo(2);
			assertThat(result.getCheckInDate()).isEqualTo(validRequest.checkInDate());
			assertThat(result.getCheckOutDate()).isEqualTo(validRequest.checkOutDate());
			assertThat(result.getCheckInAt()).isEqualTo(Instant.parse("2026-08-12T19:00:00Z"));
			assertThat(result.getCheckOutAt()).isEqualTo(Instant.parse("2026-08-14T15:00:00Z"));
			assertThat(result.getTimeZoneId()).isEqualTo(TIME_ZONE_ID);
			assertThat(result.getExpiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
			then(accommodationRepository).should().findByIdAndStatusForUpdate(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED);

			// verify reservation saved
			then(reservationRepository).should().save(any(Reservation.class));

			// verify history saved
			then(historyRepository).should().save(historyCaptor.capture());
			ReservationHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.CREATE);
			assertThat(savedHistory.getReservationId()).isEqualTo(result.getId());
			assertThat(savedHistory.getCheckInDate()).isEqualTo(result.getCheckInDate());
			assertThat(savedHistory.getCheckOutDate()).isEqualTo(result.getCheckOutDate());
			assertThat(savedHistory.getCheckInAt()).isEqualTo(result.getCheckInAt());
			assertThat(savedHistory.getCheckOutAt()).isEqualTo(result.getCheckOutAt());
			assertThat(savedHistory.getTimeZoneId()).isEqualTo(result.getTimeZoneId());
			assertThat(savedHistory.getExpiresAt()).isEqualTo(result.getExpiresAt());

			// verify event published
			then(outboxEventPublisher).should().save(eq(EventType.RESERVATION_PENDING), any(ReservationEvent.ReservationPendingEvent.class));
		}

		@Test
		@DisplayName("DST 전환을 지나는 숙박도 숙소 현지 시각을 정확한 절대 시각으로 변환한다")
		void conflictCheckUsesAccommodationZoneInstantsAcrossDst() {
			ReservationRequest.Create dstRequest = new ReservationRequest.Create(
				accommodation.getId(),
				LocalDate.of(2026, 3, 7),
				LocalDate.of(2026, 3, 9),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				dstRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(bookingWindowProvider.currentFor(TIME_ZONE_ID))
				.willReturn(BookingWindow.startingOn(LocalDate.of(2026, 3, 1)));
			given(reservationRepository.existsConflictingReservation(
				anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
				.willReturn(false);
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(false);
			given(reservationRepository.save(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
					uidField.setAccessible(true);
					uidField.set(reservation, UUID.randomUUID());
					return reservation;
				});

			Reservation result = transactionService.createPendingReservationInTx(
				dstRequest, memberId, "DST 경계 예약 생성");

			Instant expectedCheckInAt = Instant.parse("2026-03-07T20:00:00Z");
			Instant expectedCheckOutAt = Instant.parse("2026-03-09T15:00:00Z");
			then(reservationRepository).should().existsConflictingReservation(
				accommodation.getId(), dstRequest.checkInDate(), dstRequest.checkOutDate(), NOW);
			assertThat(result.getCheckInAt()).isEqualTo(expectedCheckInAt);
			assertThat(result.getCheckOutAt()).isEqualTo(expectedCheckOutAt);
		}

		@Test
		@DisplayName("회원이 존재하지 않으면 MemberNotFoundException이 발생한다")
		void 예외_회원_미존재() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(MemberNotFoundException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("숙소가 존재하지 않으면 AccommodationNotFoundException이 발생한다")
		void 예외_숙소_미존재() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(AccommodationNotFoundException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("체크아웃이 체크인보다 이후가 아니면 범위와 충돌 검사 전에 거부한다")
		void 예외_잘못된_숙박_기간() {
			ReservationRequest.Create invalidRequest = new ReservationRequest.Create(
				1L,
				LocalDate.of(2026, 8, 12),
				LocalDate.of(2026, 8, 12),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				invalidRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));

			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(
				invalidRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(InvalidReservationDateException.class);

			then(bookingWindowProvider).shouldHaveNoInteractions();
			then(reservationRepository).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("숙소 현지 예약 가능 기간을 벗어나면 충돌과 쓰기 전에 거부한다")
		void 예외_예약_가능_기간_초과() {
			ReservationRequest.Create outsideRequest = new ReservationRequest.Create(
				1L,
				WINDOW_START.plusMonths(3),
				WINDOW_START.plusMonths(3).plusDays(1),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				outsideRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));

			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(
				outsideRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(ReservationOutsideBookingWindowException.class);

			then(bookingWindowProvider).should().currentFor(TIME_ZONE_ID);
			then(reservationRepository).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("날짜가 충돌하면 ReservationConflictException이 발생한다")
		void 예외_날짜_충돌() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(
				anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
				.willReturn(true);

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(ReservationConflictException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("예약 코드가 중복되면 재생성한다")
		void 예약코드_중복시_재생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatusForUpdate(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(
				anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
				.willReturn(false);
			// 첫 번째 코드는 중복, 두 번째는 유일
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(true)
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환
			given(reservationRepository.save(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					if (reservation.getReservationUid() == null) {
						java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
						uidField.setAccessible(true);
						uidField.set(reservation, UUID.randomUUID());
					}
					return reservation;
				});

			// when
			Reservation result = transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");

			// then
			assertThat(result).isNotNull();
			// 코드 중복 체크가 2번 호출됨
			then(reservationRepository).should(times(2)).existsByReservationCode(anyString());
		}
	}


	@Nested
	@DisplayName("예약 취소 트랜잭션 테스트")
	class CancelReservationInTxTest {

		@Test
		@DisplayName("CONFIRMED 상태에서 본인이 취소 요청 시 CANCELLATION_PENDING으로 변경된다")
		void 정상_취소요청() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, guest);
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", null);

			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId);

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
			assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.STATUS_CHANGE);
			assertThat(savedHistory.getChangeReason()).isEqualTo("사용자 취소 요청");

			then(outboxEventPublisher).should().save(
				eq(EventType.RESERVATION_CANCELLATION_REQUESTED),
				any(ReservationEvent.ReservationCancellationRequestedEvent.class));
			then(couponUsageService).shouldHaveNoInteractions();
			then(outboxEventPublisher).should(never()).save(eq(EventType.RESERVATION_CHANGED), any());
		}

		@Test
		@DisplayName("이미 CANCELLATION_PENDING이면 중복 PG 취소 요청을 발행하지 않는다")
		void 중복_취소요청_멱등() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(
				reservationUid, ReservationStatus.CANCELLATION_PENDING);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));

			transactionService.cancelReservationInTx(
				reservationUid.toString(), new PaymentRequest.Cancel("중복 요청", null), memberId);

			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
			then(couponUsageService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("명시한 취소 금액이 현재 결제 잔액보다 작으면 예약 취소를 시작하지 않는다")
		void 부분취소_금액_거부() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, guest);
			Payment payment = Payment.builder()
				.reservation(reservation)
				.balanceAmount(200_000L)
				.build();
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			assertThatThrownBy(() -> transactionService.cancelReservationInTx(
				reservationUid.toString(), new PaymentRequest.Cancel("부분 환불", 100_000L), memberId))
				.isInstanceOf(InvalidInputException.class);

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("명시한 취소 금액이 현재 결제 잔액 전액이면 취소 요청을 시작한다")
		void 현재잔액_전액취소_허용() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, guest);
			Payment payment = Payment.builder()
				.reservation(reservation)
				.balanceAmount(200_000L)
				.build();
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			transactionService.cancelReservationInTx(
				reservationUid.toString(), new PaymentRequest.Cancel("전액 환불", 200_000L), memberId);

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
			then(outboxEventPublisher).should().save(
				eq(EventType.RESERVATION_CANCELLATION_REQUESTED),
				argThat(event -> event instanceof ReservationEvent.ReservationCancellationRequestedEvent requested
					&& requested.cancelAmount().equals(200_000L)));
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", null);

			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId))
				.isInstanceOf(ReservationNotFoundException.class);
		}

		@Test
		@DisplayName("본인이 아니면 ReservationAccessDeniedException이 발생한다")
		void 예외_권한_없음() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Member anotherGuest = Member.builder()
				.id(999L)
				.email("another@test.com")
				.nickname("AnotherGuest")
				.build();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, anotherGuest);
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", null);

			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));

			// when & then
			assertThatThrownBy(() -> transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId))
				.isInstanceOf(ReservationAccessDeniedException.class);
		}
	}

	@Nested
	@DisplayName("예약 취소 성공 확정 트랜잭션 테스트")
	class CompleteCancellationInTxTest {

		@Test
		@DisplayName("CANCELLATION_PENDING에서 성공 이벤트를 받으면 CANCELLED 확정 후 쿠폰과 재고를 갱신한다")
		void 정상_취소성공_확정() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(
				reservationUid, ReservationStatus.CANCELLATION_PENDING);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			givenFullyCancelledPayment(reservationUid);

			transactionService.completeCancellationInTx(reservationUid.toString());

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			then(couponUsageService).should().restore(reservation.getId());
			then(historyRepository).should().save(historyCaptor.capture());
			assertThat(historyCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			assertThat(historyCaptor.getValue().getChangeType()).isEqualTo(ChangeType.CANCEL);
			then(outboxEventPublisher).should().save(
				eq(EventType.RESERVATION_CHANGED),
				argThat(event -> event instanceof AccommodationIndexingEvents.ReservationChangedEvent changed
					&& changed.accommodationUid().equals(accommodation.getAccommodationUid().toString()))
			);
		}

		@Test
		@DisplayName("실패 이벤트 뒤 전액 환불 성공이 확인되면 CANCELLED로 수렴한다")
		void lateSuccessAfterFailureConvergesToCancelled() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(
				reservationUid, ReservationStatus.CANCELLATION_FAILED);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			givenFullyCancelledPayment(reservationUid);

			transactionService.completeCancellationInTx(reservationUid.toString());

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			then(couponUsageService).should().restore(reservation.getId());
		}

		@Test
		@DisplayName("PG 전액 환불이 확인되지 않으면 예약 재고를 해제하지 않는다")
		void doesNotReleaseInventoryWithoutFullRefund() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(
				reservationUid, ReservationStatus.CANCELLATION_PENDING);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.PARTIAL_CANCELED);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			assertThatThrownBy(() -> transactionService.completeCancellationInTx(reservationUid.toString()))
				.isInstanceOf(IllegalStateException.class);

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
			then(couponUsageService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 CANCELLED인 레거시 예약도 전액 환불을 확인하고 ES 예약 범위를 갱신한다")
		void 레거시_취소성공_ES갱신() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			givenFullyCancelledPayment(reservationUid);

			transactionService.completeCancellationInTx(reservationUid.toString());

			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).should().save(
				eq(EventType.RESERVATION_CHANGED),
				argThat(event -> event instanceof AccommodationIndexingEvents.ReservationChangedEvent changed
					&& changed.accommodationUid().equals(accommodation.getAccommodationUid().toString()))
			);
		}

		@Test
		@DisplayName("이미 CANCELLED여도 전액 환불이 확인되지 않으면 ES 예약 범위를 갱신하지 않는다")
		void 레거시_취소성공_결제검증() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.CANCELED);
			given(payment.getBalanceAmount()).willReturn(100_000L);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			assertThatThrownBy(() -> transactionService.completeCancellationInTx(reservationUid.toString()))
				.isInstanceOf(IllegalStateException.class);

			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("CANCELLED 성공 이벤트가 다시 와도 상태 부수 효과 없이 같은 ES 갱신으로 수렴한다")
		void 중복_성공_멱등() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			givenFullyCancelledPayment(reservationUid);

			transactionService.completeCancellationInTx(reservationUid.toString());
			transactionService.completeCancellationInTx(reservationUid.toString());

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
			then(outboxEventPublisher).should(times(2)).save(
				eq(EventType.RESERVATION_CHANGED),
				argThat(event -> event instanceof AccommodationIndexingEvents.ReservationChangedEvent changed
					&& changed.accommodationUid().equals(accommodation.getAccommodationUid().toString()))
			);
		}
	}

	@Nested
	@DisplayName("취소 실패 확정 트랜잭션 테스트")
	class RevertCancellationInTxTest {

		@Test
		@DisplayName("CANCELLATION_PENDING 상태에서 실패 시 CANCELLATION_FAILED로 변경된다")
		void 정상_실패확정() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(
				reservationUid, ReservationStatus.CANCELLATION_PENDING);

			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.STATUS_CHANGE);
			assertThat(savedHistory.getSourceSystem()).isEqualTo("KAFKA");
			then(couponUsageService).shouldHaveNoInteractions();
			then(outboxEventPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 CANCELLATION_FAILED 상태면 조기 반환한다 (멱등성)")
		void 멱등성_이미_실패() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLATION_FAILED);

			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			then(historyRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("레거시 CANCELLED에서 결제가 남아 있으면 예약과 쿠폰을 복구한다")
		void 레거시_전액환불_실패_복구() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.DONE);
			given(payment.getBalanceAmount()).willReturn(200_000L);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패");

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			then(couponUsageService).should().reuse(reservation.getId());
			then(historyRepository).should().save(historyCaptor.capture());
			assertThat(historyCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			var lockOrder = inOrder(reservationRepository, paymentRepository);
			lockOrder.verify(reservationRepository).findByReservationUidWithLock(reservationUid);
			lockOrder.verify(paymentRepository).findByReservationReservationUidWithLock(reservationUid);
		}

		@Test
		@DisplayName("레거시 CANCELLED의 부분 환불 실패도 예약과 쿠폰을 복구한다")
		void 레거시_부분환불_실패_복구() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.PARTIAL_CANCELED);
			given(payment.getBalanceAmount()).willReturn(100_000L);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			transactionService.revertCancellationInTx(reservationUid.toString(), "부분 환불");

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			then(couponUsageService).should().reuse(reservation.getId());
		}

		@Test
		@DisplayName("전액 환불 성공 뒤 도착한 느은 실패 이벤트는 무시한다")
		void 전액환불_후_느은실패_무시() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.CANCELED);
			given(payment.getBalanceAmount()).willReturn(0L);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			transactionService.revertCancellationInTx(reservationUid.toString(), "느은 실패");

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("레거시 CANCELLED의 모순된 결제 상태는 임의로 복구하지 않는다")
		void 레거시_모순상태_복구거부() {
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);
			Payment payment = mock(Payment.class);
			given(payment.getStatus()).willReturn(PaymentStatus.CANCELED);
			given(payment.getBalanceAmount()).willReturn(1L);
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(reservation));
			given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
				.willReturn(Optional.of(payment));

			assertThatThrownBy(() -> transactionService.revertCancellationInTx(
				reservationUid.toString(), "모순된 실패"))
				.isInstanceOf(IllegalStateException.class);

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			given(reservationRepository.findByReservationUidWithLock(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패"))
				.isInstanceOf(ReservationNotFoundException.class);
		}
	}

	private Reservation createReservationWithStatus(UUID reservationUid, ReservationStatus status) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2025, 1, 26))
			.checkOutDate(LocalDate.of(2025, 1, 28))
			.checkInAt(Instant.parse("2025-01-26T20:00:00Z"))
			.checkOutAt(Instant.parse("2025-01-28T16:00:00Z"))
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(status)
			.expiresAt(NOW.plusSeconds(15 * 60))
			.build();
	}

	private void givenFullyCancelledPayment(UUID reservationUid) {
		Payment payment = mock(Payment.class);
		given(payment.getStatus()).willReturn(PaymentStatus.CANCELED);
		given(payment.getBalanceAmount()).willReturn(0L);
		given(paymentRepository.findByReservationReservationUidWithLock(reservationUid))
			.willReturn(Optional.of(payment));
	}

	private Reservation createConfirmedReservationWithGuest(UUID reservationUid, Member guestMember) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guestMember)
			.checkInDate(LocalDate.of(2025, 1, 26))
			.checkOutDate(LocalDate.of(2025, 1, 28))
			.checkInAt(Instant.parse("2025-01-26T20:00:00Z"))
			.checkOutAt(Instant.parse("2025-01-28T16:00:00Z"))
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(ReservationStatus.CONFIRMED)
			.expiresAt(NOW.plusSeconds(15 * 60))
			.build();
	}
}

package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationBookingProjection;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 테스트")
class ReservationServiceTest {
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 12);
	private static final BookingWindow BOOKING_WINDOW = BookingWindow.startingOn(WINDOW_START);

	@InjectMocks
	private ReservationService reservationService;

	@Mock
	private ReservationHoldService holdService;

	@Mock
	private ReservationLockManager lockManager;

	@Mock
	private ReservationTransactionService transactionService;

	@Mock
	private AccommodationRepository accommodationRepository;

	@Mock
	private BookingWindowProvider bookingWindowProvider;

	@Mock
	private RLock mockLock;

	private ReservationRequest.Create validRequest;
	private Long memberId;
	private Reservation mockReservation;

	@BeforeEach
	void setUp() {
		memberId = 1L;
		LocalDate checkInDate = WINDOW_START.plusDays(1);
		validRequest = new ReservationRequest.Create(
			1L,
			checkInDate,
			checkInDate.plusDays(2),
			2
		);

		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.name("Test Accommodation")
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build();

		Member guest = Member.builder()
			.id(memberId)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();

		mockReservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.status(ReservationStatus.PAYMENT_PENDING)
			.totalPrice(200_000L)
			.build();
	}

	@Nested
	@DisplayName("예약 생성 테스트")
	class CreatePendingReservationTest {

		@Test
		@DisplayName("정상적인 예약 생성 시 Ready 응답이 반환된다")
		void 정상_예약_생성_성공() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList()))
				.willReturn(mockLock);
			given(transactionService.createPendingReservationInTx(any(), anyLong(), anyString()))
				.willReturn(mockReservation);

			// when
			ReservationResponse.Ready result = reservationService.createPendingReservation(validRequest, memberId);

			// then
			assertThat(result).isNotNull();
			assertThat(result.reservationUid()).isEqualTo(mockReservation.getReservationUid().toString());
			assertThat(result.amount()).isEqualTo(mockReservation.getTotalPrice());
			assertThat(result.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(result.paymentRequired()).isTrue();

			// verify interactions
			then(holdService).should().isAnyDateHeld(
				validRequest.accommodationId(),
				validRequest.checkInDate(),
				validRequest.checkOutDate()
			);
			then(lockManager).should().acquireLocks(anyList());
			then(transactionService).should().createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");
			then(holdService).should().holdDates(
				validRequest.accommodationId(),
				validRequest.checkInDate(),
				validRequest.checkOutDate()
			);
			then(accommodationRepository).should()
				.findBookingProjectionByIdAndStatus(1L, AccommodationStatus.PUBLISHED);
			then(bookingWindowProvider).should().currentFor(TIME_ZONE_ID);
			then(lockManager).should().releaseLocks(mockLock);
		}

		@Test
		@DisplayName("0원으로 즉시 확정된 예약에는 Redis 결제 홀드를 만들지 않는다")
		void complimentaryReservationSkipsPaymentHold() {
			Reservation complimentary = Reservation.builder()
				.id(mockReservation.getId())
				.reservationUid(mockReservation.getReservationUid())
				.accommodation(mockReservation.getAccommodation())
				.guest(mockReservation.getGuest())
				.status(ReservationStatus.CONFIRMED)
				.totalPrice(0L)
				.build();
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList())).willReturn(mockLock);
			given(transactionService.createPendingReservationInTx(any(), anyLong(), anyString()))
				.willReturn(complimentary);

			ReservationResponse.Ready result = reservationService.createPendingReservation(validRequest, memberId);

			assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
			assertThat(result.paymentRequired()).isFalse();
			then(holdService).should(never()).holdDates(anyLong(), any(), any());
			then(lockManager).should().releaseLocks(mockLock);
		}

		@Test
		@DisplayName("Redis Hold가 존재하면 ReservationLockException이 발생한다")
		void 예외_Redis_Hold_존재() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(true);

			// when & then
			assertThatThrownBy(() -> reservationService.createPendingReservation(validRequest, memberId))
				.isInstanceOf(ReservationLockException.class);

			// verify no lock acquisition attempted
			then(lockManager).should(never()).acquireLocks(anyList());
			then(transactionService).should(never()).createPendingReservationInTx(any(), anyLong(), anyString());
		}

		@Test
		@DisplayName("3개월 예약 가능 기간을 벗어나면 Redis와 DB 처리 전에 거부한다")
		void 예외_예약_가능_기간_초과() {
			LocalDate windowEndExclusive = BOOKING_WINDOW.endExclusive();
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				windowEndExclusive,
				windowEndExclusive.plusDays(1),
				2
			);
			givenPublishedBookingWindow(request.accommodationId(), TIME_ZONE_ID, BOOKING_WINDOW);

			assertThatThrownBy(() -> reservationService.createPendingReservation(request, memberId))
				.isInstanceOf(ReservationOutsideBookingWindowException.class);

			then(holdService).shouldHaveNoInteractions();
			then(lockManager).shouldHaveNoInteractions();
			then(transactionService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("경량 조회한 숙소 시간대를 예약 가능 기간 계산에 그대로 사용한다")
		void 숙소_시간대_전달() {
			String newYorkTimeZoneId = "America/New_York";
			BookingWindow newYorkWindow = BookingWindow.startingOn(LocalDate.of(2026, 8, 11));
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				LocalDate.of(2026, 8, 12),
				LocalDate.of(2026, 8, 14),
				2
			);
			givenPublishedBookingWindow(request.accommodationId(), newYorkTimeZoneId, newYorkWindow);
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(true);

			assertThatThrownBy(() -> reservationService.createPendingReservation(request, memberId))
				.isInstanceOf(ReservationLockException.class);

			then(bookingWindowProvider).should().currentFor(newYorkTimeZoneId);
		}

		@Test
		@DisplayName("숙소가 없거나 게시 상태가 아니면 Redis 처리 전에 거부한다")
		void 예외_예약_가능_숙소_미존재() {
			given(accommodationRepository.findBookingProjectionByIdAndStatus(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> reservationService.createPendingReservation(validRequest, memberId))
				.isInstanceOf(AccommodationNotFoundException.class);

			then(bookingWindowProvider).shouldHaveNoInteractions();
			then(holdService).shouldHaveNoInteractions();
			then(lockManager).shouldHaveNoInteractions();
			then(transactionService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("체크아웃이 체크인보다 이후가 아니면 Redis와 DB 처리 전에 거부한다")
		void 예외_잘못된_숙박_기간() {
			LocalDate checkInDate = WINDOW_START.plusDays(1);
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				checkInDate,
				checkInDate,
				2
			);

			assertThatThrownBy(() -> reservationService.createPendingReservation(request, memberId))
				.isInstanceOf(InvalidReservationDateException.class);

			then(holdService).shouldHaveNoInteractions();
			then(lockManager).shouldHaveNoInteractions();
			then(transactionService).shouldHaveNoInteractions();
			then(accommodationRepository).shouldHaveNoInteractions();
			then(bookingWindowProvider).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("락 획득 실패 시 ReservationLockException이 발생한다")
		void 예외_락_획득_타임아웃() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList()))
				.willThrow(new ReservationLockException());

			// when & then
			assertThatThrownBy(() -> reservationService.createPendingReservation(validRequest, memberId))
				.isInstanceOf(ReservationLockException.class);

			then(transactionService).should(never()).createPendingReservationInTx(any(), anyLong(), anyString());
		}

		@Test
		@DisplayName("예외 발생 시에도 락 해제가 보장된다")
		void 락_해제_보장() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList()))
				.willReturn(mockLock);
			given(transactionService.createPendingReservationInTx(any(), anyLong(), anyString()))
				.willThrow(new RuntimeException("Transaction failed"));

			// when & then
			assertThatThrownBy(() -> reservationService.createPendingReservation(validRequest, memberId))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Transaction failed");

			// verify lock is released even on exception
			then(lockManager).should().releaseLocks(mockLock);
		}

		@Test
		@DisplayName("락 해제 전에 Hold를 설정한다")
		void Hold_설정_시점() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList()))
				.willReturn(mockLock);
			given(transactionService.createPendingReservationInTx(any(), anyLong(), anyString()))
				.willReturn(mockReservation);

			// when
			reservationService.createPendingReservation(validRequest, memberId);

			// then
			var inOrder = inOrder(transactionService, holdService, lockManager);
			inOrder.verify(transactionService).createPendingReservationInTx(any(), anyLong(), anyString());
			inOrder.verify(holdService).holdDates(anyLong(), any(LocalDate.class), any(LocalDate.class));
			inOrder.verify(lockManager).releaseLocks(mockLock);
		}

		@Test
		@DisplayName("락 키가 올바르게 생성되어 전달된다")
		void 락_키_생성_확인() {
			// given
			givenPublishedBookingWindow();
			given(holdService.isAnyDateHeld(anyLong(), any(LocalDate.class), any(LocalDate.class)))
				.willReturn(false);
			given(lockManager.acquireLocks(anyList()))
				.willReturn(mockLock);
			given(transactionService.createPendingReservationInTx(any(), anyLong(), anyString()))
				.willReturn(mockReservation);

			// when
			reservationService.createPendingReservation(validRequest, memberId);

			// then
			then(lockManager).should().acquireLocks(argThat(lockKeys -> {
				List<String> keys = (List<String>)lockKeys;
				return keys.size() == 2 &&
					keys.contains("LOCK:RESERVATION:1:" + validRequest.checkInDate()) &&
					keys.contains("LOCK:RESERVATION:1:" + validRequest.checkInDate().plusDays(1));
			}));
		}
	}

	private void givenPublishedBookingWindow() {
		givenPublishedBookingWindow(validRequest.accommodationId(), TIME_ZONE_ID, BOOKING_WINDOW);
	}

	private void givenPublishedBookingWindow(
		Long accommodationId,
		String timeZoneId,
		BookingWindow bookingWindow
	) {
		given(accommodationRepository.findBookingProjectionByIdAndStatus(
			accommodationId, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(new AccommodationBookingProjection(timeZoneId)));
		given(bookingWindowProvider.currentFor(timeZoneId)).willReturn(bookingWindow);
	}

	@Nested
	@DisplayName("예약 취소 테스트")
	class CancelReservationTest {

		@Test
		@DisplayName("예약 취소 시 transactionService에 위임된다")
		void 정상_취소_위임() {
			// given
			String reservationUid = UUID.randomUUID().toString();
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", 200_000L);

			// when
			reservationService.cancelReservation(reservationUid, cancelRequest, memberId);

			// then
			then(transactionService).should().cancelReservationInTx(reservationUid, cancelRequest, memberId);
		}
	}

	@Nested
	@DisplayName("취소 보상 테스트")
	class RevertCancellationTest {

		@Test
		@DisplayName("취소 보상 이벤트 수신 시 transactionService에 위임된다")
		void 정상_보상_위임() {
			// given
			String reservationUid = UUID.randomUUID().toString();
			ReservationEvent.ReservationCancellationRevertRequestedEvent event =
				new ReservationEvent.ReservationCancellationRevertRequestedEvent(
					reservationUid,
					"환불 처리 실패"
				);

			// when
			reservationService.revertCancellation(event);

			// then
			then(transactionService).should().revertCancellationInTx(reservationUid, "환불 처리 실패");
		}
	}

	@Nested
	@DisplayName("예약 취소 완료 테스트")
	class CompleteCancellationTest {

		@Test
		@DisplayName("결제 취소 완료 이벤트 수신 시 잠금 트랜잭션 처리에 위임한다")
		void 정상_취소완료_위임() {
			String reservationUid = UUID.randomUUID().toString();
			ReservationEvent.ReservationCancellationCompleteRequestedEvent event =
				new ReservationEvent.ReservationCancellationCompleteRequestedEvent(reservationUid);

			reservationService.completeCancellation(event);

			then(transactionService).should().completeCancellationInTx(reservationUid);
		}
	}
}

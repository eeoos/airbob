package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 테스트")
class ReservationServiceTest {

	@InjectMocks
	private ReservationService reservationService;

	@Mock
	private ReservationHoldService holdService;

	@Mock
	private ReservationLockManager lockManager;

	@Mock
	private ReservationTransactionService transactionService;

	@Mock
	private RLock mockLock;

	private ReservationRequest.Create validRequest;
	private Long memberId;
	private Reservation mockReservation;

	@BeforeEach
	void setUp() {
		memberId = 1L;
		validRequest = new ReservationRequest.Create(
			1L,
			LocalDate.of(2025, 1, 26),
			LocalDate.of(2025, 1, 28),
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
			then(lockManager).should().releaseLocks(mockLock);
		}

		@Test
		@DisplayName("Redis Hold가 존재하면 ReservationLockException이 발생한다")
		void 예외_Redis_Hold_존재() {
			// given
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
		@DisplayName("락 획득 실패 시 ReservationLockException이 발생한다")
		void 예외_락_획득_타임아웃() {
			// given
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
		@DisplayName("Hold 설정은 락 해제 후에 호출된다")
		void Hold_설정_시점() {
			// given
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
					keys.contains("LOCK:RESERVATION:1:2025-01-26") &&
					keys.contains("LOCK:RESERVATION:1:2025-01-27");
			}));
		}
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
	@DisplayName("예약 확정 테스트")
	class ConfirmReservationTest {

		@Test
		@DisplayName("결제 완료 이벤트 수신 시 transactionService에 위임된다")
		void 정상_확정_위임() {
			// given
			String reservationUid = UUID.randomUUID().toString();
			PaymentEvent.PaymentCompletedEvent event = new PaymentEvent.PaymentCompletedEvent(reservationUid);

			// when
			reservationService.confirmReservation(event);

			// then
			then(transactionService).should().confirmReservationInTx(reservationUid);
		}
	}

	@Nested
	@DisplayName("예약 만료 테스트")
	class ExpireReservationTest {

		@Test
		@DisplayName("결제 실패 이벤트 수신 시 transactionService에 위임된다")
		void 정상_만료_위임() {
			// given
			String reservationUid = UUID.randomUUID().toString();
			PaymentEvent.PaymentFailedEvent event = new PaymentEvent.PaymentFailedEvent(
				reservationUid,
				"결제 시간 초과"
			);

			// when
			reservationService.expireReservation(event);

			// then
			then(transactionService).should().expireReservationInTx(reservationUid, "결제 시간 초과");
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
}

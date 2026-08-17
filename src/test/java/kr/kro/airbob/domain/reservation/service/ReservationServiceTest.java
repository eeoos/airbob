package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.service.PaymentCancellationCommandService;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationService 테스트")
class ReservationServiceTest {

	@InjectMocks
	private ReservationService reservationService;

	@Mock
	private ReservationTransactionService transactionService;

	@Mock
	private PaymentCancellationCommandService cancellationCommandService;

	private ReservationRequest.Create validRequest;
	private Long memberId;
	private Reservation pendingReservation;

	@BeforeEach
	void setUp() {
		memberId = 1L;
		LocalDate checkInDate = LocalDate.of(2026, 8, 13);
		validRequest = new ReservationRequest.Create(1L, checkInDate, checkInDate.plusDays(2), 2);

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
		pendingReservation = Reservation.builder()
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
		@DisplayName("예약 생성의 동시성 검증과 저장은 권위 트랜잭션에 위임한다")
		void delegatesCreationToAuthoritativeTransaction() {
			given(transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.willReturn(pendingReservation);

			ReservationResponse.Ready result = reservationService.createPendingReservation(validRequest, memberId);

			assertThat(result.reservationUid()).isEqualTo(pendingReservation.getReservationUid().toString());
			assertThat(result.amount()).isEqualTo(pendingReservation.getTotalPrice());
			assertThat(result.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(result.paymentRequired()).isTrue();
			then(transactionService).should()
				.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");
		}

		@Test
		@DisplayName("0원 예약의 확정 결과도 권위 트랜잭션 결과를 그대로 응답한다")
		void returnsComplimentaryReservationResult() {
			Reservation complimentary = Reservation.builder()
				.id(pendingReservation.getId())
				.reservationUid(pendingReservation.getReservationUid())
				.accommodation(pendingReservation.getAccommodation())
				.guest(pendingReservation.getGuest())
				.status(ReservationStatus.CONFIRMED)
				.totalPrice(0L)
				.build();
			given(transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.willReturn(complimentary);

			ReservationResponse.Ready result = reservationService.createPendingReservation(validRequest, memberId);

			assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
			assertThat(result.paymentRequired()).isFalse();
		}

		@Test
		@DisplayName("잘못된 숙박 기간은 DB 트랜잭션 전에 거부한다")
		void rejectsInvalidStayBeforeTransaction() {
			LocalDate checkInDate = LocalDate.of(2026, 8, 13);
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				checkInDate,
				checkInDate,
				2
			);

			assertThatThrownBy(() -> reservationService.createPendingReservation(request, memberId))
				.isInstanceOf(InvalidReservationDateException.class);
			then(transactionService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("DB 중복 예약 판정은 변경하지 않고 호출자에게 전달한다")
		void propagatesReservationConflict() {
			given(transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.willThrow(new ReservationConflictException());

			assertThatThrownBy(() -> reservationService.createPendingReservation(validRequest, memberId))
				.isInstanceOf(ReservationConflictException.class);
		}
	}

	@Nested
	@DisplayName("예약 취소 테스트")
	class CancelReservationTest {

		@Test
		@DisplayName("예약 취소 시 결제 operation command에 위임된다")
		void delegatesCancellation() {
			String reservationUid = UUID.randomUUID().toString();
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", 200_000L);
			Cancellation expected = new Cancellation(
				UUID.randomUUID(),
				kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status.PENDING,
				"/api/v1/payment-operations/operation-id",
				false
			);
			given(cancellationCommandService.requestCancellation(reservationUid, cancelRequest, memberId))
				.willReturn(expected);

			Cancellation actual = reservationService.cancelReservation(
				reservationUid, cancelRequest, memberId);

			assertThat(actual).isEqualTo(expected);
			then(cancellationCommandService).should()
				.requestCancellation(reservationUid, cancelRequest, memberId);
		}
	}
}

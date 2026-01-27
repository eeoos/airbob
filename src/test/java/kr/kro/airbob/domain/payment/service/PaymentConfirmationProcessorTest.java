package kr.kro.airbob.domain.payment.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentConfirmationProcessor 테스트")
class PaymentConfirmationProcessorTest {

	@InjectMocks
	private PaymentConfirmationProcessor confirmationProcessor;

	@Mock
	private PaymentTransactionService paymentTransactionService;

	@Mock
	private ReservationTransactionService reservationTransactionService;

	@Mock
	private PaymentCompensationService paymentCompensationService;

	private UUID reservationUid;
	private Reservation reservation;
	private TossPaymentResponse tossResponse;

	@BeforeEach
	void setUp() {
		reservationUid = UUID.randomUUID();

		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();

		tossResponse = TossPaymentResponse.builder()
			.paymentKey("pk_test_123")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();
	}

	@Nested
	@DisplayName("processSuccess 테스트")
	class ProcessSuccessTest {

		@Test
		@DisplayName("정상 결제 승인 시 processSuccessfulPayment가 호출된다")
		void 정상_결제_승인_처리() {
			// given
			PaymentEvent.PgCallSucceededEvent event = new PaymentEvent.PgCallSucceededEvent(
				tossResponse, reservationUid.toString()
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(reservation);

			// when
			confirmationProcessor.processSuccess(event);

			// then
			then(paymentTransactionService).should().processSuccessfulPayment(tossResponse, reservation);
			then(paymentCompensationService).should(never()).compensateGhostPayment(anyString());
		}

		@Test
		@DisplayName("예약이 없을 때 Ghost Payment 보상 로직이 실행된다")
		void Ghost_Payment_감지_보상() {
			// given
			PaymentEvent.PgCallSucceededEvent event = new PaymentEvent.PgCallSucceededEvent(
				tossResponse, reservationUid.toString()
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(null);

			// when
			confirmationProcessor.processSuccess(event);

			// then
			then(paymentCompensationService).should().compensateGhostPayment("pk_test_123");
			then(paymentTransactionService).should(never()).processSuccessfulPayment(any(), any());
		}

		@Test
		@DisplayName("Ghost Payment 감지 후 정상 처리가 중단된다")
		void Ghost_Payment_감지시_정상처리_중단() {
			// given
			PaymentEvent.PgCallSucceededEvent event = new PaymentEvent.PgCallSucceededEvent(
				tossResponse, reservationUid.toString()
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(null);

			// when
			confirmationProcessor.processSuccess(event);

			// then
			then(paymentTransactionService).should(never()).processSuccessfulPayment(any(), any());
		}
	}

	@Nested
	@DisplayName("processFailure 테스트")
	class ProcessFailureTest {

		@Test
		@DisplayName("정상 결제 실패 시 processFailedPayment가 호출된다")
		void 정상_결제_실패_처리() {
			// given
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"pk_fail", reservationUid.toString(), 100_000
			);

			PaymentEvent.PgCallFailedEvent event = new PaymentEvent.PgCallFailedEvent(
				confirmRequest, reservationUid.toString(), "REJECT_CARD_PAYMENT", "잔액 부족"
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(reservation);

			// when
			confirmationProcessor.processFailure(event);

			// then
			then(paymentTransactionService).should().processFailedPayment(
				confirmRequest, reservation, "REJECT_CARD_PAYMENT", "잔액 부족"
			);
		}

		@Test
		@DisplayName("예약이 없을 때 IGNORE 처리되고 로직이 종료된다")
		void 예약_없을때_IGNORE() {
			// given
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"pk_fail", reservationUid.toString(), 100_000
			);

			PaymentEvent.PgCallFailedEvent event = new PaymentEvent.PgCallFailedEvent(
				confirmRequest, reservationUid.toString(), "REJECT_CARD_PAYMENT", "잔액 부족"
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(null);

			// when
			confirmationProcessor.processFailure(event);

			// then
			then(paymentTransactionService).should(never()).processFailedPayment(any(), any(), anyString(), anyString());
		}

		@Test
		@DisplayName("에러 코드와 메시지가 정확히 전달된다")
		void 에러정보_전달() {
			// given
			PaymentRequest.Confirm confirmRequest = new PaymentRequest.Confirm(
				"pk_fail", reservationUid.toString(), 100_000
			);

			PaymentEvent.PgCallFailedEvent event = new PaymentEvent.PgCallFailedEvent(
				confirmRequest, reservationUid.toString(), "EXCEED_MAX_AMOUNT", "거래금액 한도 초과"
			);

			given(reservationTransactionService.findByReservationUidNullable(reservationUid.toString()))
				.willReturn(reservation);

			// when
			confirmationProcessor.processFailure(event);

			// then
			then(paymentTransactionService).should().processFailedPayment(
				eq(confirmRequest), eq(reservation), eq("EXCEED_MAX_AMOUNT"), eq("거래금액 한도 초과")
			);
		}
	}
}

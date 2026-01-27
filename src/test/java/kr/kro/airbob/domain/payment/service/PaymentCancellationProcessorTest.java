package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.ZonedDateTime;
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

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.outbox.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancellationProcessor 테스트")
class PaymentCancellationProcessorTest {

	@InjectMocks
	private PaymentCancellationProcessor cancellationProcessor;

	@Mock
	private PaymentTransactionService paymentTransactionService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private SlackNotificationService slackNotificationService;

	@Captor
	private ArgumentCaptor<String> slackMessageCaptor;

	private UUID reservationUid;
	private Reservation reservation;
	private Payment payment;
	private TossPaymentResponse cancelResponse;

	@BeforeEach
	void setUp() {
		reservationUid = UUID.randomUUID();

		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();

		TossPaymentResponse originalResponse = TossPaymentResponse.builder()
			.paymentKey("pk_test_123")
			.orderId(reservationUid.toString())
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();

		payment = Payment.create(originalResponse, reservation);

		cancelResponse = TossPaymentResponse.builder()
			.status("CANCELED")
			.balanceAmount(0L)
			.build();
	}

	@Nested
	@DisplayName("processSuccess 테스트")
	class ProcessSuccessTest {

		@Test
		@DisplayName("취소 성공 시 processSuccessfulCancellation이 호출된다")
		void 취소_성공_처리() {
			// given
			PaymentEvent.PgCancelCallSucceededEvent event = new PaymentEvent.PgCancelCallSucceededEvent(
				cancelResponse, reservationUid.toString()
			);

			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			// when
			cancellationProcessor.processSuccess(event);

			// then
			then(paymentTransactionService).should().processSuccessfulCancellation(payment, cancelResponse);
		}

		@Test
		@DisplayName("Payment 조회 실패 시 PaymentNotFoundException이 발생한다")
		void Payment_조회_실패_예외() {
			// given
			PaymentEvent.PgCancelCallSucceededEvent event = new PaymentEvent.PgCancelCallSucceededEvent(
				cancelResponse, reservationUid.toString()
			);

			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> cancellationProcessor.processSuccess(event))
				.isInstanceOf(PaymentNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("processFailure 테스트")
	class ProcessFailureTest {

		@Test
		@DisplayName("취소 실패 시 FATAL 메시지와 함께 Slack 알림이 발송된다")
		void FATAL_로깅_Slack_알림() {
			// given
			PaymentEvent.PaymentCancellationRequestedEvent cancelRequest =
				new PaymentEvent.PaymentCancellationRequestedEvent(
					reservationUid.toString(), "고객 요청", 100_000L
				);

			PaymentEvent.PgCancelCallFailedEvent event = new PaymentEvent.PgCancelCallFailedEvent(
				cancelRequest, reservationUid.toString(), "NOT_CANCELABLE_PAYMENT", "취소 불가"
			);

			// when
			cancellationProcessor.processFailure(event);

			// then
			then(slackNotificationService).should().sendAlert(slackMessageCaptor.capture());
			String slackMessage = slackMessageCaptor.getValue();

			assertThat(slackMessage).contains("[FATAL]");
			assertThat(slackMessage).contains("수동 개입 필요");
			assertThat(slackMessage).contains(reservationUid.toString());
		}

		@Test
		@DisplayName("취소 실패 시 processFailedCancellationInTx가 호출된다")
		void 취소_실패_이벤트_발행() {
			// given
			PaymentEvent.PaymentCancellationRequestedEvent cancelRequest =
				new PaymentEvent.PaymentCancellationRequestedEvent(
					reservationUid.toString(), "고객 요청", 100_000L
				);

			PaymentEvent.PgCancelCallFailedEvent event = new PaymentEvent.PgCancelCallFailedEvent(
				cancelRequest, reservationUid.toString(), "NOT_CANCELABLE_PAYMENT", "취소 불가"
			);

			// when
			cancellationProcessor.processFailure(event);

			// then
			then(paymentTransactionService).should().processFailedCancellationInTx(
				reservationUid.toString(), "취소 불가"
			);
		}

		@Test
		@DisplayName("Slack 메시지에 에러 코드와 메시지가 포함된다")
		void Slack_메시지_에러정보_포함() {
			// given
			PaymentEvent.PaymentCancellationRequestedEvent cancelRequest =
				new PaymentEvent.PaymentCancellationRequestedEvent(
					reservationUid.toString(), "고객 요청", 100_000L
				);

			PaymentEvent.PgCancelCallFailedEvent event = new PaymentEvent.PgCancelCallFailedEvent(
				cancelRequest, reservationUid.toString(), "EXCEED_MAX_REFUND_DUE", "환불 기간 초과"
			);

			// when
			cancellationProcessor.processFailure(event);

			// then
			then(slackNotificationService).should().sendAlert(slackMessageCaptor.capture());
			String slackMessage = slackMessageCaptor.getValue();

			assertThat(slackMessage).contains("EXCEED_MAX_REFUND_DUE");
			assertThat(slackMessage).contains("환불 기간 초과");
		}
	}
}

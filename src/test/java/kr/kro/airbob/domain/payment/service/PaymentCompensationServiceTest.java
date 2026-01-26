package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.ZonedDateTime;
import java.util.List;
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
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.outbox.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCompensationService 테스트")
class PaymentCompensationServiceTest {

	@InjectMocks
	private PaymentCompensationService compensationService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private TossPaymentsAdapter tossPaymentsAdapter;

	@Mock
	private PaymentTransactionService paymentTransactionService;

	@Mock
	private SlackNotificationService slackNotificationService;

	@Captor
	private ArgumentCaptor<String> slackMessageCaptor;

	private UUID reservationUid;
	private Reservation reservation;
	private Payment payment;

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
	}

	@Nested
	@DisplayName("compensate 테스트")
	class CompensateTest {

		@Test
		@DisplayName("정상 보상 처리 시 cancelPayment가 호출된다")
		void 정상_보상_처리() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensate(reservationUid.toString());

			// then
			then(tossPaymentsAdapter).should().cancelPayment(
				eq("pk_test_123"),
				contains("Saga 보상"),
				isNull()
			);
		}

		@Test
		@DisplayName("보상 처리 시 processCompensationInTx가 호출된다")
		void processCompensationInTx_호출() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensate(reservationUid.toString());

			// then
			then(paymentTransactionService).should().processCompensationInTx(payment, cancelResponse);
		}

		@Test
		@DisplayName("이미 CANCELED 상태면 보상을 스킵한다")
		void 이미_CANCELED_스킵() {
			// given
			TossPaymentResponse canceledResponse = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.balanceAmount(0L)
				.method("카드")
				.status("CANCELED")
				.approvedAt(ZonedDateTime.now())
				.build();

			Payment canceledPayment = Payment.create(canceledResponse, reservation);

			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(canceledPayment));

			// when
			compensationService.compensate(reservationUid.toString());

			// then
			then(tossPaymentsAdapter).should(never()).cancelPayment(anyString(), anyString(), any());
		}

		@Test
		@DisplayName("이미 PARTIAL_CANCELED 상태면 보상을 스킵한다")
		void 이미_PARTIAL_CANCELED_스킵() {
			// given
			TossPaymentResponse partialCanceledResponse = TossPaymentResponse.builder()
				.paymentKey("pk_test_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.balanceAmount(50_000L)
				.method("카드")
				.status("PARTIAL_CANCELED")
				.approvedAt(ZonedDateTime.now())
				.build();

			Payment partialCanceledPayment = Payment.create(partialCanceledResponse, reservation);

			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(partialCanceledPayment));

			// when
			compensationService.compensate(reservationUid.toString());

			// then
			then(tossPaymentsAdapter).should(never()).cancelPayment(anyString(), anyString(), any());
		}

		@Test
		@DisplayName("결제 정보를 찾을 수 없으면 PaymentNotFoundException이 발생한다")
		void 결제정보_없음_예외() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> compensationService.compensate(reservationUid.toString()))
				.isInstanceOf(PaymentNotFoundException.class);
		}

		@Test
		@DisplayName("TossPaymentException 발생 시 예외가 재throw된다")
		void TossPaymentException_재throw() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			TossPaymentException tossException = new TossPaymentException(
				PaymentCancelErrorCode.NOT_CANCELABLE_PAYMENT
			);
			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willThrow(tossException);

			// when & then
			assertThatThrownBy(() -> compensationService.compensate(reservationUid.toString()))
				.isInstanceOf(TossPaymentException.class);
		}

		@Test
		@DisplayName("일반 Exception 발생 시 예외가 재throw된다")
		void 일반_Exception_재throw() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willThrow(new RuntimeException("네트워크 오류"));

			// when & then
			assertThatThrownBy(() -> compensationService.compensate(reservationUid.toString()))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("네트워크 오류");
		}

		@Test
		@DisplayName("cancelReason에 'Saga 보상' 문구가 포함된다")
		void cancelReason_Saga_보상_포함() {
			// given
			given(paymentRepository.findByReservationReservationUid(reservationUid))
				.willReturn(Optional.of(payment));

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensate(reservationUid.toString());

			// then
			then(tossPaymentsAdapter).should().cancelPayment(
				anyString(),
				argThat(reason -> reason.contains("Saga 보상")),
				isNull()
			);
		}
	}

	@Nested
	@DisplayName("compensateGhostPayment 테스트")
	class CompensateGhostPaymentTest {

		@Test
		@DisplayName("유령 결제 감지 시 CRITICAL 알림이 발송된다")
		void CRITICAL_알림_발송() {
			// given
			String paymentKey = "pk_ghost_123";

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensateGhostPayment(paymentKey);

			// then
			then(slackNotificationService).should(atLeastOnce()).sendAlert(slackMessageCaptor.capture());
			String firstAlert = slackMessageCaptor.getAllValues().get(0);

			assertThat(firstAlert).contains("[CRITICAL]");
			assertThat(firstAlert).contains("유령 결제");
			assertThat(firstAlert).contains(paymentKey);
		}

		@Test
		@DisplayName("전액 환불 요청 시 cancelAmount가 null로 전달된다")
		void 전액_환불_cancelAmount_null() {
			// given
			String paymentKey = "pk_ghost_123";

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensateGhostPayment(paymentKey);

			// then
			then(tossPaymentsAdapter).should().cancelPayment(
				eq(paymentKey),
				anyString(),
				isNull()  // 전액 환불
			);
		}

		@Test
		@DisplayName("환불 성공 시 성공 알림이 발송된다")
		void 환불_성공_알림() {
			// given
			String paymentKey = "pk_ghost_123";

			TossPaymentResponse cancelResponse = TossPaymentResponse.builder()
				.status("CANCELED")
				.balanceAmount(0L)
				.build();

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willReturn(cancelResponse);

			// when
			compensationService.compensateGhostPayment(paymentKey);

			// then
			then(slackNotificationService).should(atLeastOnce()).sendAlert(slackMessageCaptor.capture());
			List<String> alerts = slackMessageCaptor.getAllValues();

			boolean hasSuccessMessage = alerts.stream()
				.anyMatch(msg -> msg.contains("[COMPENSATION]") && msg.contains("성공"));
			assertThat(hasSuccessMessage).isTrue();
		}

		@Test
		@DisplayName("환불 실패 시 FATAL 알림이 발송된다")
		void 환불_실패_FATAL_알림() {
			// given
			String paymentKey = "pk_ghost_123";

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willThrow(new RuntimeException("환불 실패"));

			// when
			compensationService.compensateGhostPayment(paymentKey);

			// then
			then(slackNotificationService).should(atLeastOnce()).sendAlert(slackMessageCaptor.capture());
			List<String> alerts = slackMessageCaptor.getAllValues();

			boolean hasFatalMessage = alerts.stream()
				.anyMatch(msg -> msg.contains("[FATAL]") && msg.contains("실패"));
			assertThat(hasFatalMessage).isTrue();
		}

		@Test
		@DisplayName("환불 실패 시에도 예외가 발생하지 않는다 (Slack으로 알림만 발송)")
		void 환불_실패_예외_미발생() {
			// given
			String paymentKey = "pk_ghost_123";

			given(tossPaymentsAdapter.cancelPayment(anyString(), anyString(), isNull()))
				.willThrow(new RuntimeException("환불 실패"));

			// when & then - 예외 발생하지 않음
			assertThatCode(() -> compensationService.compensateGhostPayment(paymentKey))
				.doesNotThrowAnyException();
		}
	}
}

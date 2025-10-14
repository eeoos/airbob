package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.PaymentTransactionService;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancellationService {

	private final PaymentRepository paymentRepository;
	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final PaymentTransactionService paymentTransactionService;

	public void process(PaymentEvent.PaymentCancellationRequestedEvent event) {
		String reservationUid = event.reservationUid();
		log.info("[결제 취소 워커 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				event.cancelReason(),
				event.cancelAmount()
			);
			paymentTransactionService.processSuccessfulCancellation(payment, response);
		} catch (TossPaymentException e) {
			log.error("[결제 취소 실패] Toss API 오류. UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, e.getMessage());
		} catch (ResourceAccessException e) {
			log.error("[결제 취소 실패] 재시도 소진. UID: {}. 수동 개입 필요.", reservationUid, e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, "외부 시스템 오류");
		} catch (Exception e) {
			log.error("[결제 취소 실패] 알 수 없는 예외. UID: {}", reservationUid, e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, e.getMessage());
		}
	}
}

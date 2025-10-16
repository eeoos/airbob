package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationProcessor {

	private final PaymentTransactionService paymentTransactionService;
	private final PaymentRepository paymentRepository;
	private final SlackNotificationService slackNotificationService;

	public void processSuccess(PaymentEvent.PgCancelCallSucceededEvent event) {
		String reservationUid = event.reservationUid();
		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);

		paymentTransactionService.processSuccessfulCancellation(payment, event.response());
		log.info("PG사 API 취소 성공 DB 처리 완료. Reservation UID={}", reservationUid);
	}

	public void processFailure(PaymentEvent.PgCancelCallFailedEvent event) {
		String reservationUid = event.reservationUid();
		String errorMessage = String.format(
			"🚨 [FATAL] PG사 결제 취소 최종 실패! 수동 개입 필요! Reservation UID: %s, ErrorCode: %s, ErrorMessage: %s",
			reservationUid, event.errorCode(), event.errorMessage()
		);
		log.error(errorMessage);
		slackNotificationService.sendAlert(errorMessage);

		paymentTransactionService.processFailedCancellationInTx(reservationUid, event.errorMessage());
	}
}

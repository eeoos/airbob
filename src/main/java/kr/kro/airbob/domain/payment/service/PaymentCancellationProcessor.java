package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationProcessor {

	private final PaymentTransactionService paymentTransactionService;
	private final SlackNotificationService slackNotificationService;

	public void processSuccess(PaymentEvent.PgCancelCallSucceededEvent event) {
		String reservationUid = event.reservationUid();
		paymentTransactionService.processSuccessfulCancellation(reservationUid, event.response());
		if (PaymentStatus.from(event.response().getStatus()) != PaymentStatus.CANCELED
			|| !Long.valueOf(0L).equals(event.response().getBalanceAmount())) {
			String errorMessage = String.format(
				"🚨 [FATAL] PG 취소 응답이 전액 환불 상태가 아닙니다. 수동 확인 필요! Reservation UID: %s, Status: %s, Balance: %s",
				reservationUid, event.response().getStatus(), event.response().getBalanceAmount());
			log.error(errorMessage);
			slackNotificationService.sendAlert(errorMessage);
		}
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

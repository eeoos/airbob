package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmationProcessor {

	private final PaymentTransactionService paymentTransactionService;
	private final ReservationTransactionService reservationTransactionService;
	private final PaymentCompensationService paymentCompensationService;

	public void processSuccess(PaymentEvent.PgCallSucceededEvent event) {
		Reservation reservation = reservationTransactionService.findByReservationUidNullable(event.reservationUid());

		if (reservation == null) {
			log.error("[CRITICAL] PG_CALL_SUCCEEDED 처리 중 예약을 찾을 수 없음! Order ID: {}. 유령 결제 보상 로직을 시작합니다.", event.reservationUid());
			paymentCompensationService.compensateGhostPayment(event.response().getPaymentKey());
			return;
		}

		paymentTransactionService.processSuccessfulPayment(event.response(), reservation);
		log.info("PG사 API 승인 성공 DB 처리 완료. Order ID={}", event.reservationUid());
	}

	public void processFailure(PaymentEvent.PgCallFailedEvent event) {
		Reservation reservation = reservationTransactionService.findByReservationUidNullable(event.reservationUid());

		if (reservation == null) {
			log.warn("[IGNORE] PG_CALL_FAILED 처리 중 예약을 찾을 수 없음. Order ID: {}. 해당 결제 시도는 무시됩니다.", event.reservationUid());
			return;
		}

		paymentTransactionService.processFailedPayment(event.request(), reservation, event.errorCode(), event.errorMessage());
		log.info("PG사 API 승인 실패 DB 처리 완료. Order ID={}", event.reservationUid());
	}
}

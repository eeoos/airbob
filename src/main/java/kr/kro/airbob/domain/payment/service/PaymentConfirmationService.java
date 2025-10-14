package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.service.PaymentTransactionService;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentConfirmationService {

	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final PaymentTransactionService paymentTransactionService;

	private final SlackNotificationService slackNotificationService;

	private final ReservationRepository reservationRepository;

	public void processPaymentConfirmation(PaymentRequest.Confirm event) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.orderId()))
			.orElse(null);

		if (reservation == null) {
			handleOrphanPayment(event);
			return;
		}

		String reservationUid = reservation.getReservationUid().toString();
		log.info("[결제 워커 시작]: Reservation UID {}", reservationUid);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.confirmPayment(
				event.paymentKey(),
				event.orderId(),
				event.amount()
			);

			if (PaymentStatus.DONE.toString().equalsIgnoreCase(response.getStatus())) {
				// 성공 시 트랜잭션 서비스 호출 (ack는 컨슈머가 이미 처리)
				paymentTransactionService.processSuccessfulPayment(response, reservation);
			} else {
				String reason = response.getFailure() != null ? response.getFailure().getMessage() :
					"결제 실패 (상태: " + response.getStatus() + ")";
				paymentTransactionService.processFailedPayment(event, reservation, response.getFailure().getCode(),
					reason);
			}
		} catch (Exception e) {
			log.error("[결제 워커 실패] Reservation UID: {}. 에러: {}", reservationUid, e.getMessage(), e);
			// 실패 시 트랜잭션 서비스 호출
			String errorCode =
				(e instanceof TossPaymentException) ? ((TossPaymentException)e).getErrorCode().name() : "WORKER_ERROR";
			paymentTransactionService.processFailedPayment(event, reservation, errorCode, e.getMessage());
		}
	}

	private void handleOrphanPayment(PaymentRequest.Confirm event) {
		String errorMessage = String.format(
			"[CRITICAL] 결제 승인 중 예약을 찾을 수 없음! Order ID: %s, Payment Key: %s. 즉시 상태 확인 및 환불을 시도합니다.",
			event.orderId(), event.paymentKey()
		);
		log.error(errorMessage);
		slackNotificationService.sendAlert(errorMessage);

		try {
			TossPaymentResponse paymentStatus = tossPaymentsAdapter.getPaymentByPaymentKey(event.paymentKey());

			if (PaymentStatus.DONE.name().equalsIgnoreCase(paymentStatus.getStatus())) {
				log.warn("유령 결제 확인. 즉시 전액 환불을 시도합니다. Payment Key: {}", event.paymentKey());
				tossPaymentsAdapter.cancelPayment(event.paymentKey(), "시스템 오류: 예약 정보 불일치", null);

				String successMessage = String.format(
					"[COMPENSATION] 유령 결제 자동 환불 성공. Payment Key: %s", event.paymentKey()
				);
				log.info(successMessage);
				slackNotificationService.sendAlert(successMessage);
			} else {
				String notDoneMessage = String.format(
					"[INFO] 유령 결제 상태 확인. 아직 승인되지 않은 상태(%s). Payment Key: %s", paymentStatus.getStatus(), event.paymentKey()
				);
				log.info(notDoneMessage);
				slackNotificationService.sendAlert(notDoneMessage);
			}
		} catch (Exception e) {
			String failureMessage = String.format(
				"🚨 [FATAL] 유령 결제 자동 환불/조회 실패! 수동 개입 필요! Payment Key: %s, Error: %s",
				event.paymentKey(), e.getMessage()
			);
			log.error(failureMessage, e);
			slackNotificationService.sendAlert(failureMessage);
		}
	}

}

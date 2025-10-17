package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.service.PaymentTransactionService;
import kr.kro.airbob.domain.payment.service.TossPaymentsAdapter;
import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCompensationService {
	private final PaymentRepository paymentRepository;
	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final PaymentTransactionService paymentTransactionService;

	private final SlackNotificationService slackNotificationService;

	public void compensate(String reservationUid) {
		log.warn("[DLQ 보상 트랜잭션 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(() -> {
				log.error("[DLQ-FATAL] 보상 트랜잭션 실패: reservationUid {}에 해당하는 결제 정보를 찾을 수 없음. 수동 확인 필요", reservationUid);
				return new PaymentNotFoundException();
			});

		if (payment.getStatus() == PaymentStatus.CANCELED || payment.getStatus() == PaymentStatus.PARTIAL_CANCELED) {
			log.warn("[DLQ 보상 트랜잭션] 이미 취소된 결제. PaymentKey: {}", payment.getPaymentKey());
			return;
		}

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				"시스템 오류로 인한 예약 확정 실패 (Saga 보상)",
				null
			);

			paymentTransactionService.processCompensationInTx(payment, response);

		} catch (TossPaymentException e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션(결제 취소) 중 Toss API 오류 발생. Reservation UID: {}, Code: {}. 수동 개입 필요.",
				reservationUid, e.getErrorCode().name(), e);
			throw e;
		} catch (Exception e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션 중 알 수 없는 오류 발생. Reservation UID: {}. 수동 개입 필요", reservationUid, e);
			throw e;
		}
	}

	public void compensateGhostPayment(String paymentKey) {
		String errorMessage = String.format(
			"[CRITICAL] 유령 결제 발생! Order ID에 해당하는 예약 없음. 즉시 환불을 시도합니다. Payment Key: %s",
			paymentKey
		);
		log.error(errorMessage);
		slackNotificationService.sendAlert(errorMessage);

		try {
			// 전액 환불
			tossPaymentsAdapter.cancelPayment(paymentKey, "시스템 오류: 예약 정보 불일치", null);

			String successMessage = String.format(
				"[COMPENSATION] 유령 결제 자동 환불 성공. Payment Key: %s", paymentKey
			);
			log.info(successMessage);
			slackNotificationService.sendAlert(successMessage);

		} catch (Exception e) {
			String failureMessage = String.format(
				"🚨 [FATAL] 유령 결제 자동 환불 실패! 수동 개입 필요! Payment Key: %s, Error: %s",
				paymentKey, e.getMessage()
			);
			log.error(failureMessage, e);
			slackNotificationService.sendAlert(failureMessage);
		}
	}
}

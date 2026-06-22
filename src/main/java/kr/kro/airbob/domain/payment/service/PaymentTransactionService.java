package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;

	private final OutboxEventPublisher outboxEventPublisher;

	// 결제 성공 시 DB 작업 처리하는 트랜잭션 메서드
	@Transactional
	public void processSuccessfulPayment(TossPaymentResponse response, Reservation reservation) {
		Payment payment = Payment.create(response, reservation);
		paymentRepository.save(payment);

		// 거래 원장에 승인 이벤트 기록 (payment_id 연결)
		paymentTransactionRepository.save(PaymentTransaction.confirm(response, reservation, payment));

		outboxEventPublisher.save(
			PAYMENT_COMPLETED,
			new PaymentEvent.PaymentCompletedEvent(reservation.getReservationUid().toString())
		);
	}

	// 결제 실패 시 DB 작업 처리하는 트랜잭션 메서드
	@Transactional
	public void processFailedPayment(PaymentRequest.Confirm event, Reservation reservation, String code, String message) {
		paymentTransactionRepository.save(PaymentTransaction.fail(event, reservation, code, message));
		handlePaymentFailure(reservation.getReservationUid().toString(), message);
	}

	@Transactional
	public void processSuccessfulCancellation(Payment payment, TossPaymentResponse response) {
		applyCancellation(payment, response);
		log.info("[결제 취소 처리 완료]: PaymentKey {}의 상태 {} 변경 완료", payment.getPaymentKey(), payment.getStatus());
	}

	@Transactional
	public void processCompensationInTx(Payment payment, TossPaymentResponse response) {
		applyCancellation(payment, response);
		log.info("[DLQ 보상 트랜잭션 완료]: PaymentKey {} 결제 취소 DB 업데이트 완료.", payment.getPaymentKey());
	}

	@Transactional
	public void processFailedCancellationInTx(String reservationUid, String reason) {
		outboxEventPublisher.save(
			PAYMENT_CANCELLATION_FAILED,
			new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, reason)
		);
	}

	// 취소: Payment 현재 상태(상태/잔액) 갱신 + 거래 원장에 취소 이벤트 기록
	private void applyCancellation(Payment payment, TossPaymentResponse response) {
		payment.updateOnCancel(response);
		if (response.getCancels() != null && !response.getCancels().isEmpty()) {
			paymentTransactionRepository.save(
				PaymentTransaction.cancel(response.getCancels().getLast(), payment));
		}
	}

	private void handlePaymentFailure(String reservationUid, String reason) {
		log.error("[결제 실패]: Reservation UID {} 결제 실패. 사유: {}", reservationUid, reason);

		outboxEventPublisher.save(
			PAYMENT_FAILED,
			new PaymentEvent.PaymentFailedEvent(reservationUid, reason)
		);
	}
}

package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.PAYMENT_CANCELLATION_COMPLETED;
import static kr.kro.airbob.outbox.EventType.PAYMENT_CANCELLATION_FAILED;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancellationTransactionService {

	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final OutboxEventPublisher outboxEventPublisher;

	@Transactional
	public void processSuccessfulCancellation(String reservationUid, TossPaymentResponse response) {
		UUID reservationUuid = UUID.fromString(reservationUid);
		Payment payment = paymentRepository
			.findByReservationReservationUidWithLock(reservationUuid)
			.orElseThrow(PaymentNotFoundException::new);
		validateCancellationResponse(payment, response, reservationUid);
		PaymentStatus responseStatus = PaymentStatus.from(response.getStatus());
		if (payment.getStatus() == responseStatus
			&& Objects.equals(payment.getBalanceAmount(), response.getBalanceAmount())) {
			log.info("[결제 취소 처리-SKIP] 이미 반영된 취소 성공 결과입니다. Reservation UID={}", reservationUid);
			return;
		}
		applyCancellation(payment, response);

		if (payment.getStatus() == PaymentStatus.CANCELED
			&& Long.valueOf(0L).equals(payment.getBalanceAmount())) {
			outboxEventPublisher.save(
				PAYMENT_CANCELLATION_COMPLETED,
				new PaymentEvent.PaymentCancellationCompletedEvent(reservationUid)
			);
		} else {
			outboxEventPublisher.save(
				PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(
					reservationUid, "예약 취소 요청이 전액 환불로 완료되지 않았습니다."));
		}
		log.info("[결제 취소 처리 완료]: PaymentKey {}의 상태 {} 변경 완료", payment.getPaymentKey(), payment.getStatus());
	}

	@Transactional
	public void processFailedCancellationInTx(String reservationUid, String reason) {
		outboxEventPublisher.save(
			PAYMENT_CANCELLATION_FAILED,
			new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, reason)
		);
	}

	private void applyCancellation(Payment payment, TossPaymentResponse response) {
		payment.updateOnCancel(response);
		if (response.getCancels() != null && !response.getCancels().isEmpty()) {
			paymentTransactionRepository.save(
				PaymentTransaction.cancel(response.getCancels().getLast(), payment));
		}
	}

	private void validateCancellationResponse(
		Payment payment,
		TossPaymentResponse response,
		String reservationUid
	) {
		boolean identityMatches = Objects.equals(payment.getPaymentKey(), response.getPaymentKey())
			&& Objects.equals(payment.getOrderId(), response.getOrderId())
			&& Objects.equals(reservationUid, response.getOrderId())
			&& Objects.equals(payment.getAmount(), response.getTotalAmount());
		Long balanceAmount = response.getBalanceAmount();
		boolean balanceIsValid = balanceAmount != null
			&& balanceAmount >= 0L
			&& balanceAmount <= payment.getAmount();

		if (!identityMatches || !balanceIsValid) {
			throw new IllegalStateException("PG 취소 응답이 기존 결제 정보와 일치하지 않습니다.");
		}
	}
}

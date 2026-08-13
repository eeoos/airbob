package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.*;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final ReservationRepository reservationRepository;

	private final OutboxEventPublisher outboxEventPublisher;

	// 결제 성공 시 DB 작업 처리하는 트랜잭션 메서드
	@Transactional
	public void processSuccessfulPayment(TossPaymentResponse response, Reservation reservation) {
		UUID reservationUid = reservation.getReservationUid();
		Reservation lockedReservation = reservationRepository.findByReservationUidWithLock(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		validateSuccessfulPaymentResponse(response, lockedReservation);

		Payment existingPayment = paymentRepository
			.findByReservationReservationUidWithLock(reservationUid)
			.orElse(null);
		if (existingPayment != null) {
			if (isSameApprovedPayment(existingPayment, response)) {
				log.info("[결제 성공 처리-SKIP] 이미 반영된 승인 결과입니다. Reservation UID={}", reservationUid);
				return;
			}
			throw new IllegalStateException("예약에 이미 다른 결제 정보가 존재합니다.");
		}

		Payment payment = Payment.create(response, lockedReservation);
		paymentRepository.save(payment);

		// 거래 원장에 승인 이벤트 기록 (payment_id 연결)
		paymentTransactionRepository.save(PaymentTransaction.confirm(response, lockedReservation, payment));

		outboxEventPublisher.save(
			PAYMENT_COMPLETED,
			new PaymentEvent.PaymentCompletedEvent(reservationUid.toString())
		);
	}

	// 결제 실패 시 DB 작업 처리하는 트랜잭션 메서드
	@Transactional
	public void processFailedPayment(PaymentRequest.Confirm event, Reservation reservation, String code, String message) {
		paymentTransactionRepository.save(PaymentTransaction.fail(event, reservation, code, message));
		handlePaymentFailure(reservation.getReservationUid().toString(), message);
	}

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
	public void processCompensationInTx(String reservationUid, TossPaymentResponse response) {
		Payment payment = paymentRepository
			.findByReservationReservationUidWithLock(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);
		validateCancellationResponse(payment, response, reservationUid);
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

	private void validateSuccessfulPaymentResponse(
		TossPaymentResponse response,
		Reservation reservation
	) {
		boolean matchesReservation = response != null
			&& Objects.equals(reservation.getReservationUid().toString(), response.getOrderId())
			&& Objects.equals(reservation.getTotalPrice(), response.getTotalAmount())
			&& PaymentStatus.DONE == PaymentStatus.from(response.getStatus())
			&& response.getPaymentKey() != null
			&& !response.getPaymentKey().isBlank()
			&& response.getApprovedAt() != null
			&& response.getMethod() != null
			&& !response.getMethod().isBlank();
		if (!matchesReservation) {
			throw new IllegalStateException("PG 승인 응답이 예약 정보와 일치하지 않습니다.");
		}
	}

	private boolean isSameApprovedPayment(Payment payment, TossPaymentResponse response) {
		return Objects.equals(payment.getPaymentKey(), response.getPaymentKey())
			&& Objects.equals(payment.getOrderId(), response.getOrderId())
			&& Objects.equals(payment.getAmount(), response.getTotalAmount())
			&& payment.getStatus() == PaymentStatus.DONE;
	}

	private void handlePaymentFailure(String reservationUid, String reason) {
		log.error("[결제 실패]: Reservation UID {} 결제 실패. 사유: {}", reservationUid, reason);

		outboxEventPublisher.save(
			PAYMENT_FAILED,
			new PaymentEvent.PaymentFailedEvent(reservationUid, reason)
		);
	}
}

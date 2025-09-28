package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.*;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentConfirmException;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentRepository paymentRepository;
	private final ReservationRepository reservationRepository;
	private final PaymentAttemptRepository paymentAttemptRepository;

	private final OutboxEventPublisher outboxEventPublisher;
	private final TossPaymentsAdapter tossPaymentsAdapter;

	@Transactional
	public void processPaymentConfirmation(PaymentRequest.Confirm event) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.orderId()))
			.orElseThrow(ReservationNotFoundException::new);

		String reservationUid = reservation.getReservationUid().toString();
		log.info("[결제 승인 처리 시작]: Reservation UID {}", reservationUid);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.confirmPayment(
				event.paymentKey(),
				event.orderId(),
				event.amount()
			);
			processPaymentResponse(response, reservation);
		} catch (TossPaymentConfirmException e) {
			log.error("[결제 실패]: Reservation UID {} 처리 중 에러 발생. Code: {}", reservationUid, e.getErrorCode().name());
			saveFailedAttempt(event, reservation, e.getErrorCode().name(), e.getMessage());

		} catch (Exception e) {
			log.error("[결제 실패]: Reservation UID {} 처리 중 알 수 없는 예외 발생", reservationUid, e);
			saveFailedAttempt(event, reservation, "UNKNOWN_ERROR", e.getMessage());
			handlePaymentFailure(reservationUid, "알 수 없는 오류가 발생했습니다.");
		}
	}

	@Transactional
	public void processPaymentCancellation(ReservationEvent.ReservationCancelledEvent event) {
		String reservationUid = event.reservationUid();
		log.info("[결제 취소 처리]: Reservation UID {}", reservationUid);

		try {
			Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
				.orElseThrow(PaymentNotFoundException::new);

			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				event.cancelReason(),
				event.cancelAmount()
			);

			payment.updateOnCancel(response);

			log.info("[결제 취소 처리 완료]: PaymentKey {}의 상태 {} 변경 완료", payment.getPaymentKey(), payment.getStatus());

		} catch (Exception e) {
			log.error("[결제 취소 처리 실패]: Reservation UID {} 처리 중 예외 발생", reservationUid, e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, e.getMessage())
			);
		}
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByPaymentKey(String paymentKey) {
		TossPaymentResponse response = tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey);

		Payment payment = paymentRepository.findByPaymentKey(paymentKey)
			.orElseThrow(PaymentNotFoundException::new);

		return PaymentResponse.PaymentInfo.from(payment);
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByOrderId(String orderId) {
		TossPaymentResponse response = tossPaymentsAdapter.getPaymentByOrderId(orderId);

		Payment payment = paymentRepository.findByOrderId(orderId)
			.orElseThrow(PaymentNotFoundException::new);

		return PaymentResponse.PaymentInfo.from(payment);
	}

	@Transactional
	public TossPaymentResponse issueVirtualAccount(String reservationUid, PaymentRequest.VirtualAccount request) {
		log.info("[가상계좌 발급]: Reservation UID {}", reservationUid);

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		TossPaymentResponse response = tossPaymentsAdapter.issueVirtualAccount(reservation, request.bankCode(),
			request.customerName());

		PaymentAttempt attempt = PaymentAttempt.create(response, reservation);
		paymentAttemptRepository.save(attempt);

		log.info("[가상계좌 발급 완료]: Reservation UID {}", reservationUid);

		return response;
	}

	private void processPaymentResponse(TossPaymentResponse response, Reservation reservation) {
		PaymentAttempt attempt = PaymentAttempt.create(response, reservation);
		paymentAttemptRepository.save(attempt);

		if (PaymentStatus.DONE.toString().equalsIgnoreCase(response.getStatus())) {
			Payment payment = Payment.create(response, reservation);
			paymentRepository.save(payment);

			log.info("[결제 성공]: Reservation UID {} 의 결제가 성공적으로 처리되었습니다.", reservation.getReservationUid());

			outboxEventPublisher.save(
				PAYMENT_SUCCEEDED,
				new PaymentEvent.PaymentSucceededEvent(reservation.getReservationUid().toString())
			);
		} else {
			String reason = response.getFailure() != null ? response.getFailure().getMessage() : "결제 실패 (상태: " + response.getStatus() + ")";
			handlePaymentFailure(reservation.getReservationUid().toString(), reason);
		}
	}

	private void saveFailedAttempt(PaymentRequest.Confirm event, Reservation reservation, String code, String message) {
		PaymentAttempt failedAttempt = PaymentAttempt.createFailedAttempt(event.paymentKey(), event.orderId(),
			Long.valueOf(event.amount()), reservation, code, message);
		paymentAttemptRepository.save(failedAttempt);
	}

	private void handlePaymentFailure(String reservationUid, String reason) {
		log.error("[결제 실패]: Reservation UID {} 결제 실패. 사유: {}", reservationUid, reason);

		outboxEventPublisher.save(
			PAYMENT_FAILED,
			new PaymentEvent.PaymentFailedEvent(reservationUid, reason)
		);
	}
}

package kr.kro.airbob.domain.payment.service;

import static kr.kro.airbob.outbox.EventType.*;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
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
		} catch (TossPaymentException e) {
			log.error("[결제 최종 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name());
			saveFailedAttempt(event, reservation, e.getErrorCode().name(), e.getMessage());
			handlePaymentFailure(reservationUid, "결제 승인에 실패했습니다.");
		} catch (ResourceAccessException e) {
			log.error("[결제 최종 실패] 재시도 소진. Reservation UID: {}. 수동 확인 필요.", reservationUid, e);
			saveFailedAttempt(event, reservation, "RETRY_EXHAUSTED", e.getMessage());
			handlePaymentFailure(reservationUid, "결제 시스템 오류로 인해 실패했습니다. 잠시 후 다시 시도해주세요.");
		} catch (Exception e) {
			log.error("[결제 최종 실패] 알 수 없는 예외 발생. Reservation UID: {}", reservationUid, e);
			saveFailedAttempt(event, reservation, "UNKNOWN_ERROR", e.getMessage());
			handlePaymentFailure(reservationUid, "알 수 없는 오류가 발생했습니다.");
		}
	}

	@Transactional
	public void processPaymentCancellation(ReservationEvent.ReservationCancelledEvent event) {
		String reservationUid = event.reservationUid();
		log.info("[결제 취소 처리]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				event.cancelReason(),
				event.cancelAmount()
			);
			payment.updateOnCancel(response);
			log.info("[결제 취소 처리 완료]: PaymentKey {}의 상태 {} 변경 완료", payment.getPaymentKey(), payment.getStatus());

		} catch (TossPaymentException e) {
			log.error("[결제 취소 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, e.getMessage())
			);
		} catch (ResourceAccessException e) {
			log.error("[결제 취소 실패] 재시도 소진. Reservation UID: {}. 수동 개입 필요.", reservationUid, e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, "결제 취소 중 외부 시스템 오류 발생")
			);
		} catch (Exception e) {
			log.error("[결제 취소 처리 실패]: Reservation UID {} 처리 중 알 수 없는 예외 발생", reservationUid, e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, e.getMessage())
			);
		}
	}

	@Transactional
	public void compensatePayment(ReservationEvent.ReservationConfirmationFailedEvent event) {
		String reservationUid = event.reservationUid();
		log.warn("[결제 보상 트랜잭션 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);

		if (payment.getStatus() == PaymentStatus.CANCELED) {
			log.warn("[결제 보상 트랜잭션] 이미 취소된 결제입니다. PaymentKey: {}", payment.getPaymentKey());
			return;
		}

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				"시스템 오류로 인한 예약 확정 실패",
				null
			);
			payment.updateOnCancel(response);
			log.info("[결제 보상 트랜잭션 완료]: PaymentKey {}의 결제가 취소되었습니다.", payment.getPaymentKey());
		} catch (TossPaymentException e) {
			log.error("[결제 보상 트랜잭션 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}. 수동 개입 필요.", reservationUid, e.getErrorCode().name(), e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, "보상 트랜잭션 실패 (재시도 불가): " + e.getMessage())
			);
		} catch (ResourceAccessException e) {
			log.error("[결제 보상 트랜잭션 실패] 재시도 소진. Reservation UID: {}. 수동 개입 필요.", reservationUid, e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, "보상 트랜잭션 실패 (외부 시스템 오류)")
			);
		} catch (Exception e) {
			log.error("[결제 보상 트랜잭션 실패]: Reservation UID {} 처리 중 알 수 없는 예외 발생. 수동 개입이 필요합니다.", reservationUid, e);
			outboxEventPublisher.save(
				EventType.PAYMENT_CANCELLATION_FAILED,
				new PaymentEvent.PaymentCancellationFailedEvent(reservationUid, "보상 트랜잭션 실패: " + e.getMessage())
			);
		}
	}

	@Transactional
	public void compensatePaymentByReservationUid(String reservationUid) {
		log.warn("[DLQ 보상 트랜잭션 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(() -> {
				log.error("[DLQ-FATAL] 보상 트랜잭션 실패: reservationUid {}에 해당하는 결제 정보를 찾을 수 없음. 수동 확인 필요", reservationUid);
				return new PaymentNotFoundException();
			});

		// 이미 취소된 결제인지 확인
		if (payment.getStatus() == PaymentStatus.CANCELED || payment.getStatus() == PaymentStatus.PARTIAL_CANCELED) {
			log.warn("[DLQ 보상 트랜잭션] 이미 취소된 결제. PaymentKey: {}", payment.getPaymentKey());
			return;
		}

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				"시스템 오류로 인한 예약 확정 실패 (Saga 보상)",
				null // 전액 취소
			);
			payment.updateOnCancel(response);
			log.info("[DLQ 보상 트랜잭션 완료]: PaymentKey {} 결제 취소 완료.", payment.getPaymentKey());
		} catch (TossPaymentException e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션(결제 취소) 중 Toss API 오류 발생. Reservation UID: {}, Code: {}. 수동 개입 필요.",
				reservationUid, e.getErrorCode().name(), e);
			throw e;
		} catch (Exception e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션 중 알 수 없는 오류 발생. Reservation UID: {}. 수동 개입 필요", reservationUid, e);
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByPaymentKey(String paymentKey) {
		try {
			TossPaymentResponse response = tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey);
			Payment payment = paymentRepository.findByPaymentKey(paymentKey)
				.orElseThrow(PaymentNotFoundException::new);
			return PaymentResponse.PaymentInfo.from(payment);
		} catch (TossPaymentException e) {
			log.warn("[결제 조회 실패] API 조회 실패. PaymentKey: {}, Code: {}", paymentKey, e.getErrorCode().name());
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[결제 조회 실패] 외부 시스템 오류. PaymentKey: {}", paymentKey, e);
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByOrderId(String orderId) {
		try {
			TossPaymentResponse response = tossPaymentsAdapter.getPaymentByOrderId(orderId);
			Payment payment = paymentRepository.findByOrderId(orderId)
				.orElseThrow(PaymentNotFoundException::new);
			return PaymentResponse.PaymentInfo.from(payment);
		} catch (TossPaymentException e) {
			log.warn("[결제 조회 실패] API 조회 실패. OrderId: {}, Code: {}", orderId, e.getErrorCode().name());
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[결제 조회 실패] 외부 시스템 오류. OrderId: {}", orderId, e);
			throw e;
		}
	}

	@Transactional
	public TossPaymentResponse issueVirtualAccount(String reservationUid, PaymentRequest.VirtualAccount request) {
		log.info("[가상계좌 발급]: Reservation UID {}", reservationUid);

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.issueVirtualAccount(reservation, request.bankCode(), request.customerName());
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);
			paymentAttemptRepository.save(attempt);
			log.info("[가상계좌 발급 완료]: Reservation UID {}", reservationUid);
			return response;
		} catch (TossPaymentException e) {
			log.error("[가상계좌 발급 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
			// 가상계좌 발급 실패는 Saga의 시작 부분이므로, 사용자에게 직접 에러를 전달해야 함
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[가상계좌 발급 실패] 재시도 소진. Reservation UID: {}.", reservationUid, e);
			throw e; // 컨트롤러로 예외를 전달하여 5xx 에러 응답
		}
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

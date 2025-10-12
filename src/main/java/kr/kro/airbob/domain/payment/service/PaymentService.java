package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentRepository paymentRepository;
	private final ReservationRepository reservationRepository;
	private final PaymentAttemptRepository paymentAttemptRepository;

	private final TossPaymentsAdapter tossPaymentsAdapter;
	private final PaymentTransactionService paymentTransactionService;

	public void processPaymentConfirmation(PaymentRequest.Confirm event, Acknowledgment ack) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.orderId()))
			.orElseThrow(ReservationNotFoundException::new);

		String reservationUid = reservation.getReservationUid().toString();
		log.info("[결제 승인 처리 시작]: Reservation UID {}", reservationUid);

		try {
			// Toss 결제 API 호출
			TossPaymentResponse response = tossPaymentsAdapter.confirmPayment(
				event.paymentKey(),
				event.orderId(),
				event.amount()
			);

			if (PaymentStatus.DONE.toString().equalsIgnoreCase(response.getStatus())) {
				paymentTransactionService.processSuccessfulPayment(response, reservation, ack::acknowledge);
			} else {
				String reason = response.getFailure() != null ? response.getFailure().getMessage() :
					"결제 실패 (상태: " + response.getStatus() + ")";
				paymentTransactionService.processFailedPayment(event, reservation, response.getFailure().getCode(), reason);
			}
		} catch (TossPaymentException e) {
			paymentTransactionService.processFailedPayment(event, reservation, e.getErrorCode().name(), e.getMessage());
		} catch (ResourceAccessException e) {
			log.error("[결제 최종 실패] 재시도 소진. Reservation UID: {}. 수동 확인 필요.", reservationUid, e);
			paymentTransactionService.processFailedPayment(event, reservation, "RETRY_EXHAUSTED", e.getMessage());
		} catch (Exception e) {
			log.error("[결제 최종 실패] 알 수 없는 예외 발생. Reservation UID: {}", reservationUid, e);
			paymentTransactionService.processFailedPayment(event, reservation, "UNKNOWN_ERROR", e.getMessage());
		}
	}

	public void processPaymentCancellation(ReservationEvent.ReservationCancelledEvent event,  Acknowledgment ack) {
		String reservationUid = event.reservationUid();
		log.info("[결제 취소 처리 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(PaymentNotFoundException::new);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				event.cancelReason(),
				event.cancelAmount()
			);

			paymentTransactionService.processSuccessfulCancellation(payment, response, ack::acknowledge);

		} catch (TossPaymentException e) {
			log.error("[결제 취소 실패] Toss API 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(), e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, e.getMessage(), ack::acknowledge);
		} catch (ResourceAccessException e) {
			log.error("[결제 취소 실패] 재시도 소진. Reservation UID: {}. 수동 개입 필요.", reservationUid, e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, "결제 취소 중 외부 시스템 오류 발생", ack::acknowledge);
		} catch (Exception e) {
			log.error("[결제 취소 처리 실패]: Reservation UID {} 처리 중 알 수 없는 예외 발생", reservationUid, e);
			paymentTransactionService.processFailedCancellationInTx(reservationUid, e.getMessage(), ack::acknowledge);
		}
	}

	public void compensatePaymentByReservationUid(String reservationUid) {
		log.warn("[DLQ 보상 트랜잭션 시작]: Reservation UID {}", reservationUid);

		Payment payment = paymentRepository.findByReservationReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(() -> {
				log.error("[DLQ-FATAL] 보상 트랜잭션 실패: reservationUid {}에 해당하는 결제 정보를 찾을 수 없음. 수동 확인 필요", reservationUid);
				return new PaymentNotFoundException();
			});

		if (payment.getStatus() == PaymentStatus.CANCELED || payment.getStatus() == PaymentStatus.PARTIAL_CANCELED) {
			log.warn("[DLQ 보상 트랜잭션] 이미 취소된 결제. PaymentKey: {}", payment.getPaymentKey());
			return; // 이미 처리됐으면 아무것도 안 함
		}

		try {
			// TOSS API 호출
			TossPaymentResponse response = tossPaymentsAdapter.cancelPayment(
				payment.getPaymentKey(),
				"시스템 오류로 인한 예약 확정 실패 (Saga 보상)",
				null
			);

			// DB 트랜잭션 호출
			paymentTransactionService.processCompensationInTx(payment, response);

		} catch (TossPaymentException e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션(결제 취소) 중 Toss API 오류 발생. Reservation UID: {}, Code: {}. 수동 개입 필요.",
				reservationUid, e.getErrorCode().name(), e);
			throw e; // 예외를 다시 던져 DLQ 컨슈머가 실패했음을 인지하게 함
		} catch (Exception e) {
			log.error("[DLQ-FATAL] 보상 트랜잭션 중 알 수 없는 오류 발생. Reservation UID: {}. 수동 개입 필요", reservationUid, e);
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByPaymentKey(String paymentKey, Long memberId) {
		try {
			Payment payment = paymentRepository.findByPaymentKey(paymentKey)
				.orElseThrow(PaymentNotFoundException::new);

			if (!payment.getReservation().getGuest().getId().equals(memberId)) {
				throw new PaymentAccessDeniedException();
			}

			TossPaymentResponse response = tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey);

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
	public PaymentResponse.PaymentInfo findPaymentByOrderId(String orderId, Long memberId) {
		try {
			Payment payment = paymentRepository.findByOrderId(orderId)
				.orElseThrow(PaymentNotFoundException::new);

			if (!payment.getReservation().getGuest().getId().equals(memberId)) {
				throw new PaymentAccessDeniedException();
			}
			TossPaymentResponse response = tossPaymentsAdapter.getPaymentByOrderId(orderId);
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
			TossPaymentResponse response = tossPaymentsAdapter.issueVirtualAccount(reservation, request.bankCode(),
				request.customerName());
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);
			paymentAttemptRepository.save(attempt);
			log.info("[가상계좌 발급 완료]: Reservation UID {}", reservationUid);
			return response;
		} catch (TossPaymentException e) {
			log.error("[가상계좌 발급 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(),
				e);
			// 가상계좌 발급 실패는 Saga의 시작 부분이므로, 사용자에게 직접 에러를 전달
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[가상계좌 발급 실패] 재시도 소진. Reservation UID: {}.", reservationUid, e);
			throw e; // 컨트롤러로 예외를 전달하여 5xx 에러 응답
		}
	}
}

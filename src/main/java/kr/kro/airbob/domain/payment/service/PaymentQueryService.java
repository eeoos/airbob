package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

	private final PaymentRepository paymentRepository;
	private final TossPaymentsAdapter tossPaymentsAdapter;

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByPaymentKey(String paymentKey, Long memberId) {
		try {
			// todo: Guest 같이 조회하게끔 수정
			Payment payment = paymentRepository.findByPaymentKey(paymentKey)
				.orElseThrow(PaymentNotFoundException::new);

			validateOwner(payment, memberId);

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
			// todo: Guest 같이 조회하게끔 수정
			Payment payment = paymentRepository.findByOrderId(orderId)
				.orElseThrow(PaymentNotFoundException::new);

			validateOwner(payment, memberId);
			TossPaymentResponse response = tossPaymentsAdapter.getPaymentByOrderId(orderId);
			return PaymentResponse.PaymentInfo.from(payment);
		} catch (TossPaymentException e) {
			log.warn("[결제 조회 실패] API 조회 실패. OrderId: {}, Code: {}", orderId, e.getErrorCode().name());
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[결제 조회 실패] 외 부 시스템 오류. OrderId: {}", orderId, e);
			throw e;
		}
	}

	private void validateOwner(Payment payment, Long memberId) {
		if (!payment.getReservation().getGuest().getId().equals(memberId)) {
			throw new PaymentAccessDeniedException();
		}
	}

}

package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;

/** 결제 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class PaymentReader {

	private final PaymentRepository paymentRepository;

	public Payment readByPaymentKey(String paymentKey) {
		return paymentRepository.findByPaymentKey(paymentKey).orElseThrow(PaymentNotFoundException::new);
	}

	public Payment readByOrderId(String orderId) {
		return paymentRepository.findByOrderId(orderId).orElseThrow(PaymentNotFoundException::new);
	}

	/** 예약의 결제 행을 FOR UPDATE로 잠근다. 예약 행을 먼저 잠근 뒤에 부른다. */
	public Payment readByReservationIdForUpdate(Long reservationId) {
		return paymentRepository.findByReservationIdWithLock(reservationId).orElseThrow(PaymentNotFoundException::new);
	}
}

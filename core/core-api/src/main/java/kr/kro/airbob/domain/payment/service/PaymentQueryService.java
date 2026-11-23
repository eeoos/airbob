package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.PaymentDetail;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

	private final PaymentReader paymentReader;
	private final PaymentTransactionReader transactionReader;

	@Transactional(readOnly = true)
	public PaymentDetail findPaymentByPaymentKey(String paymentKey, Long memberId) {
		Payment payment = paymentReader.readByPaymentKey(paymentKey);
		validateOwner(payment, memberId);
		return PaymentDetail.of(payment, transactionReader.readCancellations(payment.getId()));
	}

	@Transactional(readOnly = true)
	public PaymentDetail findPaymentByOrderId(String orderId, Long memberId) {
		Payment payment = paymentReader.readByOrderId(orderId);
		validateOwner(payment, memberId);
		return PaymentDetail.of(payment, transactionReader.readCancellations(payment.getId()));
	}

	private void validateOwner(Payment payment, Long memberId) {
		if (!payment.getReservation().getGuest().getId().equals(memberId)) {
			throw new PaymentAccessDeniedException();
		}
	}

}

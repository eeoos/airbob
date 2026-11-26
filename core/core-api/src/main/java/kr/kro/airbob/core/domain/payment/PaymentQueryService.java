package kr.kro.airbob.core.domain.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

	private final PaymentReader paymentReader;
	private final PaymentTransactionReader transactionReader;

	@Transactional(readOnly = true)
	public PaymentDetail getPaymentByPaymentKey(String paymentKey, Long memberId) {
		Payment payment = paymentReader.readByPaymentKey(paymentKey);
		validateOwner(payment, memberId);
		return PaymentDetail.of(payment, transactionReader.readCancellations(payment.getId()));
	}

	@Transactional(readOnly = true)
	public PaymentDetail getPaymentByOrderId(String orderId, Long memberId) {
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

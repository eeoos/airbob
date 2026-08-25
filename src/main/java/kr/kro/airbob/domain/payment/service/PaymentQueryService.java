package kr.kro.airbob.domain.payment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

	private final PaymentRepository paymentRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByPaymentKey(String paymentKey, Long memberId) {
		Payment payment = paymentRepository.findByPaymentKey(paymentKey)
			.orElseThrow(PaymentNotFoundException::new);
		validateOwner(payment, memberId);
		return PaymentResponse.PaymentInfo.from(payment, findCancelTransactions(payment));
	}

	@Transactional(readOnly = true)
	public PaymentResponse.PaymentInfo findPaymentByOrderId(String orderId, Long memberId) {
		Payment payment = paymentRepository.findByOrderId(orderId)
			.orElseThrow(PaymentNotFoundException::new);
		validateOwner(payment, memberId);
		return PaymentResponse.PaymentInfo.from(payment, findCancelTransactions(payment));
	}

	private List<PaymentTransaction> findCancelTransactions(Payment payment) {
		return paymentTransactionRepository.findByPaymentIdAndTransactionTypeInOrderByCreatedAtAsc(
			payment.getId(), List.of(PaymentTransactionType.CANCEL, PaymentTransactionType.PARTIAL_CANCEL));
	}

	private void validateOwner(Payment payment, Long memberId) {
		if (!payment.getReservation().getGuest().getId().equals(memberId)) {
			throw new PaymentAccessDeniedException();
		}
	}

}

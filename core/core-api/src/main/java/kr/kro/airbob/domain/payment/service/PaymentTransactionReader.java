package kr.kro.airbob.domain.payment.service;

import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;

/** 결제 거래 원장 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class PaymentTransactionReader {

	private static final List<PaymentTransactionType> CANCELLATION_TYPES =
		List.of(PaymentTransactionType.CANCEL, PaymentTransactionType.PARTIAL_CANCEL);

	private final PaymentTransactionRepository transactionRepository;

	/** 결제의 전체·부분 취소 원장을 생성 시각 오름차순으로 읽는다. */
	public List<PaymentTransaction> readCancellations(Long paymentId) {
		return transactionRepository.findByPaymentIdAndTransactionTypeInOrderByCreatedAtAsc(
			paymentId, CANCELLATION_TYPES);
	}
}

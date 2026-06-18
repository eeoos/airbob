package kr.kro.airbob.domain.payment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

	// 가상계좌 등 결제 확정 전 거래 조회 (구 PaymentAttemptRepository)
	List<PaymentTransaction> findByOrderIdOrderByCreatedAtDesc(String orderId);

	// 특정 결제의 취소 이력(전체/부분) 조회 — 발생 순
	List<PaymentTransaction> findByPaymentIdAndTransactionTypeInOrderByCreatedAtAsc(
		Long paymentId, List<PaymentTransactionType> transactionTypes);
}

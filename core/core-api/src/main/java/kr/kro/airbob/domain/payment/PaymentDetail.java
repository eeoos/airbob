package kr.kro.airbob.domain.payment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;

/** 게스트가 조회한 확정 결제와 그 결제의 전체·부분 취소 원장(생성 시각 오름차순). */
public record PaymentDetail(
	String orderId,
	String paymentKey,
	PaymentMethod method,
	Long totalAmount,
	Long balanceAmount,
	PaymentStatus status,
	LocalDateTime createdAt,
	Instant approvedAt,
	List<Cancellation> cancellations
) {

	public PaymentDetail {
		cancellations = List.copyOf(cancellations);
	}

	public static PaymentDetail of(Payment payment, List<PaymentTransaction> cancelTransactions) {
		return new PaymentDetail(
			payment.getOrderId(),
			payment.getPaymentKey(),
			payment.getMethod(),
			payment.getAmount(),
			payment.getBalanceAmount(),
			payment.getStatus(),
			payment.getCreatedAt(),
			payment.getApprovedAt(),
			cancelTransactions.stream().map(Cancellation::from).toList()
		);
	}

	/** 취소 원장 한 건. 과거 원장에는 값이 없을 수 있다. */
	public record Cancellation(
		Long cancelAmount,
		String cancelReason,
		Instant canceledAt
	) {

		public static Cancellation from(PaymentTransaction transaction) {
			return new Cancellation(
				transaction.getCancelAmount(),
				transaction.getCancelReason(),
				transaction.getCanceledAt()
			);
		}
	}
}

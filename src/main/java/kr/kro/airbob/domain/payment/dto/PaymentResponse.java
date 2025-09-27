package kr.kro.airbob.domain.payment.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentCancel;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentResponse {

	@Builder
	public record PaymentInfo(
		String orderId,
		String paymentKey,
		String method,
		Long totalAmount,
		Long balanceAmount,
		PaymentStatus status,
		LocalDateTime requestedAt,
		LocalDateTime approvedAt,
		List<CancelInfo> cancels
	){
		public static PaymentInfo from(Payment payment) {
			List<CancelInfo> cancelInfos = payment.getCancels().stream()
				.map(CancelInfo::from)
				.toList();

			return PaymentInfo.builder()
				.orderId(payment.getOrderId())
				.paymentKey(payment.getPaymentKey())
				.method(payment.getMethod().getDescription())
				.totalAmount(payment.getTotalAmount())
				.balanceAmount(payment.getBalanceAmount())
				.status(payment.getStatus())
				.requestedAt(payment.getCreatedAt())
				.approvedAt(payment.getApprovedAt())
				.cancels(cancelInfos)
				.build();
		}
	}

	@Builder
	public record CancelInfo(
		Long cancelAmount,
		String cancelReason,
		LocalDateTime canceledAt
	) {
		public static CancelInfo from(PaymentCancel cancel) {
			return CancelInfo.builder()
				.cancelAmount(cancel.getCancelAmount())
				.cancelReason(cancel.getCancelReason())
				.canceledAt(cancel.getCanceledAt())
				.build();
		}
	}
}

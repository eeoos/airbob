package kr.kro.airbob.domain.payment.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.entity.PaymentCancel;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentResponse {

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaymentInfo(
		String orderId,
		String paymentKey,
		String method,
		Long totalAmount,
		Long balanceAmount,
		PaymentStatus status,
		LocalDateTime requestedAt,
		LocalDateTime approvedAt,
		List<CancelInfo> cancels,
		VirtualAccountInfo virtualAccount
	){
		public static PaymentInfo from(Payment payment) {
			List<CancelInfo> cancelInfos = payment.getCancels().stream()
				.map(CancelInfo::from)
				.toList();

			return PaymentInfo.builder()
				.orderId(payment.getOrderId())
				.paymentKey(payment.getPaymentKey())
				.method(payment.getMethod().getDescription())
				.totalAmount(payment.getAmount())
				.balanceAmount(payment.getBalanceAmount())
				.status(payment.getStatus())
				.requestedAt(payment.getCreatedAt())
				.approvedAt(payment.getApprovedAt())
				.cancels(cancelInfos)
				.virtualAccount(null)
				.build();
		}

		public static PaymentInfo from(PaymentAttempt attempt) {
			return PaymentInfo.builder()
				.orderId(attempt.getOrderId())
				.paymentKey(attempt.getPaymentKey())
				.method(attempt.getMethod().getDescription())
				.totalAmount(attempt.getAmount())
				.status(attempt.getStatus())
				.requestedAt(attempt.getCreatedAt())
				.virtualAccount(VirtualAccountInfo.from(attempt))
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

	@Builder
	public record VirtualAccountInfo(
		String accountNumber,
		String bankCode,
		String customerName,
		LocalDateTime dueDate
	) {
		public static VirtualAccountInfo from(PaymentAttempt attempt) {
			return VirtualAccountInfo.builder()
				.accountNumber(attempt.getVirtualAccountNumber())
				.bankCode(attempt.getVirtualBankCode())
				.customerName(attempt.getVirtualCustomerName())
				.dueDate(attempt.getVirtualDueDate())
				.build();
		}
	}
}

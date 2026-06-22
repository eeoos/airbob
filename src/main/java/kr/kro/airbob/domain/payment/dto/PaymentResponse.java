package kr.kro.airbob.domain.payment.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
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
		// 확정된 결제 + 취소 이력(거래 원장의 CANCEL/PARTIAL_CANCEL)
		public static PaymentInfo from(Payment payment, List<PaymentTransaction> cancelTransactions) {
			List<CancelInfo> cancelInfos = cancelTransactions.stream()
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

		// 결제 확정 전(가상계좌 발급 등) — 거래 원장 기반
		public static PaymentInfo from(PaymentTransaction transaction) {
			return PaymentInfo.builder()
				.orderId(transaction.getOrderId())
				.paymentKey(transaction.getPaymentKey())
				.method(transaction.getMethod() != null ? transaction.getMethod().getDescription() : null)
				.totalAmount(transaction.getAmount())
				.status(transaction.getStatus())
				.requestedAt(transaction.getCreatedAt())
				.virtualAccount(VirtualAccountInfo.from(transaction))
				.build();
		}
	}

	@Builder
	public record CancelInfo(
		Long cancelAmount,
		String cancelReason,
		LocalDateTime canceledAt
	) {
		public static CancelInfo from(PaymentTransaction cancelTransaction) {
			return CancelInfo.builder()
				.cancelAmount(cancelTransaction.getCancelAmount())
				.cancelReason(cancelTransaction.getCancelReason())
				.canceledAt(cancelTransaction.getCanceledAt())
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
		public static VirtualAccountInfo from(PaymentTransaction transaction) {
			return VirtualAccountInfo.builder()
				.accountNumber(transaction.getVirtualAccountNumber())
				.bankCode(transaction.getVirtualBankCode())
				.customerName(transaction.getVirtualCustomerName())
				.dueDate(transaction.getVirtualDueDate())
				.build();
		}
	}
}

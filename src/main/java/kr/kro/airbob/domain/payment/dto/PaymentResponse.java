package kr.kro.airbob.domain.payment.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

	public record HostPaymentInfo(Long totalAmount) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record GuestPaymentInfo(String method, Long totalAmount, PaymentStatus status, Instant approvedAt) {
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaymentInfo(
		String orderId,
		String paymentKey,
		String method,
		Long totalAmount,
		Long balanceAmount,
		PaymentStatus status,
		Instant requestedAt,
		Instant approvedAt,
		List<CancelInfo> cancels
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
				.requestedAt(toUtcInstant(payment.getCreatedAt()))
				.approvedAt(payment.getApprovedAt())
				.cancels(cancelInfos)
				.build();
		}
	}

	@Builder
	public record CancelInfo(
		Long cancelAmount,
		String cancelReason,
		Instant canceledAt
	) {
		public static CancelInfo from(PaymentTransaction cancelTransaction) {
			return CancelInfo.builder()
				.cancelAmount(cancelTransaction.getCancelAmount())
				.cancelReason(cancelTransaction.getCancelReason())
				.canceledAt(cancelTransaction.getCanceledAt())
				.build();
		}
	}

	private static Instant toUtcInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
	}
}

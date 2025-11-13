package kr.kro.airbob.domain.payment.dto;

import java.time.ZonedDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter // ?
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 문서에 명시된 필드 외에는 무시
public class TossPaymentResponse {
	private String paymentKey;
	private String orderId;
	private Long totalAmount;
	private String method;
	private String status;
	private ZonedDateTime requestedAt;
	private ZonedDateTime approvedAt;
	private Long balanceAmount;
	private Failure failure;

	private List<Cancel> cancels;

	private VirtualAccount virtualAccount;

	@Getter
	@Setter
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Failure {
		private String code;
		private String message;
	}

	@Getter
	@Setter
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Cancel {
		private Long cancelAmount;
		private String cancelReason;
		private Long taxFreeAmount;
		private Integer taxExemptionAmount;
		private Long refundableAmount;
		private Long easyPayDiscountAmount;
		private ZonedDateTime canceledAt;
		private String transactionKey;
		private String receiptKey;
	}

	@Getter
	@Setter
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class VirtualAccount {
		private String accountNumber;
		private String accountType;
		private String bankCode;
		private String customerName;
		private ZonedDateTime dueDate;
		private boolean expired;
		private String settlementStatus;
		private String refundStatus;
	}
}

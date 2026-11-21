package kr.kro.airbob.domain.payment.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentResponse {

	@Schema(name = "PaymentResponseHostPaymentInfo")
	public record HostPaymentInfo(Long totalAmount) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "PaymentResponseGuestPaymentInfo")
	public record GuestPaymentInfo(String method, Long totalAmount, PaymentStatus status, @Schema(nullable = true) Instant approvedAt) {
	}

	@Builder
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@Schema(name = "PaymentResponsePaymentInfo", example = """
		{"order_id":"00000000-0000-0000-0000-000000000020","payment_key":"example-payment-key",
		 "method":"카드","total_amount":10000,"balance_amount":10000,"status":"DONE",
		 "requested_at":"2027-01-01T00:00:01Z","approved_at":"2027-01-01T00:00:00Z","cancels":[]}
		""")
	public record PaymentInfo(
		@Schema(description = "예약 UID와 같은 주문 ID", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String orderId,
		@Schema(description = "결제사의 결제 키", example = "example-payment-key", requiredMode = Schema.RequiredMode.REQUIRED)
		String paymentKey,
		@Schema(description = "결제 수단 표시명. 예: 카드, 간편결제", example = "카드", requiredMode = Schema.RequiredMode.REQUIRED)
		String method,
		@Schema(description = "최초 승인 금액(KRW)", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long totalAmount,
		@Schema(description = "취소 반영 후 남은 결제 금액. 전액 취소 시 0", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long balanceAmount,
		@Schema(description = "저장된 결제 상태. 신규 승인은 DONE, 전액 취소는 CANCELED입니다. 과거 데이터에는 다른 상태도 있을 수 있습니다.", example = "DONE", requiredMode = Schema.RequiredMode.REQUIRED)
		PaymentStatus status,
		@Schema(description = "Airbob 결제 기록 생성 시각(UTC). 과거 데이터에 없으면 필드가 생략됩니다.", example = "2027-01-01T00:00:01Z")
		Instant requestedAt,
		@Schema(description = "결제사 승인 시각(UTC). 값이 없으면 필드가 생략됩니다.", example = "2027-01-01T00:00:00Z", nullable = true)
		Instant approvedAt,
		@Schema(description = "성공한 전체·부분 취소 이력의 원장 생성 시각 오름차순. 없으면 빈 배열이며 진행 중·실패 이력은 포함하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
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
	@Schema(name = "PaymentResponseCancelInfo")
	public record CancelInfo(
		@Schema(description = "해당 이력의 취소 금액. 과거 원장에 값이 없으면 null", example = "10000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Long cancelAmount,
		@Schema(description = "취소 사유. 과거 원장에 값이 없으면 null", example = "여행 일정 변경", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String cancelReason,
		@Schema(description = "결제사가 알려준 취소 시각(UTC). 과거 원장에 값이 없으면 null", example = "2027-01-02T00:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
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

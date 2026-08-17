package kr.kro.airbob.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentRequest {

	public record Confirm(
		@NotBlank @Size(max = 200) String paymentKey,
		@NotBlank String orderId,
		@NotNull @Positive Integer amount
	) {}

	public record Cancel(
		@NotBlank(message = "취소 사유는 필수입니다.")
		@Size(
			max = PaymentOperation.CANCELLATION_REASON_MAX_LENGTH,
			message = "취소 사유는 200자 이하여야 합니다."
		)
		String cancelReason,

		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		Long cancelAmount // 취소 금액, null이면 전액 취소
		) {}

	public record VirtualAccount(
		@NotBlank(message = "은행 코드는 필수입니다.")
		String bankCode,

		@NotBlank(message = "고객 이름은 필수입니다.")
		String customerName
		) {}
}

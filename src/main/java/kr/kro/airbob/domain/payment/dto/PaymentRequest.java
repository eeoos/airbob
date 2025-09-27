package kr.kro.airbob.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentRequest {

	public record Confirm(
		@NotBlank String paymentKey,
		@NotBlank String orderId,
		@NotNull @Positive Long amount
	){}

	public record Cancel(
		@NotBlank(message = "취소 사유는 필수입니다.")
		String cancelReason,

		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		Long cancelAmount // 취소 금액, null이면 전액 취소
		) {

	}
}

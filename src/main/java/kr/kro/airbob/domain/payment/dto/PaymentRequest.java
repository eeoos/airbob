package kr.kro.airbob.domain.payment.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
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
		@NotNull @Positive Integer amount,
		@NotNull UUID paymentAttemptId
	) {}

	@Schema(name = "PaymentRequestCancel", example = "{\"cancel_reason\":\"여행 일정 변경\"}")
	public record Cancel(
		@NotBlank(message = "취소 사유는 필수입니다.")
		@Size(
			max = PaymentOperation.CANCELLATION_REASON_MAX_LENGTH,
			message = "취소 사유는 200자 이하여야 합니다."
		)
		@Schema(description = "취소 사유. 200자 이하", example = "여행 일정 변경")
		String cancelReason,

		@Positive(message = "취소 금액은 0보다 커야 합니다.")
		@Schema(description = "생략하거나 null이면 전액 취소. 지정 시 현재 결제 잔액 전액이어야 합니다. 부분 취소는 지원하지 않으며 0원 예약에서는 생략하거나 null로 보냅니다.",
			example = "10000", minimum = "1", nullable = true)
		Long cancelAmount // 취소 금액, null이면 전액 취소
		) {}

}

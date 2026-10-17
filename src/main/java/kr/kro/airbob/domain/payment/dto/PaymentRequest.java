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

	@Schema(name = "PaymentRequestConfirm")
	public record Confirm(
		@Schema(description = "결제창에서 받은 결제 키. 빈 값·공백만 있는 값 불가, 최대 200자", example = "example-payment-key")
		@NotBlank @Size(min = 1, max = 200) String paymentKey,
		@Schema(description = "결제 시도 발급 응답의 order_id(예약 UID) 문자열을 그대로 전달합니다.", format = "uuid", example = "00000000-0000-0000-0000-000000000020")
		@NotBlank String orderId,
		@Schema(description = "결제 시도 발급 응답의 amount와 같은 금액. 0원 예약은 결제 확인을 요청하지 않습니다.", example = "10000", minimum = "1", maximum = "2147483647")
		@NotNull @Positive Integer amount,
		@Schema(description = "결제 시도 발급 응답의 payment_attempt_id", example = "00000000-0000-0000-0000-000000000030")
		@NotNull UUID paymentAttemptId
	) {}

	@Schema(name = "PaymentRequestCancel", example = "{\"cancel_reason\":\"여행 일정 변경\"}")
	public record Cancel(
		@NotBlank(message = "취소 사유는 필수입니다.")
		@Size(
			min = 1,
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

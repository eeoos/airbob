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
}

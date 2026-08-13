package kr.kro.airbob.domain.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PaymentRequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 결제키가_200자를_초과하면_거부한다() {
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"p".repeat(201), UUID.randomUUID().toString(), 10_000);

		assertThat(validator.validate(request))
			.anyMatch(violation -> violation.getPropertyPath().toString().equals("paymentKey"));
	}
}

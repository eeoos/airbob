package kr.kro.airbob.core.api.controller.v1.payment.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PaymentRequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("결제키가 200자를 초과하면 거부한다")
	void rejectsPaymentKeyLongerThan200Characters() {
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"p".repeat(201), UUID.randomUUID().toString(), 10_000, UUID.randomUUID());

		assertThat(validator.validate(request))
			.anyMatch(violation -> violation.getPropertyPath().toString().equals("paymentKey"));
	}

	@Test
	@DisplayName("결제 시도 토큰이 없으면 거부한다")
	void rejectsMissingPaymentAttemptToken() {
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", UUID.randomUUID().toString(), 10_000, null);

		assertThat(validator.validate(request))
			.anyMatch(violation -> violation.getPropertyPath().toString().equals("paymentAttemptId"));
	}

	@Test
	@DisplayName("취소 사유는 200자까지 허용하고 201자는 거부한다")
	void acceptsCancelReasonUpTo200CharactersAndRejects201() {
		PaymentRequest.Cancel maximum = new PaymentRequest.Cancel("사".repeat(200), null);
		PaymentRequest.Cancel oversized = new PaymentRequest.Cancel("사".repeat(201), null);

		assertThat(validator.validate(maximum)).isEmpty();
		assertThat(validator.validate(oversized))
			.anyMatch(violation -> violation.getPropertyPath().toString().equals("cancelReason"));
	}
}

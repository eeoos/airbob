package kr.kro.airbob.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.domain.accommodation.exception.PublishingFieldRequiredException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationInvariantViolationException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerMvcTest {

	private static final String SENSITIVE_PROVIDER_DETAIL =
		"provider_response=secret-payment-key-and-raw-body";

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void mapsPaymentOperationInvariantViolationsToGenericInternalServerError(
		CapturedOutput output
	) throws Exception {
		mockMvc.perform(get("/test/errors/payment-invariant"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C003"))
			.andExpect(jsonPath("$.error.status").value(500))
			.andExpect(content().string(not(containsString(SENSITIVE_PROVIDER_DETAIL))));

		assertThat(output).doesNotContain(SENSITIVE_PROVIDER_DETAIL);
	}

	@Test
	void keepsExpectedPaymentOperationRequestConflictsAsConflict() throws Exception {
		mockMvc.perform(get("/test/errors/payment-conflict"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("P006"))
			.andExpect(jsonPath("$.error.status").value(409));
	}

	@Test
	void mapsPublishingFieldErrorsToTheirDedicatedCode() throws Exception {
		mockMvc.perform(get("/test/errors/publishing-field"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("A009"))
			.andExpect(jsonPath("$.error.status").value(400));
	}

	@RestController
	private static final class FailureController {

		@GetMapping("/test/errors/payment-invariant")
		void paymentInvariant() {
			throw new PaymentOperationInvariantViolationException(SENSITIVE_PROVIDER_DETAIL);
		}

		@GetMapping("/test/errors/payment-conflict")
		void paymentConflict() {
			throw new PaymentOperationConflictException();
		}

		@GetMapping("/test/errors/publishing-field")
		void publishingField() {
			throw new PublishingFieldRequiredException("title");
		}
	}
}

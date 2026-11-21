package kr.kro.airbob.domain.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.MarkNotPaid;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.Reconciliation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;

class PaymentOperationAdminRequestValidationTest {

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validator = Validation.buildDefaultValidatorFactory().getValidator();
	}

	@Test
	void acceptsZeroAsTheInitialExpectedVersion() {
		assertThat(validator.validate(new Reconciliation(0L))).isEmpty();
	}

	@Test
	void requiresANonNegativeExpectedVersion() {
		assertThat(validator.validate(new Reconciliation(null))).isNotEmpty();
		assertThat(validator.validate(new Reconciliation(-1L))).isNotEmpty();
	}

	@Test
	void acceptsOnlyClosedReasonsAndStrictInternalEvidenceReferences() {
		MarkNotPaid request = new MarkNotPaid(
			3L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			"toss-dashboard/case:ABC_123-4.5,log"
		);

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void rejectsMissingOversizedOrUnsafeEvidenceReferences() {
		assertEvidenceInvalid(null);
		assertEvidenceInvalid("   ");
		assertEvidenceInvalid("a".repeat(257));
		assertEvidenceInvalid("case/ABC 123");
		assertEvidenceInvalid("case/ABC?token=secret");
		assertEvidenceInvalid("case/ABC\n123");
	}

	private static void assertEvidenceInvalid(String evidenceReference) {
		Set<ConstraintViolation<MarkNotPaid>> violations = validator.validate(new MarkNotPaid(
			3L,
			PaymentOperationResolutionReason.PROVIDER_PAYMENT_NOT_FOUND,
			evidenceReference
		));

		assertThat(violations)
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains("evidenceReference");
	}
}

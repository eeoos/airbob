package kr.kro.airbob.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionReason;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentOperationAdminRequest {

	private static final int EVIDENCE_REFERENCE_MAX_LENGTH = 256;
	private static final String INTERNAL_EVIDENCE_REFERENCE_PATTERN = "^[A-Za-z0-9_\\-/:.,]+$";

	public record Reconciliation(
		@NotNull @PositiveOrZero Long expectedVersion
	) {
	}

	public record MarkNotPaid(
		@NotNull @PositiveOrZero Long expectedVersion,
		@NotNull PaymentOperationResolutionReason reasonCode,
		@NotBlank
		@Size(max = EVIDENCE_REFERENCE_MAX_LENGTH)
		@Pattern(regexp = INTERNAL_EVIDENCE_REFERENCE_PATTERN)
		String evidenceReference
	) {
	}
}

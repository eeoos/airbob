package kr.kro.airbob.domain.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

	@Schema(name = "PaymentOperationAdminRequestReconciliation")
	public record Reconciliation(
		@Schema(description = "수동 확인 목록의 version. 충돌 시 다시 조회한 값을 사용합니다.", minimum = "0", example = "7")
		@NotNull @PositiveOrZero Long expectedVersion
	) {
	}

	@Schema(name = "PaymentOperationAdminRequestMarkNotPaid")
	public record MarkNotPaid(
		@Schema(description = "수동 확인 목록의 version. 충돌 시 다시 조회한 값을 사용합니다.", minimum = "0", example = "7")
		@NotNull @PositiveOrZero Long expectedVersion,
		@Schema(description = "미결제를 확인한 증거의 분류", example = "PROVIDER_PAYMENT_NOT_FOUND")
		@NotNull PaymentOperationResolutionReason reasonCode,
		@Schema(description = "내부 사건·조사 문서 참조. 영문자·숫자와 _ - / : . ,만 허용하며 공백 없이 최대 256자입니다. 결제 키·개인정보·결제사 응답은 넣지 않습니다.", example = "incident/ABC-123")
		@NotBlank
		@Size(min = 1, max = EVIDENCE_REFERENCE_MAX_LENGTH)
		@Pattern(regexp = INTERNAL_EVIDENCE_REFERENCE_PATTERN)
		String evidenceReference
	) {
	}
}

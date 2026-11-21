package kr.kro.airbob.domain.accommodation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyRequest {

	@Builder
	@Schema(name = "PolicyRequestOccupancyPolicyInfo")
	public record OccupancyPolicyInfo(
		@NotNull(message = "최대 수용 인원은 필수입니다.")
		@Positive(message = "최대 수용 인원은 1 이상이어야 합니다.")
		@Schema(description = "최대 수용 인원", example = "4", minimum = "1")
		Integer maxOccupancy,

		@NotNull(message = "유아 수용 인원은 필수입니다.")
		@PositiveOrZero(message = "유아 수용 인원은 0 이상이어야 합니다.")
		@Schema(description = "유아 수용 인원. 허용하지 않으면 0입니다.", example = "1", minimum = "0")
		Integer infantOccupancy,

		@NotNull(message = "반려동물 수용 인원은 필수입니다.")
		@PositiveOrZero(message = "반려동물 수용 인원은 0 이상이어야 합니다.")
		@Schema(description = "반려동물 수용 수. 허용하지 않으면 0입니다.", example = "0", minimum = "0")
		Integer petOccupancy
	){
	}
}

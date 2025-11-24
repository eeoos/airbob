package kr.kro.airbob.domain.accommodation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyRequest {

	@Builder
	public record OccupancyPolicyInfo(
		@NotNull(message = "최대 수용 인원은 필수입니다.")
		@Positive(message = "최대 수용 인원은 1 이상이어야 합니다.")
		Integer maxOccupancy,

		@NotNull(message = "유아 수용 인원은 필수입니다.")
		@PositiveOrZero(message = "유아 수용 인원은 0 이상이어야 합니다.")
		Integer infantOccupancy,

		@NotNull(message = "반려동물 수용 인원은 필수입니다.")
		@PositiveOrZero(message = "반려동물 수용 인원은 0 이상이어야 합니다.")
		Integer petOccupancy
	){
	}
}

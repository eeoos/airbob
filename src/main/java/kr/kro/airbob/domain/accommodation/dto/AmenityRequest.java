package kr.kro.airbob.domain.accommodation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AmenityRequest {

	@Builder
	public record AmenityInfo (
		@NotBlank(message = "편의시설 이름은 필수입니다.")
		String name,

		@NotNull(message = "편의시설 개수는 필수입니다.")
		@PositiveOrZero(message = "편의시설 개수는 0 이상이어야 합니다.")
		Integer count
	) {
	}
}

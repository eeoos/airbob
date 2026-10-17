package kr.kro.airbob.domain.accommodation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AmenityRequest {

	@Builder
	@Schema(name = "AmenityRequestAmenityInfo")
	public record AmenityInfo (
		@NotBlank(message = "편의시설 이름은 필수입니다.")
		@Size(min = 1, max = 50)
		@Schema(description = "표시명이 아닌 AMENITY_TYPE 공통 코드의 code입니다. 대문자로 정규화합니다.", example = "WIFI")
		String name,

		@NotNull(message = "편의시설 개수는 필수입니다.")
		@PositiveOrZero(message = "편의시설 개수는 0 이상이어야 합니다.")
		@Schema(description = "개수. 0이면 저장하지 않습니다. 같은 코드의 합계는 2147483647 이하여야 합니다.", example = "1", minimum = "0")
		Integer count
	) {
	}
}

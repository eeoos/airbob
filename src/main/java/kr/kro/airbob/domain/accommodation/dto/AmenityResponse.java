package kr.kro.airbob.domain.accommodation.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AmenityResponse {

	@Builder
	@Schema(name = "AmenityResponseAmenityInfo")
	public record AmenityInfo(
		@Schema(description = "AMENITY_TYPE의 공통 코드. 수정 요청 amenity_infos[].name에 사용합니다.", example = "WIFI")
		String type,
		@Schema(description = "편의시설 개수", example = "1")
		Integer count
	) {
		public static AmenityInfo from(AccommodationAmenity amenity) {
			return AmenityInfo.builder()
				.type(amenity.getAmenityCode())
				.count(amenity.getCount())
				.build();
		}

	}
}

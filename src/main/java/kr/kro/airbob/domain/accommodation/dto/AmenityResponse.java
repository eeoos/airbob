package kr.kro.airbob.domain.accommodation.dto;

import java.util.List;

import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AmenityResponse {

	@Builder
	public record AmenityInfo(
		AmenityType type,
		Integer count
	) {
		public static AmenityInfo from(AccommodationAmenity amenity) {
			return AmenityInfo.builder()
				.type(amenity.getAmenity().getName())
				.count(amenity.getCount())
				.build();
		}

	}

	@Builder
	public record AmenityInfos(
		List<AmenityInfo> amenityInfos
	) {
		public static AmenityInfos from(List<AmenityInfo> amenityInfos) {
			return AmenityInfos.builder()
				.amenityInfos(amenityInfos)
				.build();
		}
	}
}

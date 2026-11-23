package kr.kro.airbob.domain.accommodation;

import java.util.List;

import kr.kro.airbob.core.domain.image.StoredImage;

/** 호스트가 보는 자기 숙소 상세와 편의시설·이미지. */
public record HostAccommodationDetail(
	HostAccommodationDetailProjection accommodation,
	List<AccommodationDetailSnapshot.Amenity> amenities,
	List<StoredImage> images
) {
}

package kr.kro.airbob.core.domain.accommodation;

import java.util.List;

/**
 * 숙소 수정. 기본 정보(patch)의 null 필드는 유지한다. 주소·편의시설·인원 정책은 null이면 유지하고,
 * 보내면 전체를 교체한다(편의시설 빈 목록은 모두 삭제).
 */
public record AccommodationUpdate(
	AccommodationPatch patch,
	NewAddress address,
	List<NewAmenity> amenities,
	NewOccupancyPolicy occupancyPolicy
) {
}

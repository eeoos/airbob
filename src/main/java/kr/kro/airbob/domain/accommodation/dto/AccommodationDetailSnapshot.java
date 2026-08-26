package kr.kro.airbob.domain.accommodation.dto;

import java.time.LocalTime;
import java.util.List;

import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;

/**
 * 요청자별 찜 여부가 빠진 숙소 상세 공용 스냅샷
 */
public record AccommodationDetailSnapshot(
	long id,
	String name,
	String description,
	String type,
	Long basePrice,
	String currency,
	LocalTime checkInTime,
	LocalTime checkOutTime,
	String timeZoneId,
	AddressResponse.AddressSummaryInfo addressSummary,
	AddressResponse.Coordinate coordinate,
	MemberResponse.MemberInfo host,
	PolicyResponse.PolicyInfo policy,
	List<AmenityResponse.AmenityInfo> amenities,
	List<ImageResponse.ImageInfo> images,
	ReviewResponse.ReviewSummary reviewSummary
) {
	public AccommodationDetailSnapshot {
		amenities = List.copyOf(amenities);
		images = List.copyOf(images);
	}
}

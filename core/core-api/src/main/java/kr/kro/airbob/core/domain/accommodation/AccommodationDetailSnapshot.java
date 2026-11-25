package kr.kro.airbob.core.domain.accommodation;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import kr.kro.airbob.core.domain.image.StoredImage;

/**
 * 요청자별 찜 여부가 빠진 숙소 상세 공용 스냅샷. 숙소 상세 캐시에 JSON으로 저장되므로, 이 레코드와 중첩 레코드의
 * 필드 이름·구조를 바꾸면 이미 저장된 캐시 값을 읽지 못한다(AccommodationDetailCacheValueJsonTest가 지킨다).
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
	AddressSummary addressSummary,
	Coordinate coordinate,
	Host host,
	Policy policy,
	List<Amenity> amenities,
	List<StoredImage> images,
	ReviewSummary reviewSummary) {
	public AccommodationDetailSnapshot {
		amenities = List.copyOf(amenities);
		images = List.copyOf(images);
	}

	public record AddressSummary(
		String country,
		String state,
		String city,
		String district) {
	}

	public record Coordinate(
		Double latitude,
		Double longitude) {
	}

	public record Host(
		long id,
		String nickname,
		String thumbnailImageUrl) {
	}

	public record Policy(
		Integer maxOccupancy,
		Integer infantOccupancy,
		Integer petOccupancy) {
	}

	public record Amenity(
		String type,
		Integer count) {
	}

	/** 게시된 리뷰 수와 평균 평점. 리뷰가 없으면 0이다. */
	public record ReviewSummary(
		Integer totalCount,
		BigDecimal averageRating) {
		public static ReviewSummary of(Integer totalCount, BigDecimal averageRating) {
			return new ReviewSummary(
				totalCount == null ? 0 : totalCount,
				averageRating == null ? BigDecimal.ZERO : averageRating);
		}
	}
}

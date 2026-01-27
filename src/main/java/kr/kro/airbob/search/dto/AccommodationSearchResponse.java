package kr.kro.airbob.search.dto;

import java.math.BigDecimal;
import java.util.List;

import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationSearchResponse {

	@Builder
	public record AccommodationSearchInfo(
		long id,
		String name,
		String accommodationThumbnailUrl,
		String type,
		Long basePrice,
		String currency,
		AddressResponse.AddressSummaryInfo addressSummary,
		AddressResponse.Coordinate coordinate,
		ReviewResponse.ReviewSummary reviewSummary,
		// String hostName, // todo: 넣을지 여부 결정 필요
		Boolean isInWishlist
	){
		public static AccommodationSearchInfo from(AccommodationDocument doc, boolean isInWishlist) {

			AccommodationDocument.Location location = doc.location();
			return AccommodationSearchInfo.builder()
				.id(doc.accommodationId())
				.name(doc.name())
				.accommodationThumbnailUrl(doc.thumbnailUrl())
				.type(doc.type())
				.basePrice(doc.basePrice())
				.currency(doc.currency())
				.addressSummary(AddressResponse.AddressSummaryInfo.from(doc))
				.coordinate(AddressResponse.Coordinate.builder()
					.latitude(location != null ? location.lat() : null)
					.longitude(location != null ? location.lon() : null)
					.build())
				.reviewSummary(ReviewResponse.ReviewSummary.builder()
					.averageRating(doc.averageRating() != null
						? BigDecimal.valueOf(doc.averageRating())
						: BigDecimal.ZERO)
					.totalCount(doc.reviewCount() != null ? doc.reviewCount() : 0)
					.build())
				// .hostName(doc.hostNickname())
				.isInWishlist(isInWishlist)
				.build();
		}
	}

	@Builder
	public record AccommodationSearchInfos(
		List<AccommodationSearchInfo> staySearchResultListing,
		PageInfo pageInfo
	){
	}

	@Builder
	public record PageInfo(
		int pageSize,
		int currentPage,
		int totalPages,
		long totalElements,
		boolean isFirst,
		boolean isLast,
		boolean hasNext,
		boolean hasPrevious
	){
		public static PageInfo fail(int pageSize, int pageNumber) {
			return PageInfo.builder()
				.pageSize(pageSize)
				.currentPage(pageNumber)
				.totalPages(0)
				.totalElements(0)
				.isFirst(true)
				.isLast(true)
				.hasNext(false)
				.hasPrevious(false)
				.build();
		}
	}
}

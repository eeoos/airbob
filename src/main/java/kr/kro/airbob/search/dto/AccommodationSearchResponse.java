package kr.kro.airbob.search.dto;

import java.math.BigDecimal;
import java.util.List;

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
		Long basePrice,
		String currency,
		AddressResponse.AddressSummaryInfo addressInfo,
		AddressResponse.Coordinate coordinate,
		ReviewResponse.ReviewSummary review,
		// String hostName, // todo: 넣을지 여부 결정 필요
		Boolean isInWishlist
	){
		public static AccommodationSearchInfo from(AccommodationDocument doc, boolean isInWishlist) {

			AccommodationDocument.Location location = doc.location();
			return AccommodationSearchInfo.builder()
				.id(doc.accommodationId())
				.name(doc.name())
				.accommodationThumbnailUrl(doc.thumbnailUrl())
				.basePrice(doc.basePrice())
				.currency(doc.currency())
				.addressInfo(AddressResponse.AddressSummaryInfo.from(doc))
				.coordinate(AddressResponse.Coordinate.builder()
					.latitude(location != null ? location.lat() : null)
					.longitude(location != null ? location.lon() : null)
					.build())
				.review(ReviewResponse.ReviewSummary.builder()
					.averageRating(new BigDecimal(String.valueOf(doc.averageRating())))
					.totalCount(doc.reviewCount())
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

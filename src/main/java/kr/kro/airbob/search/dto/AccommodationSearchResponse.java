package kr.kro.airbob.search.dto;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.geo.dto.Coordinate;
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
		String locationSummary, // ex) 동작구 사당동
		String accommodationThumbnailUrl,
		Coordinate coordinate,
		PriceResponse pricePerNight,
		ReviewResponse.ReviewSummary review,
		// String hostName, // todo: 넣을지 여부 결정 필요
		Boolean isInWishlist
	){
		public static AccommodationSearchInfo from(AccommodationDocument doc, boolean isInWishlist) {

			NumberFormat format = NumberFormat.getCurrencyInstance(Locale.KOREA);
			String currencyCode = format.getCurrency().getCurrencyCode();
			String displayName = format.getCurrency().getDisplayName();
			String basePrice = format.format(doc.basePrice());

			return AccommodationSearchInfo.builder()
				.id(doc.accommodationId())
				.name(doc.name())
				.locationSummary(String.format("%s %s", doc.district(), doc.street()))
				.accommodationThumbnailUrl(doc.thumbnailUrl())
				.coordinate(new Coordinate(
					doc.location() != null ? doc.location().lat() : null,
					doc.location() != null ? doc.location().lon() : null
				))
				.pricePerNight(PriceResponse.builder()
					.currencyCode(currencyCode)
					.displayPrice(displayName)
					.price(basePrice)
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

	@Builder
	public record PriceResponse(
		String currencyCode,
		String displayPrice,
		String price
	){
	}
}

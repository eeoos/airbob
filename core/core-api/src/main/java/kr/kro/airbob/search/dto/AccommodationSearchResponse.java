package kr.kro.airbob.search.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationSearchResponse {

	@Builder
	@Schema(name = "AccommodationSearchResponseAccommodationSearchInfo")
	public record AccommodationSearchInfo(
		@Schema(description = "숙소 ID", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		long id,
		@Schema(description = "색인의 숙소 이름. 과거 데이터는 null일 수 있습니다.", example = "서울 하우스", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String name,
		@Schema(description = "색인의 대표 사진 URL. 없으면 null", example = "https://example.invalid/stay.jpg", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String accommodationThumbnailUrl,
		@Schema(description = "색인의 숙소 유형 코드. 과거 데이터는 null일 수 있습니다.", example = "APT", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String type,
		@Schema(description = "색인의 1박 기본 가격. 할인·수수료·숙박일 수를 반영한 총액이 아닙니다. 과거 데이터는 null일 수 있습니다.", example = "100000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Long basePrice,
		@Schema(description = "기본 가격의 통화. 과거 데이터는 null일 수 있습니다.", example = "KRW", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String currency,
		@Schema(description = "색인의 주소 요약. 없는 필드는 null", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.AddressSummaryInfo addressSummary,
		@Schema(description = "색인의 위도·경도. 위치가 없으면 각 필드는 null", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.Coordinate coordinate,
		@Schema(description = "색인의 리뷰 집계. 없으면 0건·0점", requiredMode = Schema.RequiredMode.REQUIRED)
		ReviewResponse.ReviewSummary reviewSummary,
		@Schema(description = "본인의 현재 활성 위시리스트 중 하나라도 포함하는지 여부. 유효한 세션이 없으면 false", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
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
				.isInWishlist(isInWishlist)
				.build();
		}
	}

	@Builder
	@Schema(name = "AccommodationSearchResponseAccommodationSearchInfos")
	public record AccommodationSearchInfos(
		@Schema(description = "현재 페이지의 검색 결과. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<AccommodationSearchInfo> staySearchResultListing,
		@Schema(description = "페이지 정보. 다음 요청 가능 여부는 has_next로 확인합니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		PageInfo pageInfo
	){
	}

	@Builder
	@Schema(name = "AccommodationSearchResponsePageInfo")
	public record PageInfo(
		@Schema(description = "페이지 크기. 항상 18", example = "18", requiredMode = Schema.RequiredMode.REQUIRED)
		int pageSize,
		@Schema(description = "보정된 현재 페이지. 0~14", example = "0", minimum = "0", maximum = "14", requiredMode = Schema.RequiredMode.REQUIRED)
		int currentPage,
		@Schema(description = "total_elements로 계산한 페이지 수. 접근 가능한 15페이지보다 클 수 있습니다.", example = "1", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		int totalPages,
		@Schema(description = "검색에서 확인한 전체 일치 건수. 현재 페이지가 비어도 유지하며 기본 집계 한도(10,000건) 초과 시 하한값일 수 있습니다.", example = "1", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		long totalElements,
		@Schema(description = "현재 페이지가 0인지 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean isFirst,
		@Schema(description = "더 조회할 페이지가 없는지 여부. 14페이지에서는 항상 true", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean isLast,
		@Schema(description = "다음 페이지를 요청할 수 있는지 여부. 14페이지에서는 항상 false", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean hasNext,
		@Schema(description = "현재 페이지가 0보다 큰지 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean hasPrevious
	){
		public static PageInfo fail(int pageSize, int pageNumber) {
			return PageInfo.builder()
				.pageSize(pageSize)
				.currentPage(pageNumber)
				.totalPages(0)
				.totalElements(0)
				.isFirst(pageNumber == 0)
				.isLast(true)
				.hasNext(false)
				.hasPrevious(pageNumber > 0)
				.build();
		}
	}
}

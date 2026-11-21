package kr.kro.airbob.domain.wishlist.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistResponse {

	@Schema(name = "WishlistResponseCreate")
	public record Create(
		@Schema(description = "위시리스트 ID. 목록 수정·삭제·숙소 추가·상세 조회의 wishlistId", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "WishlistResponseUpdate")
	public record Update(
		@Schema(description = "이름을 변경한 위시리스트 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "WishlistResponseMembership")
	public record Membership(
		@Schema(description = "본인의 활성 목록 중 하나라도 숙소를 포함하는지 여부. 숙소의 공개 상태와 무관합니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean isInAnyWishlist,
		@Schema(description = "지정한 본인 목록의 포함 여부. wishlistId 생략·없음·삭제·타인 소유이면 null", nullable = true, example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean targetWishlistContains,
		@Schema(description = "wishlistId가 본인의 활성 목록인지 여부. 생략 시 false", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean targetWishlistFound
	) {
	}

	@Builder
	@Schema(name = "WishlistResponseWishlistInfos")
	public record WishlistInfos(
		@Schema(description = "생성 시각·목록 ID 내림차순의 활성 목록. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<WishlistInfo> wishlists,
		@Schema(description = "다음 페이지 여부·커서·현재 목록 수", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo
	) {
	}

	@Builder
	@Schema(name = "WishlistResponseWishlistInfo")
	public record WishlistInfo(
		@Schema(description = "위시리스트 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
		long id,
		@Schema(description = "현재 위시리스트 이름", example = "서울 여행", requiredMode = Schema.RequiredMode.REQUIRED)
		String name,
		@Schema(description = "목록을 생성한 UTC 시각. 과거 데이터는 null일 수 있습니다.", example = "2027-01-10T02:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(description = "저장한 전체 숙소 수. 비공개 숙소도 포함하므로 상세 목록의 표시 개수와 다를 수 있습니다.", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		Long wishlistItemCount,
		@Schema(description = "가장 최근 추가한 대표 숙소의 현재 썸네일. 대표나 사진이 없으면 null이며 숙소의 공개 상태는 확인하지 않습니다.", nullable = true, example = "https://example.invalid/stay.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
		String thumbnailImageUrl,
		@Schema(description = "accommodationId로 지정한 숙소가 이 목록에 있는지 여부. 조회값 생략 시 null", nullable = true, example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean isContained,
		@Schema(description = "해당 숙소의 연결 ID. 메모 수정·숙소 삭제에 사용합니다. accommodationId 생략 또는 미포함이면 null", nullable = true, example = "501", requiredMode = Schema.RequiredMode.REQUIRED)
		Long wishlistAccommodationId
	) {
	}
}

package kr.kro.airbob.domain.wishlist.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistResponse {

	public record Create(
		long id
	) {
	}

	public record Update(
		long id
	) {
	}

	@Builder
	public record WishlistInfos(
		List<WishlistInfo> wishlists,
		CursorResponse.PageInfo pageInfo
	) {
	}

	@Builder
	public record WishlistInfo(
		long id,
		String name,
		LocalDateTime createdAt,
		Long wishlistItemCount,
		String thumbnailImageUrl,
		Boolean isContained,
		Long wishlistAccommodationId
	) {
	}
}

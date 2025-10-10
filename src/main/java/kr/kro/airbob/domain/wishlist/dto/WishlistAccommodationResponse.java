package kr.kro.airbob.domain.wishlist.dto;

import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistAccommodationResponse {

	public record Create(
		long id
	) {
	}

	public record Update(
		long id
	) {
	}

	public record WishlistAccommodationInfos(
		List<WishlistAccommodationInfo> wishlistAccommodations,
		CursorResponse.PageInfo pageInfo
	) {
	}

	public record WishlistAccommodationInfo(
		long id,
		String name,
		AccommodationResponse.WishlistAccommodationInfo accommodationInfo
	) {
	}
}

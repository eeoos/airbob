package kr.kro.airbob.core.domain.wishlist;

import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** One cursor page of the published accommodations in a wishlist, with the wishlist's current name. */
public record WishlistAccommodationPage(
	String wishlistName,
	List<WishlistAccommodationItem> accommodations,
	CursorResponse.PageInfo pageInfo) {
}

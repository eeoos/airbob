package kr.kro.airbob.core.domain.wishlist;

import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** One cursor page of a member's active wishlists, newest first. */
public record WishlistSummaryPage(
	List<WishlistSummary> wishlists,
	CursorResponse.PageInfo pageInfo) {
}

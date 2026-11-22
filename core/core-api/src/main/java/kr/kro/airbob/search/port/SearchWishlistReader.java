package kr.kro.airbob.search.port;

import java.util.List;
import java.util.Set;

/**
 * Wishlist flags needed by search results. The wishlist domain implements it, so search does not depend on
 * wishlist.
 */
public interface SearchWishlistReader {

	/** IDs among accommodationIds that are published and saved in one of the member's active wishlists. */
	Set<Long> findWishlistedAccommodationIds(Long memberId, List<Long> accommodationIds);
}

package kr.kro.airbob.core.domain.accommodation.port;

/**
 * Wishlist membership needed by accommodation details. The wishlist domain implements it, so accommodation
 * does not depend on wishlist (wishlist already depends on accommodation).
 */
public interface AccommodationWishlistReader {

	boolean isInWishlist(Long accommodationId, Long viewerId);
}

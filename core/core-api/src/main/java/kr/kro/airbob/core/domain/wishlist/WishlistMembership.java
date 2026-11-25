package kr.kro.airbob.core.domain.wishlist;

/**
 * Whether a member saved an accommodation, across their active wishlists and in one target wishlist.
 *
 * @param targetWishlistContains null unless the target is one of the member's active wishlists
 */
public record WishlistMembership(
	boolean inAnyWishlist,
	Boolean targetWishlistContains,
	boolean targetWishlistFound) {
}

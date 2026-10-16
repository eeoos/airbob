package kr.kro.airbob.domain.wishlist.dto;

public record WishlistDeleteVerification(
	long verifiedRows,
	boolean succeeded,
	boolean targetWishlistDeleted,
	boolean targetMembershipsDeleted,
	boolean targetDenormalizedStatePreserved,
	boolean controlWishlistPreserved,
	boolean controlMembershipPreserved,
	boolean accommodationsPreserved
) {
}

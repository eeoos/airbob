package kr.kro.airbob.domain.wishlist.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.port.AccommodationWishlistReader;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.search.port.SearchWishlistReader;
import lombok.RequiredArgsConstructor;

/**
 * Implement component for wishlist reads used by other domains. It implements the ports that accommodation
 * and search own, so those domains reach wishlist data without depending on the wishlist domain.
 */
@Component
@RequiredArgsConstructor
public class WishlistReader implements AccommodationWishlistReader, SearchWishlistReader {

	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	@Override
	public boolean isInWishlist(Long accommodationId, Long viewerId) {
		return wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(
			viewerId, accommodationId);
	}

	@Override
	public Set<Long> findWishlistedAccommodationIds(Long memberId, List<Long> accommodationIds) {
		return wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(
			memberId, accommodationIds);
	}
}

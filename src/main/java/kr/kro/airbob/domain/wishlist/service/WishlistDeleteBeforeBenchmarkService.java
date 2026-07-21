package kr.kro.airbob.domain.wishlist.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistRepository;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class WishlistDeleteBeforeBenchmarkService {

	private final WishlistRepository wishlistRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	public WishlistDeleteBeforeBenchmarkService(
		WishlistRepository wishlistRepository,
		WishlistAccommodationRepository wishlistAccommodationRepository
	) {
		this.wishlistRepository = wishlistRepository;
		this.wishlistAccommodationRepository = wishlistAccommodationRepository;
	}

	@Transactional
	public void deleteWishlist(Long wishlistId, Long memberId) {
		Wishlist wishlist = wishlistRepository.findByIdAndStatus(wishlistId, WishlistStatus.ACTIVE)
			.orElseThrow(WishlistNotFoundException::new);

		if (!wishlist.getMember().getId().equals(memberId)) {
			throw new WishlistAccessDeniedException();
		}

		wishlistAccommodationRepository.deleteAllByWishlistId(wishlist.getId());
		wishlist.delete();
	}
}

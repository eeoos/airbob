package kr.kro.airbob.core.domain.wishlist;

import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.core.domain.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;

/**
 * Implement component for wishlist writes. It keeps the denormalized count and representative accommodation in
 * step with the saved accommodations. Callers pass a wishlist locked by {@link WishlistReader}.
 */
@Component
@RequiredArgsConstructor
public class WishlistWriter {

	private final WishlistRepository wishlistRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	public Wishlist create(Member member, String name) {
		return wishlistRepository.save(Wishlist.builder()
			.name(name)
			.member(member)
			.build());
	}

	/** Deletes the saved accommodations in one batch and marks the wishlist deleted. */
	public void delete(Wishlist wishlist) {
		List<WishlistAccommodation> wishlistAccommodations =
			wishlistAccommodationRepository.findAllByWishlistId(wishlist.getId());
		wishlistAccommodationRepository.deleteAllInBatch(wishlistAccommodations);
		wishlist.delete();
	}

	public WishlistAccommodation addAccommodation(Wishlist wishlist, Accommodation accommodation) {
		if (wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(
			wishlist.getId(), accommodation.getId())) {
			throw new WishlistAccommodationDuplicateException();
		}

		WishlistAccommodation saved = wishlistAccommodationRepository.save(WishlistAccommodation.builder()
			.wishlist(wishlist)
			.accommodation(accommodation)
			.build());

		// 반정규화: 개수 +1, 대표를 방금 추가한 숙소(=최신)로 설정
		wishlistRepository.incrementCountAndSetRepresentative(wishlist.getId(), accommodation.getId());
		return saved;
	}

	public void removeAccommodation(WishlistAccommodation wishlistAccommodation) {
		Long wishlistId = wishlistAccommodation.getWishlist().getId();
		Long removedAccommodationId = wishlistAccommodation.getAccommodation().getId();
		Long currentRepresentative = wishlistAccommodation.getWishlist().getRepresentativeAccommodationId();

		wishlistAccommodationRepository.delete(wishlistAccommodation);
		wishlistAccommodationRepository.flush(); // 대표 재선정 조회가 삭제 반영 후 실행되도록

		wishlistRepository.decrementCount(wishlistId);

		// 삭제된 숙소가 대표였을 때만 다음 최신 1건으로 재선정(없으면 null)
		if (removedAccommodationId.equals(currentRepresentative)) {
			Long newRepresentative = wishlistAccommodationRepository.findLatestAccommodationId(wishlistId);
			wishlistRepository.updateRepresentative(wishlistId, newRepresentative);
		}
	}
}

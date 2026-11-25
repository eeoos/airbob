package kr.kro.airbob.core.domain.wishlist;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.port.AccommodationWishlistReader;
import kr.kro.airbob.core.domain.search.port.SearchWishlistReader;
import kr.kro.airbob.core.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.core.domain.wishlist.repository.WishlistRepository;
import kr.kro.airbob.core.domain.wishlist.repository.projection.WishlistDetailHeaderProjection;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import lombok.RequiredArgsConstructor;

/**
 * Implement component for wishlist reads. It also implements the ports that accommodation and search own, so
 * those domains reach wishlist data without depending on the wishlist domain.
 */
@Component
@RequiredArgsConstructor
public class WishlistReader implements AccommodationWishlistReader, SearchWishlistReader {

	private final WishlistRepository wishlistRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	/**
	 * Locks the member's active wishlist. Every change locks the parent list first, so renames, the stored count,
	 * the representative, and membership deletes are serialized.
	 */
	public Wishlist readOwnedForUpdate(Long wishlistId, Long memberId) {
		Wishlist wishlist = wishlistRepository.findByIdAndStatusForUpdate(wishlistId, WishlistStatus.ACTIVE)
			.orElseThrow(WishlistNotFoundException::new);

		if (!wishlist.getMember().getId().equals(memberId)) {
			throw new WishlistAccessDeniedException();
		}
		return wishlist;
	}

	/** Locks the parent wishlist and then the saved accommodation, in that order. */
	public WishlistAccommodation readOwnedAccommodationForUpdate(Long wishlistAccommodationId, Long memberId) {
		Long wishlistId = wishlistAccommodationRepository
			.findWishlistIdByIdAndMemberId(wishlistAccommodationId, memberId)
			.orElseThrow(WishlistAccommodationAccessDeniedException::new);
		wishlistRepository.findByIdAndStatusForUpdate(wishlistId, WishlistStatus.ACTIVE)
			.filter(wishlist -> wishlist.getMember().getId().equals(memberId))
			.orElseThrow(WishlistAccommodationAccessDeniedException::new);
		// 앞선 ID 조회가 만든 RR 스냅샷 대신 잠금 대기 후의 현재 연결을 다시 확인한다.
		return wishlistAccommodationRepository.findByIdForUpdate(wishlistAccommodationId)
			.orElseThrow(WishlistAccommodationAccessDeniedException::new);
	}

	/** The name of the member's active wishlist, after an ownership check without a lock. */
	public String readOwnedName(Long wishlistId, Long memberId) {
		WishlistDetailHeaderProjection header = wishlistRepository
			.findDetailHeaderByIdAndStatus(wishlistId, WishlistStatus.ACTIVE)
			.orElseThrow(WishlistNotFoundException::new);
		if (!header.memberId().equals(memberId)) {
			throw new WishlistAccessDeniedException();
		}
		return header.name();
	}

	/** accommodationId is optional; when given, each summary carries that accommodation's saved link. */
	public Slice<WishlistSummary> readSummaries(Long memberId, Long accommodationId,
		CursorRequest.CursorPageRequest page) {
		return wishlistRepository.findSummariesByMemberIdAndStatusWithCursor(
			memberId,
			WishlistStatus.ACTIVE,
			page.lastId(),
			page.lastCreatedAt(),
			accommodationId,
			PageRequest.of(0, page.size()));
	}

	public Slice<WishlistAccommodationItem> readPublishedAccommodations(Long wishlistId,
		CursorRequest.CursorPageRequest page) {
		return wishlistAccommodationRepository.findAccommodationsInWishlist(
			wishlistId, page.lastId(), page.lastCreatedAt(), PageRequest.of(0, page.size()));
	}

	public WishlistMembership readMembership(Long memberId, Long accommodationId, Long wishlistId) {
		var membership = wishlistAccommodationRepository.findMembership(memberId, accommodationId, wishlistId);
		boolean targetFound = membership.getTargetWishlist() != 0;
		return new WishlistMembership(membership.getAnyMembership() != 0,
			targetFound ? membership.getTargetMembership() != 0 : null, targetFound);
	}

	@Override
	public boolean isInWishlist(Long accommodationId, Long viewerId) {
		return wishlistAccommodationRepository.existsByWishlistMemberIdAndAccommodationId(
			viewerId, accommodationId);
	}

	@Override
	public Set<Long> findWishlistedAccommodationIds(Long memberId, List<Long> accommodationIds) {
		return wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(
			memberId, accommodationIds);
	}
}

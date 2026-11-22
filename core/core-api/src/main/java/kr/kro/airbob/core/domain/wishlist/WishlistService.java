package kr.kro.airbob.core.domain.wishlist;

import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.service.AccommodationReader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.service.MemberReader;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WishlistService {

	private final MemberReader memberReader;
	private final AccommodationReader accommodationReader;
	private final WishlistReader wishlistReader;
	private final WishlistWriter wishlistWriter;

	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional
	public Long createWishlist(String name, Long memberId) {
		Member member = memberReader.readActive(memberId);
		return wishlistWriter.create(member, name).getId();
	}

	@Transactional
	public Long updateWishlist(Long wishlistId, String name, Long memberId) {
		Wishlist wishlist = wishlistReader.readOwnedForUpdate(wishlistId, memberId);
		wishlist.updateName(name);
		return wishlist.getId();
	}

	@Transactional
	public void deleteWishlist(Long wishlistId, Long memberId) {
		Wishlist wishlist = wishlistReader.readOwnedForUpdate(wishlistId, memberId);
		wishlistWriter.delete(wishlist);
	}

	@Transactional(readOnly = true)
	public WishlistSummaryPage findWishlists(CursorRequest.CursorPageRequest page, Long memberId,
		Long accommodationId) {
		Slice<WishlistSummary> slice = wishlistReader.readSummaries(memberId, accommodationId, page);
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(), WishlistSummary::id, WishlistSummary::createdAt);
		return new WishlistSummaryPage(slice.getContent(), pageInfo);
	}

	@Transactional
	public Long createWishlistAccommodation(Long wishlistId, Long accommodationId, Long memberId) {
		Wishlist wishlist = wishlistReader.readOwnedForUpdate(wishlistId, memberId);
		Accommodation accommodation = accommodationReader.readPublished(accommodationId);
		return wishlistWriter.addAccommodation(wishlist, accommodation).getId();
	}

	@Transactional
	public Long updateWishlistAccommodation(Long wishlistAccommodationId, String memo, Long memberId) {
		WishlistAccommodation wishlistAccommodation =
			wishlistReader.readOwnedAccommodationForUpdate(wishlistAccommodationId, memberId);
		wishlistAccommodation.updateMemo(memo);
		return wishlistAccommodation.getId();
	}

	@Transactional
	public void deleteWishlistAccommodation(Long wishlistAccommodationId, Long memberId) {
		WishlistAccommodation wishlistAccommodation =
			wishlistReader.readOwnedAccommodationForUpdate(wishlistAccommodationId, memberId);
		wishlistWriter.removeAccommodation(wishlistAccommodation);
	}

	@Transactional(readOnly = true)
	public WishlistMembership findMembership(Long memberId, Long accommodationId, Long wishlistId) {
		return wishlistReader.readMembership(memberId, accommodationId, wishlistId);
	}

	@Transactional(readOnly = true)
	public WishlistAccommodationPage findWishlistAccommodations(Long wishlistId,
		CursorRequest.CursorPageRequest page, Long memberId) {
		String wishlistName = wishlistReader.readOwnedName(wishlistId, memberId);
		Slice<WishlistAccommodationItem> slice = wishlistReader.readPublishedAccommodations(wishlistId, page);
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(),
			slice.hasNext(),
			WishlistAccommodationItem::wishlistAccommodationId,
			WishlistAccommodationItem::createdAt);
		return new WishlistAccommodationPage(wishlistName, slice.getContent(), pageInfo);
	}
}

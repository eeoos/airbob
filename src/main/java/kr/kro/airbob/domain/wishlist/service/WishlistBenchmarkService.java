package kr.kro.airbob.domain.wishlist.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("read-model-benchmark")
@RequiredArgsConstructor
public class WishlistBenchmarkService {

	private final WishlistRepository wishlistRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;
	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional(readOnly = true)
	public WishlistResponse.WishlistInfos findWishlistsBefore(
		CursorRequest.CursorPageRequest request,
		Long memberId,
		Long accommodationId
	) {
		Long lastId = request.lastId();
		LocalDateTime lastCreatedAt = request.lastCreatedAt();
		Slice<Wishlist> wishlistSlice = wishlistRepository.findByMemberIdAndStatusWithCursor(
			memberId,
			WishlistStatus.ACTIVE,
			lastId,
			lastCreatedAt,
			PageRequest.of(0, request.size())
		);

		List<Wishlist> wishlists = wishlistSlice.getContent();
		if (wishlists.isEmpty()) {
			return WishlistResponse.WishlistInfos.builder()
				.wishlists(Collections.emptyList())
				.pageInfo(cursorPageInfoCreator.createPageInfo(
					Collections.emptyList(),
					false,
					Wishlist::getId,
					Wishlist::getCreatedAt
				))
				.build();
		}

		List<Long> wishlistIds = wishlists.stream()
			.map(Wishlist::getId)
			.toList();
		Map<Long, Long> wishlistItemCounts =
			wishlistAccommodationRepository.countByWishlistIds(wishlistIds);
		Map<Long, String> thumbnailUrls = wishlistAccommodationRepository
			.findLatestThumbnailUrlsByWishlistIds(wishlistIds)
			.stream()
			.filter(info -> info.getThumbnail_url() != null)
			.collect(Collectors.toMap(
				WishlistAccommodationRepository.WishlistThumbnailInfo::getWishlist_id,
				WishlistAccommodationRepository.WishlistThumbnailInfo::getThumbnail_url
			));

		Map<Long, Long> accommodationStatusMap = accommodationId == null
			? Collections.emptyMap()
			: wishlistAccommodationRepository
				.findWishlistAccIdsMapByWishlistIdsAndAccId(wishlistIds, accommodationId);

		List<WishlistResponse.WishlistInfo> wishlistInfos = wishlists.stream()
			.map(wishlist -> toWishlistInfo(
				wishlist,
				wishlistItemCounts,
				thumbnailUrls,
				accommodationStatusMap,
				accommodationId != null
			))
			.toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			wishlists,
			wishlistSlice.hasNext(),
			Wishlist::getId,
			Wishlist::getCreatedAt
		);
		return new WishlistResponse.WishlistInfos(wishlistInfos, pageInfo);
	}

	private WishlistResponse.WishlistInfo toWishlistInfo(
		Wishlist wishlist,
		Map<Long, Long> wishlistItemCounts,
		Map<Long, String> thumbnailUrls,
		Map<Long, Long> accommodationStatusMap,
		boolean includeAccommodationStatus
	) {
		Long wishlistId = wishlist.getId();
		Long wishlistAccommodationId = accommodationStatusMap.get(wishlistId);
		return new WishlistResponse.WishlistInfo(
			wishlistId,
			wishlist.getName(),
			wishlist.getCreatedAt(),
			wishlistItemCounts.getOrDefault(wishlistId, 0L),
			thumbnailUrls.get(wishlistId),
			includeAccommodationStatus ? wishlistAccommodationId != null : null,
			includeAccommodationStatus ? wishlistAccommodationId : null
		);
	}
}

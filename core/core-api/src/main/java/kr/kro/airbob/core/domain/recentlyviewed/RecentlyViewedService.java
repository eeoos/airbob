package kr.kro.airbob.core.domain.recentlyviewed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.core.domain.wishlist.WishlistReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecentlyViewedService {

	private final RecentlyViewedReader recentlyViewedReader;
	private final RecentlyViewedWriter recentlyViewedWriter;
	private final AccommodationReader accommodationReader;
	private final WishlistReader wishlistReader;

	public void addRecentlyViewed(Long accommodationId, Long memberId) {
		recentlyViewedWriter.add(accommodationId, memberId);
	}

	public void removeRecentlyViewed(Long accommodationId, Long memberId) {
		recentlyViewedWriter.remove(accommodationId, memberId);
	}

	/** 최근에 본 순서로 현재 공개 숙소만 돌려준다. 더는 공개되지 않은 숙소의 기록은 이때 지운다. */
	@Transactional(readOnly = true)
	public List<RecentlyViewedAccommodation> getRecentlyViewed(Long memberId) {
		List<RecentlyViewedEntry> entries = recentlyViewedReader.readRecent(memberId);
		if (entries.isEmpty()) {
			return List.of();
		}

		// Redis에서 모든 ID를 Set으로 추출
		Set<Long> accommodationIdsFromRedis = entries.stream()
			.map(RecentlyViewedEntry::accommodationId)
			.collect(Collectors.toSet());

		// DB에서 존재하는 숙소 정보만 조회
		List<RecentlyViewedAccommodationProjection> accommodationsInDb = accommodationReader
			.findWithAddressAndReviewSummaryByIdInAndStatus(
				new ArrayList<>(accommodationIdsFromRedis), AccommodationStatus.PUBLISHED);

		Map<Long, RecentlyViewedAccommodationProjection> accommodationMap = accommodationsInDb.stream()
			.collect(
				Collectors.toMap(RecentlyViewedAccommodationProjection::accommodationId, projection -> projection));

		Set<Long> existingIdsInDb = accommodationMap.keySet();
		List<Long> idsToDeleteFromRedis = accommodationIdsFromRedis.stream()
			.filter(id -> !existingIdsInDb.contains(id))
			.toList();

		if (!idsToDeleteFromRedis.isEmpty()) {
			log.info("Redis에서 삭제된 숙소 ID 제거: {}", idsToDeleteFromRedis);
			recentlyViewedWriter.removeAll(idsToDeleteFromRedis, memberId);
		}

		if (existingIdsInDb.isEmpty()) {
			return List.of();
		}

		List<Long> existingIdList = new ArrayList<>(existingIdsInDb);
		Map<Long, Boolean> wishlistMap = getWishlistMap(memberId, existingIdList);

		return entries.stream()
			.map(entry -> {
				RecentlyViewedAccommodationProjection projection = accommodationMap.get(entry.accommodationId());
				if (projection == null) {
					return null;
				}
				return new RecentlyViewedAccommodation(entry.viewedAt(), projection,
					wishlistMap.getOrDefault(entry.accommodationId(), false));
			})
			.filter(Objects::nonNull)
			.toList();
	}

	private Map<Long, Boolean> getWishlistMap(Long memberId, List<Long> accommodationIds) {
		Set<Long> wishlistAccommodationIds = wishlistReader
			.findWishlistedAccommodationIds(memberId, accommodationIds);

		return accommodationIds.stream()
			.collect(Collectors.toMap(
				id -> id,
				wishlistAccommodationIds::contains));
	}
}

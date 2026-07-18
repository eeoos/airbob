package kr.kro.airbob.domain.recentlyViewed.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecentlyViewedService {

	private final RedisTemplate<String, String> redisTemplate;
	private final AccommodationRepository accommodationRepository;
	private final AccommodationReviewSummaryRepository summaryRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;


	private static final String RECENTLY_VIEWED_KEY_PREFIX = "recently_viewed:";
	private static final int MAX_COUNT = 100;
	private static final long TTL_DAYS = 7;

	public void addRecentlyViewed(Long accommodationId, Long memberId) {
		String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;
		long timestamp = System.currentTimeMillis();

		redisTemplate.opsForZSet().add(key, accommodationId.toString(), timestamp);

		Long currentSize = redisTemplate.opsForZSet().size(key);
		if (currentSize != null && currentSize > MAX_COUNT) {
			redisTemplate.opsForZSet().removeRange(key, 0, currentSize - MAX_COUNT - 1);
		}
		redisTemplate.expire(key, Duration.ofDays(TTL_DAYS));
	}

	public void removeRecentlyViewed(Long accommodationId, Long memberId) {
		String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;
		redisTemplate.opsForZSet().remove(key, accommodationId.toString());
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.RecentlyViewedAccommodationInfos getRecentlyViewed(Long memberId) {
		String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

		Set<ZSetOperations.TypedTuple<String>> recentlyViewedWithScores = redisTemplate.opsForZSet()
			.reverseRangeWithScores(key, 0, -1);

		if (recentlyViewedWithScores == null || recentlyViewedWithScores.isEmpty()) {
			return AccommodationResponse.RecentlyViewedAccommodationInfos.builder()
				.accommodations(new ArrayList<>())
				.totalCount(0)
				.build();
		}

		// Redis에서 모든 ID를 Set으로 추출
		Set<Long> accommodationIdsFromRedis = recentlyViewedWithScores.stream()
			.map(tuple -> Long.parseLong(tuple.getValue()))
			.collect(Collectors.toSet());

		// DB에서 존재하는 숙소 정보만 조회
		List<Accommodation> accommodationsInDb = accommodationRepository.findWithAddressByIdAndStatusIn(new ArrayList<>(accommodationIdsFromRedis), AccommodationStatus.PUBLISHED);
		Map<Long, Accommodation> accommodationMap = accommodationsInDb.stream()
			.collect(Collectors.toMap(Accommodation::getId, accommodation -> accommodation));

		Set<Long> existingIdsInDb = accommodationMap.keySet();
		List<String> idsToDeleteFromRedis = accommodationIdsFromRedis.stream()
			.filter(id -> !existingIdsInDb.contains(id))
			.map(String::valueOf)
			.toList();

		if (!idsToDeleteFromRedis.isEmpty()) {
			log.info("Redis에서 삭제된 숙소 ID 제거: {}", idsToDeleteFromRedis);
			redisTemplate.opsForZSet().remove(key, idsToDeleteFromRedis.toArray(new Object[0]));
		}

		List<Long> existingIdList = new ArrayList<>(existingIdsInDb);
		Map<Long, ReviewResponse.ReviewSummary> reviewSummaryMap = getReviewSummaryMap(existingIdList);
		Map<Long, Boolean> wishlistMap = getWishlistMap(memberId, existingIdList);

		List<AccommodationResponse.RecentlyViewedAccommodationInfo> recentlyViewedAccommodationInfos = recentlyViewedWithScores.stream()
			.map(tuple -> {
				Long accommodationId = Long.parseLong(tuple.getValue());
				Accommodation accommodation = accommodationMap.get(accommodationId);

				if (accommodation == null) {
					return null;
				}

				LocalDateTime viewedAt = Instant.ofEpochMilli(tuple.getScore().longValue())
					.atZone(ZoneId.systemDefault())
					.toLocalDateTime();

				ReviewResponse.ReviewSummary reviewSummary = reviewSummaryMap.get(accommodationId);

				return AccommodationResponse.RecentlyViewedAccommodationInfo.from(viewedAt, accommodation,
					reviewSummary, wishlistMap.getOrDefault(accommodationId, false));
			})
			.filter(Objects::nonNull)
			.toList();
		return AccommodationResponse.RecentlyViewedAccommodationInfos.from(recentlyViewedAccommodationInfos);
	}

	/**
	 * naive 항목별 조회를 의도적으로 유지해 4N 쿼리 기준선을 재현한다.
	 */
	@Transactional(readOnly = true)
	public AccommodationResponse.RecentlyViewedAccommodationInfos getRecentlyViewedBefore(Long memberId) {
		String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;
		Set<ZSetOperations.TypedTuple<String>> recentlyViewedWithScores = redisTemplate.opsForZSet()
			.reverseRangeWithScores(key, 0, -1);

		if (recentlyViewedWithScores == null || recentlyViewedWithScores.isEmpty()) {
			return AccommodationResponse.RecentlyViewedAccommodationInfos.builder()
				.accommodations(new ArrayList<>())
				.totalCount(0)
				.build();
		}

		List<AccommodationResponse.RecentlyViewedAccommodationInfo> accommodationInfos = new ArrayList<>();
		List<String> idsToDeleteFromRedis = new ArrayList<>();

		for (ZSetOperations.TypedTuple<String> tuple : recentlyViewedWithScores) {
			Long accommodationId = Long.parseLong(tuple.getValue());
			Accommodation accommodation = accommodationRepository
				.findByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
				.orElse(null);

			if (accommodation == null) {
				idsToDeleteFromRedis.add(String.valueOf(accommodationId));
				continue;
			}

			ReviewResponse.ReviewSummary reviewSummary = summaryRepository.findByAccommodationId(accommodationId)
				.map(ReviewResponse.ReviewSummary::of)
				.orElse(null);
			boolean isInWishlist = wishlistAccommodationRepository
				.existsByWishlist_Member_IdAndAccommodation_Id(memberId, accommodationId);
			LocalDateTime viewedAt = Instant.ofEpochMilli(tuple.getScore().longValue())
				.atZone(ZoneId.systemDefault())
				.toLocalDateTime();

			accommodationInfos.add(AccommodationResponse.RecentlyViewedAccommodationInfo.from(
				viewedAt,
				accommodation,
				reviewSummary,
				isInWishlist
			));
		}

		if (!idsToDeleteFromRedis.isEmpty()) {
			redisTemplate.opsForZSet().remove(key, idsToDeleteFromRedis.toArray(new Object[0]));
		}

		return AccommodationResponse.RecentlyViewedAccommodationInfos.from(accommodationInfos);
	}

	private Map<Long, Boolean> getWishlistMap(Long memberId, List<Long> accommodationIds) {
		Set<Long> wishlistAccommodationIds = wishlistAccommodationRepository
			.findAccommodationIdsByMemberIdAndAccommodationIds(memberId, accommodationIds);

		return accommodationIds.stream()
			.collect(Collectors.toMap(
				id -> id,
				wishlistAccommodationIds::contains
			));
	}

	private Map<Long, ReviewResponse.ReviewSummary> getReviewSummaryMap(List<Long> accommodationIds) {
		List<AccommodationReviewSummary> summaries = summaryRepository.findByAccommodationIdIn(
			accommodationIds);

		return summaries.stream()
			.collect(Collectors.toMap(
				AccommodationReviewSummary::getAccommodationId,
				ReviewResponse.ReviewSummary::of
			));
	}
}

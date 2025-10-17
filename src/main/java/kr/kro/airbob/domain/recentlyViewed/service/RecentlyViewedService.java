package kr.kro.airbob.domain.recentlyViewed.service;

import java.math.BigDecimal;
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

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
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
	private final AccommodationAmenityRepository accommodationAmenityRepository;
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
		List<Accommodation> accommodationsInDb = accommodationRepository.findByIdInAndStatus(new ArrayList<>(accommodationIdsFromRedis), AccommodationStatus.PUBLISHED);
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
		Map<Long, BigDecimal> reviewRatingMap = getReviewRatingMap(existingIdList);
		Map<Long, List<AccommodationResponse.AmenityInfoResponse>> amenityMap = getAmenityMap(existingIdList);
		Map<Long, Boolean> wishlistMap = getWishlistMap(memberId, existingIdList);

		List<AccommodationResponse.RecentlyViewedAccommodationInfo> recentlyViewedAccommodationInfos = recentlyViewedWithScores.stream()
			.map(tuple -> {
				Long accommodationId = Long.parseLong(tuple.getValue());
				Accommodation accommodation = accommodationMap.get(accommodationId);

				if (accommodation == null) {
					return null;
				}

				LocalDateTime viewAt = Instant.ofEpochMilli(tuple.getScore().longValue())
					.atZone(ZoneId.systemDefault())
					.toLocalDateTime();

				return AccommodationResponse.RecentlyViewedAccommodationInfo.builder()
					.viewedAt(viewAt)
					.accommodationId(accommodationId)
					.accommodationName(accommodation.getName())
					.thumbnailUrl(accommodation.getThumbnailUrl())
					.amenities(amenityMap.getOrDefault(accommodationId, List.of()))
					.averageRating(reviewRatingMap.get(accommodationId))
					.isInWishlist(wishlistMap.getOrDefault(accommodationId, false))
					.build();
			})
			.filter(Objects::nonNull)
			.toList();

		return AccommodationResponse.RecentlyViewedAccommodationInfos.builder()
			.accommodations(recentlyViewedAccommodationInfos)
			.totalCount(recentlyViewedAccommodationInfos.size())
			.build();
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

	private Map<Long, BigDecimal> getReviewRatingMap(List<Long> accommodationIds) {
		List<AccommodationReviewSummary> summaries = summaryRepository.findByAccommodationIdIn(
			accommodationIds);

		return summaries.stream()
			.collect(Collectors.toMap(
				AccommodationReviewSummary::getAccommodationId,
				AccommodationReviewSummary::getAverageRating
			));
	}


	private Map<Long, List<AccommodationResponse.AmenityInfoResponse>> getAmenityMap(
		List<Long> accommodationIds) {

		List<AccommodationAmenity> results =
			accommodationAmenityRepository.findAccommodationAmenitiesByAccommodationIds(accommodationIds);

		return results.stream()
			.collect(Collectors.groupingBy(
				aa -> aa.getAccommodation().getId(),
				Collectors.mapping(
					result -> new AccommodationResponse.AmenityInfoResponse(
						result.getAmenity().getName(),
						result.getCount()
					),
					Collectors.toList()
				)));
	}
}

package kr.kro.airbob.domain.recentlyViewed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("최근 조회 서비스 테스트")
class RecentlyViewedServiceTest {

	@InjectMocks
	private RecentlyViewedService recentlyViewedService;

	@Mock
	private RedisTemplate<String, String> redisTemplate;

	@Mock
	private AccommodationRepository accommodationRepository;

	@Mock
	private AccommodationAmenityRepository accommodationAmenityRepository;

	@Mock
	private WishlistAccommodationRepository wishlistAccommodationRepository;

	@Mock
	private AccommodationReviewSummaryRepository summaryRepository;

	@Mock
	private ZSetOperations<String, String> zSetOperations;

	private static final String RECENTLY_VIEWED_KEY_PREFIX = "recently_viewed:";
	private static final int MAX_COUNT = 100;
	private static final long TTL_DAYS = 7;

	@BeforeEach
	void setUp() {
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
	}

	@Nested
	@DisplayName("최근 조회 내역 추가 테스트")
	class AddRecentlyViewedTest {

		@Test
		@DisplayName("새로운 숙소를 최근 조회 내역에 추가한다")
		void addRecentlyViewed_NewAccommodation() {
			// given
			Long memberId = 1L;
			Long accommodationId = 100L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			given(zSetOperations.size(key)).willReturn(10L);

			// when
			recentlyViewedService.addRecentlyViewed(memberId, accommodationId);

			// then
			ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
			ArgumentCaptor<Double> scoreCaptor = ArgumentCaptor.forClass(Double.class);

			verify(zSetOperations).add(keyCaptor.capture(), valueCaptor.capture(), scoreCaptor.capture());
			verify(redisTemplate).expire(eq(key), eq(Duration.ofDays(TTL_DAYS)));

			assertThat(keyCaptor.getValue()).isEqualTo(key);
			assertThat(valueCaptor.getValue()).isEqualTo(accommodationId.toString());
			assertThat(scoreCaptor.getValue()).isCloseTo(System.currentTimeMillis(), within(1000.0));
		}

		@Test
		@DisplayName("최대 개수를 초과하면 오래된 항목을 제거한다")
		void addRecentlyViewed_ExceedsMaxCount() {
			// given
			Long memberId = 1L;
			Long accommodationId = 100L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			given(zSetOperations.size(key)).willReturn(101L); // MAX_COUNT + 1

			// when
			recentlyViewedService.addRecentlyViewed(memberId, accommodationId);

			// then
			verify(zSetOperations).add(eq(key), eq(accommodationId.toString()), anyDouble());
			verify(zSetOperations).removeRange(eq(key), eq(0L), eq(0L)); // 가장 오래된 1개 제거
			verify(redisTemplate).expire(eq(key), eq(Duration.ofDays(TTL_DAYS)));
		}

		@Test
		@DisplayName("크기가 null이어도 정상적으로 처리한다")
		void addRecentlyViewed_NullSize() {
			// given
			Long memberId = 1L;
			Long accommodationId = 100L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			given(zSetOperations.size(key)).willReturn(null);

			// when
			recentlyViewedService.addRecentlyViewed(memberId, accommodationId);

			// then
			verify(zSetOperations).add(eq(key), eq(accommodationId.toString()), anyDouble());
			verify(zSetOperations, never()).removeRange(any(), anyLong(), anyLong());
			verify(redisTemplate).expire(eq(key), eq(Duration.ofDays(TTL_DAYS)));
		}
	}

	@Nested
	@DisplayName("최근 조회 내역 삭제 테스트")
	class RemoveRecentlyViewedTest {

		@Test
		@DisplayName("특정 숙소를 최근 조회 내역에서 삭제한다")
		void removeRecentlyViewed() {
			// given
			Long memberId = 1L;
			Long accommodationId = 100L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			// when
			recentlyViewedService.removeRecentlyViewed(memberId, accommodationId);

			// then
			verify(zSetOperations).remove(key, accommodationId.toString());
		}
	}

	@Nested
	@DisplayName("최근 조회 내역 조회 테스트")
	class GetRecentlyViewedTest {

		@Test
		@DisplayName("최근 조회 내역을 올바르게 조회한다")
		void getRecentlyViewed_Success() {
			// given
			Long memberId = 1L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			// Redis에서 반환할 데이터 설정
			Set<ZSetOperations.TypedTuple<String>> mockTuples = Set.of(
				createTypedTuple("100", System.currentTimeMillis() - 1000),
				createTypedTuple("101", System.currentTimeMillis() - 3600000)
			);

			given(zSetOperations.reverseRangeWithScores(key, 0, -1)).willReturn(mockTuples);

			// Entity Repository에서 반환할 데이터 설정 (projection에서 entity 조회로 변경됨)
			List<Accommodation> accommodations = List.of(
				createAccommodation(100L, "서울 중심가 원룸", "image1.jpg"),
				createAccommodation(101L, "강남역 스튜디오", "image2.jpg")
			);
			given(accommodationRepository.findByIdIn(anyList()))
				.willReturn(accommodations);

			// 리뷰 평점 데이터
			List<AccommodationReviewSummary> reviewSummaries = List.of(
				createReviewSummary(100L, BigDecimal.valueOf(4.5)),
				createReviewSummary(101L, BigDecimal.valueOf(4.2))
			);
			given(summaryRepository.findByAccommodationIdIn(anyList()))
				.willReturn(reviewSummaries);

			// 편의시설 데이터
			List<AccommodationAmenity> amenities = List.of(
				createAccommodationAmenity(100L, AmenityType.WIFI, 1),
				createAccommodationAmenity(100L, AmenityType.PARKING, 1),
				createAccommodationAmenity(101L, AmenityType.KITCHEN, 1)
			);
			given(accommodationAmenityRepository.findAccommodationAmenitiesByAccommodationIds(anyList()))
				.willReturn(amenities);

			// 위시리스트 데이터
			given(wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(
				eq(memberId), anyList())).willReturn(Set.of(100L));

			// when
			AccommodationResponse.RecentlyViewedAccommodations result =
				recentlyViewedService.getRecentlyViewed(memberId);

			// then
			assertThat(result.accommodations()).hasSize(2);
			assertThat(result.totalCount()).isEqualTo(2);

			// ID 존재 여부만 확인 (순서 무관)
			List<Long> accommodationIds = result.accommodations().stream()
				.map(AccommodationResponse.RecentlyViewedAccommodation::accommodationId)
				.toList();
			assertThat(accommodationIds).containsExactlyInAnyOrder(100L, 101L);
		}

		@Test
		@DisplayName("최근 조회 내역이 비어있으면 빈 결과를 반환한다")
		void getRecentlyViewed_Empty() {
			// given
			Long memberId = 1L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			given(zSetOperations.reverseRangeWithScores(key, 0, -1)).willReturn(null);

			// when
			AccommodationResponse.RecentlyViewedAccommodations result =
				recentlyViewedService.getRecentlyViewed(memberId);

			// then
			assertThat(result.accommodations()).isEmpty();
			assertThat(result.totalCount()).isEqualTo(0);

			// 빈 결과일 때는 다른 repository 호출이 일어나지 않아야 함
			verify(accommodationRepository, never()).findByIdIn(any());
			verify(summaryRepository, never()).findByAccommodationIdIn(any());
			verify(accommodationAmenityRepository, never()).findAccommodationAmenitiesByAccommodationIds(any());
			verify(wishlistAccommodationRepository, never()).findAccommodationIdsByMemberIdAndAccommodationIds(any(), any());
		}

		@Test
		@DisplayName("Redis에는 있지만 DB에서 삭제된 숙소는 필터링된다")
		void getRecentlyViewed_FilterDeletedAccommodations() {
			// given
			Long memberId = 1L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			// Redis에는 3개 숙소가 있음 (102번은 삭제된 숙소)
			Set<ZSetOperations.TypedTuple<String>> mockTuples = Set.of(
				createTypedTuple("100", System.currentTimeMillis() - 1000),
				createTypedTuple("101", System.currentTimeMillis() - 2000),
				createTypedTuple("102", System.currentTimeMillis() - 3000) // 삭제된 숙소
			);

			given(zSetOperations.reverseRangeWithScores(key, 0, -1)).willReturn(mockTuples);

			// DB에는 2개 숙소만 존재 (102번은 삭제됨)
			List<Accommodation> accommodations = List.of(
				createAccommodation(100L, "서울 중심가 원룸", "image1.jpg"),
				createAccommodation(101L, "강남역 스튜디오", "image2.jpg")
			);
			given(accommodationRepository.findByIdIn(anyList()))
				.willReturn(accommodations);

			// 리뷰 평점 데이터 (순서에 상관없이 anyList() 사용)
			List<AccommodationReviewSummary> reviewSummaries = List.of(
				createReviewSummary(100L, BigDecimal.valueOf(4.5)),
				createReviewSummary(101L, BigDecimal.valueOf(4.2))
			);
			given(summaryRepository.findByAccommodationIdIn(anyList()))
				.willReturn(reviewSummaries);

			given(accommodationAmenityRepository.findAccommodationAmenitiesByAccommodationIds(anyList()))
				.willReturn(List.of());
			given(wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(any(), any()))
				.willReturn(Set.of());

			// when
			AccommodationResponse.RecentlyViewedAccommodations result =
				recentlyViewedService.getRecentlyViewed(memberId);

			// then
			assertThat(result.accommodations()).hasSize(2); // 삭제된 숙소는 제외
			assertThat(result.totalCount()).isEqualTo(2);

			// 순서에 관계없이 ID만 확인
			List<Long> resultIds = result.accommodations().stream()
				.map(AccommodationResponse.RecentlyViewedAccommodation::accommodationId)
				.toList();
			assertThat(resultIds).containsExactlyInAnyOrder(100L, 101L);

			// Redis에서 삭제된 숙소 제거 확인
			verify(zSetOperations).remove(key, "102");
		}

		@Test
		@DisplayName("편의시설과 위시리스트 정보가 올바르게 매핑된다")
		void getRecentlyViewed_CorrectMapping() {
			// given
			Long memberId = 1L;
			String key = RECENTLY_VIEWED_KEY_PREFIX + memberId;

			Set<ZSetOperations.TypedTuple<String>> mockTuples = Set.of(
				createTypedTuple("100", System.currentTimeMillis())
			);

			given(zSetOperations.reverseRangeWithScores(key, 0, -1)).willReturn(mockTuples);

			List<Accommodation> accommodations = List.of(
				createAccommodation(100L, "테스트 숙소", "image.jpg")
			);
			given(accommodationRepository.findByIdIn(anyList()))
				.willReturn(accommodations);

			// 리뷰 평점
			List<AccommodationReviewSummary> reviewSummaries = List.of(
				createReviewSummary(100L, BigDecimal.valueOf(4.0))
			);
			given(summaryRepository.findByAccommodationIdIn(anyList()))
				.willReturn(reviewSummaries);

			// 편의시설: WiFi 2개, 주차장 1개
			List<AccommodationAmenity> amenities = List.of(
				createAccommodationAmenity(100L, AmenityType.WIFI, 2),
				createAccommodationAmenity(100L, AmenityType.PARKING, 1)
			);
			given(accommodationAmenityRepository.findAccommodationAmenitiesByAccommodationIds(anyList()))
				.willReturn(amenities);

			// 위시리스트에 포함됨
			given(wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(
				eq(memberId), anyList())).willReturn(Set.of(100L));

			// when
			AccommodationResponse.RecentlyViewedAccommodations result =
				recentlyViewedService.getRecentlyViewed(memberId);

			// then
			AccommodationResponse.RecentlyViewedAccommodation accommodation = result.accommodations().get(0);

			assertThat(accommodation.amenities()).hasSize(2);
			assertThat(accommodation.amenities()).extracting("type")
				.containsExactlyInAnyOrder(AmenityType.WIFI, AmenityType.PARKING);
			assertThat(accommodation.amenities()).extracting("count")
				.containsExactlyInAnyOrder(2, 1);
			assertThat(accommodation.isInWishlist()).isTrue();
		}

		// Helper methods - projection 대신 entity 생성 메서드들로 변경
		private Accommodation createAccommodation(Long id, String name, String thumbnailUrl) {
			return Accommodation.builder()
				.id(id)
				.name(name)
				.thumbnailUrl(thumbnailUrl)
				.build();
		}

		private AccommodationReviewSummary createReviewSummary(Long accommodationId, BigDecimal averageRating) {
			Accommodation accommodation = Accommodation.builder()
				.id(accommodationId)
				.build();

			AccommodationReviewSummary summary = AccommodationReviewSummary.builder()
				.accommodation(accommodation)
				.build();

			// averageRating을 직접 설정할 수 없으므로, addReview()를 통해 평점 추가
			// 4.5 평점을 만들기 위해 5점짜리 리뷰 9개, 4점짜리 리뷰 1개 추가 (평균 4.9 → 원하는 값에 맞게 조정)
			// 간단하게 하기 위해 Reflection을 사용하거나, 테스트용 averageRating 직접 설정
			try {
				java.lang.reflect.Field field = AccommodationReviewSummary.class.getDeclaredField("averageRating");
				field.setAccessible(true);
				field.set(summary, averageRating);
			} catch (Exception e) {
				// Reflection 실패 시 addReview로 대체
				int rating = averageRating.intValue();
				summary.addReview(rating);
			}

			return summary;
		}

		private AccommodationAmenity createAccommodationAmenity(Long accommodationId, AmenityType amenityType, Integer count) {
			Accommodation accommodation = Accommodation.builder()
				.id(accommodationId)
				.build();

			Amenity amenity = Amenity.builder()
				.name(amenityType)
				.build();

			return AccommodationAmenity.builder()
				.accommodation(accommodation)
				.amenity(amenity)
				.count(count)
				.build();
		}
	}

	// Helper methods
	private ZSetOperations.TypedTuple<String> createTypedTuple(String value, long timestamp) {
		return new ZSetOperations.TypedTuple<String>() {
			@Override
			public int compareTo(@NotNull ZSetOperations.TypedTuple<String> o) {
				return 0;
			}

			@Override
			public String getValue() {
				return value;
			}

			@Override
			public Double getScore() {
				return (double) timestamp;
			}
		};
	}
}

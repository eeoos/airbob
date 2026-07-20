package kr.kro.airbob.domain.recentlyViewed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("최근 본 숙소 서비스 테스트")
class RecentlyViewedServiceTest {

	@Mock
	private RedisTemplate<String, String> redisTemplate;
	@Mock
	private ZSetOperations<String, String> zSetOperations;
	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private AccommodationReviewSummaryRepository summaryRepository;
	@Mock
	private WishlistAccommodationRepository wishlistAccommodationRepository;

	private RecentlyViewedService recentlyViewedService;

	@BeforeEach
	void setUp() {
		recentlyViewedService = new RecentlyViewedService(
			redisTemplate,
			accommodationRepository,
			summaryRepository,
			wishlistAccommodationRepository
		);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
	}

	@Test
	@DisplayName("N+1 재현 경로는 주소를 제외한 데이터를 일괄 조회하고 최근 조회 순서를 유지한다")
	void beforePathBulkLoadsNonAddressDataAndPreservesRedisOrder() {
		Long memberId = 7L;
		Accommodation first = accommodation(1L);
		Accommodation second = accommodation(2L);
		Set<ZSetOperations.TypedTuple<String>> recentlyViewed = new LinkedHashSet<>(List.of(
			tuple("2", 2_000D),
			tuple("1", 1_000D)
		));

		when(zSetOperations.reverseRangeWithScores("recently_viewed:7", 0, -1)).thenReturn(recentlyViewed);
		when(accommodationRepository.findByIdInAndStatus(anyList(), eq(AccommodationStatus.PUBLISHED)))
			.thenReturn(List.of(first, second));
		when(summaryRepository.findByAccommodationIdIn(anyList())).thenReturn(List.of());
		when(wishlistAccommodationRepository.findAccommodationIdsByMemberIdAndAccommodationIds(
			eq(memberId), anyList())).thenReturn(Set.of(2L));

		AccommodationResponse.RecentlyViewedAccommodationInfos result =
			recentlyViewedService.getRecentlyViewedBefore(memberId);

		assertThat(result.accommodations())
			.extracting(AccommodationResponse.RecentlyViewedAccommodationInfo::accommodationId)
			.containsExactly(2L, 1L);
		assertThat(result.accommodations())
			.extracting(AccommodationResponse.RecentlyViewedAccommodationInfo::isInWishlist)
			.containsExactly(true, false);
		verify(accommodationRepository).findByIdInAndStatus(anyList(), eq(AccommodationStatus.PUBLISHED));
		verify(summaryRepository).findByAccommodationIdIn(anyList());
		verify(wishlistAccommodationRepository)
			.findAccommodationIdsByMemberIdAndAccommodationIds(eq(memberId), anyList());
		verify(accommodationRepository, never()).findWithAddressByIdAndStatusIn(anyList(), eq(AccommodationStatus.PUBLISHED));
		verify(accommodationRepository, never()).findByIdAndStatus(anyLong(), eq(AccommodationStatus.PUBLISHED));
		verify(summaryRepository, never()).findByAccommodationId(anyLong());
		verify(wishlistAccommodationRepository, never())
			.existsByWishlist_Member_IdAndAccommodation_Id(anyLong(), anyLong());
	}

	@Test
	@DisplayName("벤치마크 fixture는 기존 목록을 지우고 전달 순서대로 한 번에 교체한다")
	@SuppressWarnings({"rawtypes", "unchecked"})
	void replaceRecentlyViewedRebuildsTheWholeFixtureWithTtl() {
		List<Long> accommodationIds = List.of(251L, 252L, 253L);
		ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> tuplesCaptor =
			ArgumentCaptor.forClass((Class)Set.class);

		recentlyViewedService.replaceRecentlyViewed(7L, accommodationIds);

		InOrder inOrder = inOrder(redisTemplate, zSetOperations);
		inOrder.verify(redisTemplate).delete("recently_viewed:7");
		inOrder.verify(zSetOperations).add(eq("recently_viewed:7"), tuplesCaptor.capture());
		inOrder.verify(redisTemplate).expire("recently_viewed:7", Duration.ofDays(7));

		List<ZSetOperations.TypedTuple<String>> tuples = new ArrayList<>(tuplesCaptor.getValue());
		assertThat(tuples)
			.extracting(ZSetOperations.TypedTuple::getValue)
			.containsExactly("251", "252", "253");
		assertThat(tuples.get(0).getScore()).isGreaterThan(tuples.get(1).getScore());
		assertThat(tuples.get(1).getScore()).isGreaterThan(tuples.get(2).getScore());
	}

	private Accommodation accommodation(Long id) {
		return Accommodation.builder()
			.id(id)
			.name("숙소 " + id)
			.thumbnailUrl("https://example.com/" + id + ".jpg")
			.address(Address.builder()
				.country("대한민국")
				.state("서울특별시")
				.city("서울")
				.district("중구")
				.build())
			.build();
	}

	@SuppressWarnings("unchecked")
	private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
		ZSetOperations.TypedTuple<String> tuple = mock(ZSetOperations.TypedTuple.class);
		when(tuple.getValue()).thenReturn(value);
		when(tuple.getScore()).thenReturn(score);
		return tuple;
	}
}

package kr.kro.airbob.domain.recentlyviewed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;

@Testcontainers
class RecentlyViewedHistoryIntegrationTest {

	private static final String KEY = "recently_viewed:7";
	private static final String OTHER_KEY = "recently_viewed:8";
	private static final long TTL_SECONDS = Duration.ofDays(7).toSeconds();

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
		.withExposedPorts(6379);
	private static LettuceConnectionFactory connections;
	private static StringRedisTemplate redis;
	private AccommodationRepository accommodations;
	private WishlistAccommodationRepository wishlists;
	private RecentlyViewedService service;

	@BeforeAll
	static void connect() {
		connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connections.afterPropertiesSet();
		connections.start();
		redis = new StringRedisTemplate(connections);
	}

	@AfterAll
	static void disconnect() {
		if (connections != null) connections.destroy();
	}

	@BeforeEach
	void fixture() {
		redis.delete(List.of(KEY, OTHER_KEY));
		accommodations = mock(AccommodationRepository.class);
		wishlists = mock(WishlistAccommodationRepository.class);
		service = new RecentlyViewedService(redis, accommodations, wishlists);
	}

	@Test
	void revisitingUpdatesTheTimestampWithoutDuplicatingTheAccommodationAndRefreshesWholeHistoryTtl() {
		redis.opsForZSet().add(KEY, "31", 1D);
		redis.opsForZSet().add(KEY, "32", 2D);
		redis.expire(KEY, Duration.ofHours(1));
		long before = System.currentTimeMillis();
		service.addRecentlyViewed(31L, 7L);
		long after = System.currentTimeMillis();

		assertThat(redis.opsForZSet().size(KEY)).isEqualTo(2);
		assertThat(redis.opsForZSet().reverseRange(KEY, 0, -1)).containsExactly("31", "32");
		assertThat(redis.opsForZSet().score(KEY, "31")).isBetween((double)before, (double)after);
		assertThat(redis.opsForZSet().score(KEY, "32")).isEqualTo(2D);
		assertThat(redis.getExpire(KEY)).isBetween(TTL_SECONDS - 5, TTL_SECONDS);
		verifyNoInteractions(accommodations, wishlists);
	}

	@Test
	void addingPastTheLimitKeepsTheLatestHundredAndDoesNotTouchAnotherMember() {
		seedHundred();
		redis.opsForZSet().add(OTHER_KEY, "1", 1D);
		service.addRecentlyViewed(101L, 7L);

		assertThat(redis.opsForZSet().size(KEY)).isEqualTo(100);
		assertThat(redis.opsForZSet().score(KEY, "1")).isNull();
		assertThat(redis.opsForZSet().reverseRange(KEY, 0, 0)).containsExactly("101");
		assertThat(redis.opsForZSet().reverseRange(OTHER_KEY, 0, -1)).containsExactly("1");
		assertThat(redis.getExpire(KEY)).isBetween(TTL_SECONDS - 5, TTL_SECONDS);
	}

	@Test
	void concurrentAddsKeepExactlyTheLatestHundredUniqueIds() throws Exception {
		seedHundred();
		StringRedisTemplate interleavedTemplate = spy(redis);
		ZSetOperations<String, String> operations = spy(redis.opsForZSet());
		doReturn(operations).when(interleavedTemplate).opsForZSet();
		var boundary = new CyclicBarrier(8);
		// 별도 ZCARD와 삭제 명령 사이에 다른 요청이 끼는 순서를 실제 Redis에서 고정한다.
		doAnswer(invocation -> {
			boundary.await(10, TimeUnit.SECONDS);
			Long size = redis.opsForZSet().size(KEY);
			boundary.await(10, TimeUnit.SECONDS);
			return size;
		}).when(operations).size(KEY);
		var concurrentService = new RecentlyViewedService(interleavedTemplate, accommodations, wishlists);
		try (var executor = Executors.newFixedThreadPool(8)) {
			var futures = IntStream.rangeClosed(101, 108)
				.mapToObj(id -> executor.submit(() -> concurrentService.addRecentlyViewed((long)id, 7L))).toList();
			for (var future : futures) future.get(20, TimeUnit.SECONDS);
		}
		assertThat(redis.opsForZSet().range(KEY, 0, -1))
			.containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(9, 108).mapToObj(String::valueOf).toList());
		assertThat(redis.getExpire(KEY)).isBetween(TTL_SECONDS - 5, TTL_SECONDS);
	}

	@Test
	void removalIsIdempotentAndDoesNotRefreshTheRemainingHistoryTtl() {
		redis.opsForZSet().add(KEY, "31", 1D);
		redis.opsForZSet().add(KEY, "32", 2D);
		redis.opsForZSet().add(OTHER_KEY, "31", 1D);
		redis.expire(KEY, Duration.ofHours(1));

		service.removeRecentlyViewed(31L, 7L);
		service.removeRecentlyViewed(31L, 7L);
		service.removeRecentlyViewed(999L, 7L);
		assertThat(redis.opsForZSet().range(KEY, 0, -1)).containsExactly("32");
		assertThat(redis.getExpire(KEY)).isBetween(3595L, 3600L);
		assertThat(redis.opsForZSet().range(OTHER_KEY, 0, -1)).containsExactly("31");
		service.removeRecentlyViewed(32L, 7L);
		assertThat(redis.hasKey(KEY)).isFalse();
		verifyNoInteractions(accommodations, wishlists);
	}

	@Test
	void addingAnUnknownIdSucceedsAndReadRemovesItWithoutInventingAnAccommodation() {
		service.addRecentlyViewed(999L, 7L);
		verifyNoInteractions(accommodations, wishlists);
		when(accommodations.findWithAddressAndReviewSummaryByIdInAndStatus(anyList(), eq(AccommodationStatus.PUBLISHED)))
			.thenReturn(List.of());

		var result = service.getRecentlyViewed(7L);
		assertThat(result.accommodations()).isEmpty();
		assertThat(result.totalCount()).isZero();
		assertThat(redis.hasKey(KEY)).isFalse();
		verifyNoInteractions(wishlists);
	}

	@Test
	void readKeepsRedisTieOrderAndDoesNotRefreshExpiry() {
		long score = Instant.parse("2027-01-10T02:00:00.123Z").toEpochMilli();
		for (String id : List.of("9", "10", "31")) redis.opsForZSet().add(KEY, id, score);
		redis.expire(KEY, Duration.ofHours(1));
		when(accommodations.findWithAddressAndReviewSummaryByIdInAndStatus(anyList(), eq(AccommodationStatus.PUBLISHED)))
			.thenReturn(List.of(card(10L), card(31L), card(9L)));
		when(wishlists.findAccommodationIdsByMemberIdAndAccommodationIds(eq(7L), anyList())).thenReturn(Set.of(10L));

		var result = service.getRecentlyViewed(7L);
		assertThat(result.accommodations()).extracting(item -> item.accommodationId()).containsExactly(9L, 31L, 10L);
		assertThat(result.accommodations()).allSatisfy(item -> {
			assertThat(item.viewedAt()).isEqualTo(Instant.ofEpochMilli(score));
			assertThat(item.reviewSummary().totalCount()).isZero();
			assertThat(item.reviewSummary().averageRating()).isZero();
		});
		assertThat(result.totalCount()).isEqualTo(3);
		assertThat(redis.getExpire(KEY)).isBetween(3595L, 3600L);
	}

	@Test
	void expiredHistoryReturnsAnEmptyListWithoutReadingMySql() {
		service.addRecentlyViewed(31L, 7L);
		redis.expire(KEY, Duration.ZERO);
		assertThat(service.getRecentlyViewed(7L).totalCount()).isZero();
		verifyNoInteractions(accommodations, wishlists);
	}

	private void seedHundred() {
		IntStream.rangeClosed(1, 100).forEach(id -> redis.opsForZSet().add(KEY, String.valueOf(id), (double)id));
	}

	private RecentlyViewedAccommodationProjection card(long id) {
		return new RecentlyViewedAccommodationProjection(id, "테스트 숙소", null, null, null, null, null, null, null);
	}
}

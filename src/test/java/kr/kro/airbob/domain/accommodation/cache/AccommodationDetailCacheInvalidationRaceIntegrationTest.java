package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheJitter;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.redis.AccommodationDetailRedisClient;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;

@Testcontainers
@DisplayName("숙소 상세 캐시 조회·무효화 경합 통합 테스트")
class AccommodationDetailCacheInvalidationRaceIntegrationTest {

	private static final String CACHE_KEY = "airbob:cache:accommodation-detail:v1:{1}";

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static AccommodationDetailRedisClient redisClient;
	private static RedissonClient redissonClient;
	private static AccommodationDetailCache cache;

	@BeforeAll
	static void setUpClients() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisClient = new AccommodationDetailRedisClient(redisTemplate, connectionFactory);

		Config redissonConfig = new Config();
		redissonConfig.useSingleServer()
			.setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		redissonClient = Redisson.create(redissonConfig);
		cache = new AccommodationDetailCache(
			redisClient,
			redissonClient,
			new ObjectMapper().findAndRegisterModules(),
			mock(AccommodationDetailCacheMetricRecorder.class),
			new AccommodationDetailCacheJitter(),
			new AccommodationDetailCacheProperties(
				true,
				Duration.ofMinutes(10), Duration.ZERO,
				Duration.ofSeconds(45), Duration.ZERO,
				Duration.ofSeconds(5),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1), Duration.ofSeconds(1))
		);
	}

	@AfterAll
	static void closeClients() {
		if (redissonClient != null) {
			redissonClient.shutdown();
		}
		if (connectionFactory != null) {
			connectionFactory.destroy();
		}
	}

	@BeforeEach
	void clearKeys() {
		redissonClient.getKeys().deleteByPattern("airbob:*:accommodation-detail:*");
	}

	@Test
	@DisplayName("무효화는 이미 저장된 상세을 삭제하고 다음 요청이 DB를 다시 조회하게 한다")
	void evictionDeletesExistingValueAndForcesReload() {
		AtomicInteger loads = new AtomicInteger();

		assertThat(cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot("old");
		}).name()).isEqualTo("old");
		assertThat(redisTemplate.hasKey(CACHE_KEY)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);

		assertThat(redisTemplate.hasKey(CACHE_KEY)).isFalse();
		assertThat(cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot("new");
		}).name()).isEqualTo("new");
		assertThat(loads).hasValue(2);
	}

	@Test
	@DisplayName("구버전 DB 조회 중 무효화는 stale cache 저장을 차단한다")
	void invalidationWaitsForInFlightLoadThenDeletesStaleValue() throws Exception {
		CountDownLatch loaderStarted = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		AccommodationDetailSnapshot stale = snapshot("old");

		CompletableFuture<AccommodationDetailSnapshot> loadFuture = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				loaderStarted.countDown();
				try {
					if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("loader release timeout");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return stale;
			}));

		assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Void> evictionFuture = CompletableFuture.runAsync(
			() -> cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION));
		releaseLoader.countDown();

		assertThat(loadFuture.get(10, TimeUnit.SECONDS)).isEqualTo(stale);
		evictionFuture.get(10, TimeUnit.SECONDS);
		assertThat(redisTemplate.hasKey(CACHE_KEY)).isFalse();

		AtomicInteger reloads = new AtomicInteger();
		AccommodationDetailSnapshot fresh = snapshot("new");
		assertThat(cache.getOrLoad(1L, () -> {
			reloads.incrementAndGet();
			return fresh;
		})).isEqualTo(fresh);
		assertThat(reloads).hasValue(1);
	}

	@Test
	@DisplayName("무효화보다 늦게 끝난 구버전 조회도 stale cache를 다시 저장하지 못한다")
	void invalidationFencesLoadThatOutlivesEvictionWait() throws Exception {
		AccommodationDetailCache shortWaitCache = new AccommodationDetailCache(
			redisClient,
			redissonClient,
			new ObjectMapper().findAndRegisterModules(),
			mock(AccommodationDetailCacheMetricRecorder.class),
			new AccommodationDetailCacheJitter(),
			new AccommodationDetailCacheProperties(
				true,
				Duration.ofMinutes(10), Duration.ZERO,
				Duration.ofSeconds(45), Duration.ZERO,
				Duration.ofSeconds(5),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1), Duration.ofSeconds(1))
		);
		CountDownLatch loaderStarted = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);

		CompletableFuture<AccommodationDetailSnapshot> loadFuture = CompletableFuture.supplyAsync(
			() -> shortWaitCache.getOrLoad(1L, () -> {
				loaderStarted.countDown();
				try {
					releaseLoader.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return snapshot("old");
			}));

		assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Void> evictionFuture = CompletableFuture.runAsync(
			() -> shortWaitCache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION));
		evictionFuture.get(5, TimeUnit.SECONDS);
		releaseLoader.countDown();
		loadFuture.get(5, TimeUnit.SECONDS);

		assertThat(redisTemplate.hasKey(CACHE_KEY)).isFalse();
	}

	private static AccommodationDetailSnapshot snapshot(String name) {
		return new AccommodationDetailSnapshot(
			1L, name, null, null, null, null, null, null, "Asia/Seoul",
			null, null, null, null, List.of(), List.of(), null);
	}
}

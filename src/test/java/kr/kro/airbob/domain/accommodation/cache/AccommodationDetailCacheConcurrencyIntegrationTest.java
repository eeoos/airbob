package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
@DisplayName("숙소 상세 캐시 동시성 통합 테스트")
class AccommodationDetailCacheConcurrencyIntegrationTest {

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static AccommodationDetailRedisClient redisClient;
	private static RedissonClient redissonClient;
	private static AccommodationDetailCache cache;

	@BeforeAll
	static void setUpClients() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		redisClient = new AccommodationDetailRedisClient(
			redisTemplate, connectionFactory);

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
				Duration.ofMinutes(10),
				Duration.ZERO,
				Duration.ofSeconds(45),
				Duration.ZERO,
				Duration.ofSeconds(5),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1))
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
	@DisplayName("동시에 들어온 동일 숙소 miss 요청은 DB loader를 한 번만 실행한다")
	void sameKeyMissesAreSingleFlight() throws Exception {
		int requestCount = 30;
		AtomicInteger loadCount = new AtomicInteger();
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		AccommodationDetailSnapshot expected = snapshot();

		try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
			List<Callable<AccommodationDetailSnapshot>> requests = new ArrayList<>();
			for (int index = 0; index < requestCount; index++) {
				requests.add(() -> {
					ready.countDown();
					start.await(5, TimeUnit.SECONDS);
					return cache.getOrLoad(1L, () -> {
						loadCount.incrementAndGet();
						return expected;
					});
				});
			}

			List<Future<AccommodationDetailSnapshot>> futures = requests.stream()
				.map(executor::submit)
				.toList();
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			for (Future<AccommodationDetailSnapshot> future : futures) {
				assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
			}
		}

		assertThat(loadCount).hasValue(1);
	}

	@Test
	@DisplayName("락 OFF 대조군은 겹친 DB 조회를 병합하지 않고 유효 permit의 결과로 캐시를 채운다")
	void unlockedControlLoadsConcurrentlyAndSubsequentRequestHitsRealRedis() throws Exception {
		var unusedLockClient = mock(RedissonClient.class);
		var properties = new AccommodationDetailCacheProperties(true,
			Duration.ofMinutes(10), Duration.ZERO, Duration.ofSeconds(45), Duration.ZERO,
			Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30),
			Duration.ofSeconds(1), Duration.ofSeconds(1), false, false);
		var first = new AccommodationDetailCache(redisClient, unusedLockClient,
			new ObjectMapper().findAndRegisterModules(), mock(AccommodationDetailCacheMetricRecorder.class),
			new AccommodationDetailCacheJitter(), properties);
		var second = new AccommodationDetailCache(redisClient, unusedLockClient,
			new ObjectMapper().findAndRegisterModules(), mock(AccommodationDetailCacheMetricRecorder.class),
			new AccommodationDetailCacheJitter(), properties);
		int requestCount = 12;
		CountDownLatch loadersStarted = new CountDownLatch(requestCount);
		CountDownLatch finishLoads = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();

		try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
			List<Future<AccommodationDetailSnapshot>> futures = new ArrayList<>();
			for (int index = 0; index < requestCount; index++) {
				var instance = index % 2 == 0 ? first : second;
				futures.add(executor.submit(() -> instance.getOrLoad(1L, () -> {
					loads.incrementAndGet();
					loadersStarted.countDown();
					try {
						if (!finishLoads.await(10, TimeUnit.SECONDS)) {
							throw new IllegalStateException("loader release timed out");
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(exception);
					}
					return snapshot();
				})));
			}
			try {
				// 모든 permit 발급/DB 조회를 겹치게 하는 기능 검증이며 성능 측정용 지연이 아니다.
				assertThat(loadersStarted.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				finishLoads.countDown();
			}
			for (var future : futures) {
				assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(snapshot());
			}
		}
		assertThat(loads).hasValue(requestCount);
		for (var instance : List.of(first, second)) {
			assertThat(instance.getOrLoad(1L, () -> {
				throw new AssertionError("The valid permit must have populated the shared cache");
			})).isEqualTo(snapshot());
		}
		verifyNoInteractions(unusedLockClient);
	}

	private static AccommodationDetailSnapshot snapshot() {
		return new AccommodationDetailSnapshot(
			1L, "숙소", null, null, null, null, null, null, "Asia/Seoul",
			null, null, null, null, List.of(), List.of(), null);
	}
}

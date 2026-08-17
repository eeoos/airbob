package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
	private static RedissonClient redissonClient;
	private static AccommodationDetailCache cache;

	@BeforeAll
	static void setUpClients() {
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		AccommodationDetailRedisClient redisClient = new AccommodationDetailRedisClient(
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

	private static AccommodationDetailSnapshot snapshot() {
		return new AccommodationDetailSnapshot(
			1L, "숙소", null, null, null, null, null, null, "Asia/Seoul",
			null, null, null, null, List.of(), List.of(), null);
	}
}

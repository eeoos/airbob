package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheJitter;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import kr.kro.airbob.domain.accommodation.cache.redis.AccommodationDetailRedisClient;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 상세 Cache-Aside 단위 테스트")
class AccommodationDetailCacheTest {

	private static final String CACHE_KEY = "airbob:cache:accommodation-detail:v1:{1}";
	private static final String LOAD_PERMIT_KEY = "airbob:cache:accommodation-detail:load-permit:{1}";
	private static final String LOCK_KEY = "airbob:lock:accommodation-detail:{1}";

	@Mock private AccommodationDetailRedisClient redisClient;
	@Mock private RedissonClient redissonClient;
	@Mock private RLock lock;
	@Mock private AccommodationDetailCacheMetricRecorder metricRecorder;
	@Mock private AccommodationDetailCacheJitter jitter;

	private ObjectMapper objectMapper;
	private AccommodationDetailCache cache;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper().findAndRegisterModules();
		lenient().when(redisClient.execute(any(RedisScript.class), anyList(), any(Object[].class)))
			.thenReturn(1L);
		cache = new AccommodationDetailCache(
			redisClient,
			redissonClient,
			objectMapper,
			metricRecorder,
			jitter,
			new AccommodationDetailCacheProperties(
				true,
				Duration.ofMinutes(10),
				Duration.ofMinutes(2),
				Duration.ofSeconds(45),
				Duration.ofSeconds(15),
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1))
		);
	}

	@Test
	@DisplayName("캐시가 비활성화되면 loader를 호출하고 Redis와 락을 사용하지 않는다")
	void disabledCacheLoadsWithoutRedisOrLock() {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		AccommodationDetailCache disabledCache = new AccommodationDetailCache(
			redisClient,
			redissonClient,
			objectMapper,
			metricRecorder,
			jitter,
			new AccommodationDetailCacheProperties(
				false,
				Duration.ofMinutes(10),
				Duration.ofMinutes(2),
				Duration.ofSeconds(45),
				Duration.ofSeconds(15),
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1))
		);

		AccommodationDetailSnapshot actual = disabledCache.getOrLoad(1L, () -> expected);

		assertThat(actual).isSameAs(expected);
		verifyNoInteractions(redisClient, redissonClient);
	}

	@Test
	@DisplayName("캐시가 비활성화되면 무효화가 Redis와 캐시 메트릭을 사용하지 않는다")
	void disabledCacheEvictionDoesNotTouchCacheClientsOrMetrics() {
		AccommodationDetailCache disabledCache = new AccommodationDetailCache(
			redisClient,
			redissonClient,
			objectMapper,
			metricRecorder,
			jitter,
			new AccommodationDetailCacheProperties(
				false,
				Duration.ofMinutes(10),
				Duration.ofMinutes(2),
				Duration.ofSeconds(45),
				Duration.ofSeconds(15),
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				Duration.ofSeconds(30),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1))
		);

		disabledCache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		disabledCache.evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);

		verifyNoInteractions(redisClient, redissonClient, metricRecorder);
	}

	@Test
	@DisplayName("캐시 hit이면 DB loader와 락을 사용하지 않는다")
	void hitSkipsLoaderAndLock() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "cached");
		when(redisClient.get(CACHE_KEY)).thenReturn(json(AccommodationDetailCacheValue.found(expected)));
		AtomicInteger loads = new AtomicInteger();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		});

		assertThat(actual).isEqualTo(expected);
		assertThat(loads).hasValue(0);
		verifyNoInteractions(redissonClient);
	}

	@Test
	@DisplayName("fallback 조회 중 Redis가 복구되면 캐시 hit를 먼저 반환한다")
	void recoveredRedisHitWinsOverInFlightFallback() throws Exception {
		AccommodationDetailCache shortWaitCache = cacheWithLocalLoadWait(Duration.ofMillis(50));
		AccommodationDetailSnapshot cached = snapshot(1L, "cached-after-recovery");
		when(redisClient.get(CACHE_KEY))
			.thenThrow(new IllegalStateException("redis down"))
			.thenReturn(json(AccommodationDetailCacheValue.found(cached)));
		CountDownLatch fallbackStarted = new CountDownLatch(1);
		CountDownLatch releaseFallback = new CountDownLatch(1);
		CompletableFuture<AccommodationDetailSnapshot> fallback = CompletableFuture.supplyAsync(
			() -> shortWaitCache.getOrLoad(1L, () -> {
				fallbackStarted.countDown();
				await(releaseFallback);
				return snapshot(1L, "fallback");
			}));
		assertThat(fallbackStarted.await(5, TimeUnit.SECONDS)).isTrue();
		AtomicInteger secondLoads = new AtomicInteger();

		AccommodationDetailSnapshot actual = shortWaitCache.getOrLoad(1L, () -> {
			secondLoads.incrementAndGet();
			return snapshot(1L, "unexpected-database");
		});
		releaseFallback.countDown();

		assertThat(actual).isEqualTo(cached);
		assertThat(secondLoads).hasValue(0);
		assertThat(fallback.get(5, TimeUnit.SECONDS).name()).isEqualTo("fallback");
		verify(redisClient, times(2)).get(CACHE_KEY);
		verifyNoInteractions(redissonClient);
	}

	@Test
	@DisplayName("fallback 조회 중 Redis가 복구되면 negative cache를 먼저 반환한다")
	void recoveredRedisNegativeHitWinsOverInFlightFallback() throws Exception {
		when(redisClient.get(CACHE_KEY))
			.thenThrow(new IllegalStateException("redis down"))
			.thenReturn(json(AccommodationDetailCacheValue.notFound()));
		CountDownLatch fallbackStarted = new CountDownLatch(1);
		CountDownLatch releaseFallback = new CountDownLatch(1);
		CompletableFuture<AccommodationDetailSnapshot> fallback = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				fallbackStarted.countDown();
				await(releaseFallback);
				return snapshot(1L, "fallback");
			}));
		assertThat(fallbackStarted.await(5, TimeUnit.SECONDS)).isTrue();
		AtomicInteger secondLoads = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			secondLoads.incrementAndGet();
			return snapshot(1L, "unexpected-database");
		})).isInstanceOf(AccommodationNotFoundException.class);
		releaseFallback.countDown();

		assertThat(secondLoads).hasValue(0);
		assertThat(fallback.get(5, TimeUnit.SECONDS).name()).isEqualTo("fallback");
		verify(redisClient, times(2)).get(CACHE_KEY);
		verifyNoInteractions(redissonClient);
	}

	@Test
	@DisplayName("fallback 조회 중 Redis가 miss이면 분산 락 전에 기존 조회에 합류한다")
	void recoveredRedisMissJoinsInFlightFallbackBeforeLock() throws Exception {
		CountDownLatch secondRedisRead = new CountDownLatch(1);
		AtomicInteger redisReads = new AtomicInteger();
		when(redisClient.get(CACHE_KEY)).thenAnswer(invocation -> {
			if (redisReads.incrementAndGet() == 1) {
				throw new IllegalStateException("redis down");
			}
			secondRedisRead.countDown();
			return null;
		});
		CountDownLatch fallbackStarted = new CountDownLatch(1);
		CountDownLatch releaseFallback = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		Supplier<AccommodationDetailSnapshot> loader = () -> {
			loads.incrementAndGet();
			fallbackStarted.countDown();
			await(releaseFallback);
			return snapshot(1L, "fallback");
		};
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, loader));
		assertThat(fallbackStarted.await(5, TimeUnit.SECONDS)).isTrue();

		AtomicReference<Thread> followerThread = new AtomicReference<>();
		CompletableFuture<AccommodationDetailSnapshot> follower = CompletableFuture.supplyAsync(() -> {
			followerThread.set(Thread.currentThread());
			return cache.getOrLoad(1L, loader);
		});
		assertThat(secondRedisRead.await(5, TimeUnit.SECONDS)).isTrue();
		awaitWaiting(followerThread);
		releaseFallback.countDown();

		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("fallback");
		assertThat(follower.get(5, TimeUnit.SECONDS).name()).isEqualTo("fallback");
		assertThat(loads).hasValue(1);
		assertThat(redisReads).hasValue(2);
		verifyNoInteractions(redissonClient);
	}

	@Test
	@DisplayName("같은 ID의 반복 404는 negative cache에서 차단한다")
	void negativeHitSkipsLoader() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenReturn(json(AccommodationDetailCacheValue.notFound()));
		AtomicInteger loads = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		})).isInstanceOf(AccommodationNotFoundException.class);

		assertThat(loads).hasValue(0);
		verifyNoInteractions(redissonClient);
	}

	@Test
	@DisplayName("락 대기 중 채워진 negative cache도 DB loader를 사용하지 않는다")
	void negativeHitAfterWaitingSkipsLoader() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenReturn(
			null,
			json(AccommodationDetailCacheValue.notFound()));
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		AtomicInteger loads = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		})).isInstanceOf(AccommodationNotFoundException.class);

		assertThat(loads).hasValue(0);
		verify(redisClient, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
		verify(lock).unlock();
	}

	@Test
	@DisplayName("miss이면 숙소별 락 안에서 다시 확인하고 DB 결과를 jitter TTL로 저장한다")
	void missLoadsOnceAndStoresWithJitter() throws Exception {
		AccommodationDetailSnapshot loaded = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(120_001L)).thenReturn(30_000L);

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> loaded);

		assertThat(actual).isEqualTo(loaded);
		verify(redisClient, atLeastOnce()).execute(
			any(RedisScript.class),
			eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)),
			any(String.class), eq("630000"), any(String.class));
		verify(lock).unlock();
	}

	@Test
	@DisplayName("DB 404는 짧은 negative TTL로 저장한다")
	void storesNotFoundWithShortTtl() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(15_001L)).thenReturn(5_000L);

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			throw new AccommodationNotFoundException();
		})).isInstanceOf(AccommodationNotFoundException.class);

		verify(redisClient, atLeastOnce()).execute(
			any(RedisScript.class),
			eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)),
			any(String.class), eq("50000"), any(String.class));
	}

	@Test
	@DisplayName("Redis 조회 장애면 락을 시도하지 않고 DB 응답으로 우회한다")
	void redisReadFailureBypassesCache() {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verifyNoInteractions(redissonClient);
		verify(redisClient, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("락 내부 재조회가 실패하면 락을 해제한 뒤 DB로 우회한다")
	void secondReadFailureReleasesLockBeforeDatabaseFallback() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		AtomicBoolean lockReleased = new AtomicBoolean();
		when(redisClient.get(CACHE_KEY))
			.thenReturn(null)
			.thenThrow(new IllegalStateException("redis down"));
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		doAnswer(invocation -> {
			lockReleased.set(true);
			return null;
		}).when(lock).unlock();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> {
			assertThat(lockReleased).isTrue();
			return expected;
		});

		assertThat(actual).isEqualTo(expected);
		verify(lock).unlock();
	}

	@Test
	@DisplayName("쓰기 허가를 얻지 못하면 락을 해제한 뒤 DB로 우회한다")
	void loadPermitFailureReleasesLockBeforeDatabaseFallback() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		AtomicBoolean lockReleased = new AtomicBoolean();
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(redisClient.execute(
			any(RedisScript.class),
			eq(List.of(LOAD_PERMIT_KEY)),
			any(String.class),
			any(String.class)
		)).thenReturn(null);
		doAnswer(invocation -> {
			lockReleased.set(true);
			return null;
		}).when(lock).unlock();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> {
			assertThat(lockReleased).isTrue();
			return expected;
		});

		assertThat(actual).isEqualTo(expected);
		verify(lock).unlock();
	}

	@Test
	@DisplayName("락 해제는 별도 소유권 조회 없이 직접 시도하고 실패해도 DB 응답을 유지한다")
	void releaseAttemptsOwnerSafeUnlockDirectly() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verify(lock, never()).isHeldByCurrentThread();
		verify(lock).unlock();
	}

	@Test
	@DisplayName("Redis 장애 중 같은 숙소의 동시 요청도 JVM 내에서 DB를 한 번만 조회한다")
	void redisFailureUsesLocalSingleFlight() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch loaderStarted = new CountDownLatch(1);
		CountDownLatch releaseLoader = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		Supplier<AccommodationDetailSnapshot> loader = () -> {
			loads.incrementAndGet();
			loaderStarted.countDown();
			try {
				releaseLoader.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			return snapshot(1L, "database");
		};

		CompletableFuture<AccommodationDetailSnapshot> first = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, loader));
		assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
		AtomicReference<Thread> followerThread = new AtomicReference<>();
		CompletableFuture<AccommodationDetailSnapshot> second = CompletableFuture.supplyAsync(
			() -> {
				followerThread.set(Thread.currentThread());
				return cache.getOrLoad(1L, loader);
			});
		awaitWaiting(followerThread);
		releaseLoader.countDown();

		assertThat(first.get(5, TimeUnit.SECONDS).name()).isEqualTo("database");
		assertThat(second.get(5, TimeUnit.SECONDS).name()).isEqualTo("database");
		assertThat(loads).hasValue(1);
		verify(redisClient, times(2)).get(CACHE_KEY);
	}

	@Test
	@DisplayName("로컬 단일 조회 대기 시간이 지나면 후속 요청도 독립 DB 조회로 우회한다")
	void localLoadTimeoutFallsBackToIndependentDatabaseLoad() throws Exception {
		AccommodationDetailCache shortWaitCache = cacheWithLocalLoadWait(Duration.ofMillis(50));
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch leaderStarted = new CountDownLatch(1);
		CountDownLatch releaseLeader = new CountDownLatch(1);
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> shortWaitCache.getOrLoad(1L, () -> {
				leaderStarted.countDown();
				try {
					releaseLeader.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return snapshot(1L, "slow");
			}));
		assertThat(leaderStarted.await(5, TimeUnit.SECONDS)).isTrue();

		AccommodationDetailSnapshot follower = shortWaitCache.getOrLoad(
			1L, () -> snapshot(1L, "fallback"));
		releaseLeader.countDown();

		assertThat(follower.name()).isEqualTo("fallback");
		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("slow");
	}

	@Test
	@DisplayName("DB 조회 예외 뒤 쓰기 허가를 정리해 다음 요청이 다시 캐시를 채운다")
	void loaderFailureReleasesPermitForNextLoad() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "recovered");
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null, null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(120_001L)).thenReturn(0L);

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			throw new IllegalStateException("database failure");
		})).isInstanceOf(IllegalStateException.class);

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verify(redisClient, atLeastOnce()).execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY)), any(String.class));
		verify(redisClient, atLeastOnce()).execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)),
			any(String.class), eq("600000"), any(String.class));
	}

	@Test
	@DisplayName("락 획득 timeout이면 DB로 우회하되 락 밖에서 캐시를 쓰지 않는다")
	void lockTimeoutLoadsWithoutWriting() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn(null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verify(redisClient, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("락 timeout 직후 캐시가 채워졌으면 DB를 다시 조회하지 않는다")
	void lockTimeoutRechecksFilledCacheBeforeDbFallback() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "filled-while-waiting");
		when(redisClient.get(CACHE_KEY)).thenReturn(
			null,
			json(AccommodationDetailCacheValue.found(expected)));
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
		AtomicInteger loads = new AtomicInteger();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		});

		assertThat(actual).isEqualTo(expected);
		assertThat(loads).hasValue(0);
	}

	@Test
	@DisplayName("락 timeout 직후 negative cache가 채워졌으면 DB를 다시 조회하지 않는다")
	void lockTimeoutRechecksNegativeCacheBeforeDbFallback() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenReturn(
			null,
			json(AccommodationDetailCacheValue.notFound()));
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
		AtomicInteger loads = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		})).isInstanceOf(AccommodationNotFoundException.class);

		assertThat(loads).hasValue(0);
	}

	@Test
	@DisplayName("무효화는 기존 요청을 중단하지 않고 이후 요청을 진행 중 조회에서 분리한다")
	void evictionDetachesInFlightLocalLoadForSubsequentRequest() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch oldLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseOldLoad = new CountDownLatch(1);
		AtomicInteger leaderLoads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				leaderLoads.incrementAndGet();
				oldLoadStarted.countDown();
				await(releaseOldLoad);
				return snapshot(1L, "old");
			}));
		assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		AtomicInteger followerLoads = new AtomicInteger();
		AtomicReference<Thread> followerThread = new AtomicReference<>();
		CompletableFuture<AccommodationDetailSnapshot> follower = CompletableFuture.supplyAsync(() -> {
			followerThread.set(Thread.currentThread());
			return cache.getOrLoad(1L, () -> {
				followerLoads.incrementAndGet();
				return snapshot(1L, "new");
			});
		});
		awaitWaiting(followerThread);

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		AtomicInteger freshLoads = new AtomicInteger();
		AccommodationDetailSnapshot fresh = cache.getOrLoad(1L, () -> {
			freshLoads.incrementAndGet();
			return snapshot(1L, "new");
		});
		releaseOldLoad.countDown();

		assertThat(fresh.name()).isEqualTo("new");
		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("old");
		assertThat(follower.get(5, TimeUnit.SECONDS).name()).isEqualTo("old");
		assertThat(leaderLoads).hasValue(1);
		assertThat(followerLoads).hasValue(0);
		assertThat(freshLoads).hasValue(1);
	}

	@Test
	@DisplayName("무효화는 Redis 삭제가 끝나기 전에 진행 중 로컬 조회를 분리한다")
	void evictionDetachesLocalLoadBeforeRedisInvalidationCompletes() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch oldLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseOldLoad = new CountDownLatch(1);
		CountDownLatch invalidationStarted = new CountDownLatch(1);
		CountDownLatch releaseInvalidation = new CountDownLatch(1);
		when(redisClient.execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY))))
			.thenAnswer(invocation -> {
				invalidationStarted.countDown();
				await(releaseInvalidation);
				return 1L;
			});
		CountDownLatch freshLoadStarted = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
			CompletableFuture<AccommodationDetailSnapshot> oldLoad = CompletableFuture.supplyAsync(
				() -> cache.getOrLoad(1L, () -> {
					oldLoadStarted.countDown();
					await(releaseOldLoad);
					return snapshot(1L, "old");
				}), executor);
			assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<Void> eviction = CompletableFuture.runAsync(() ->
				cache.evictOrThrow(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION), executor);
			assertThat(invalidationStarted.await(5, TimeUnit.SECONDS)).isTrue();

			CompletableFuture<AccommodationDetailSnapshot> freshLoad = CompletableFuture.supplyAsync(
				() -> cache.getOrLoad(1L, () -> {
					freshLoadStarted.countDown();
					return snapshot(1L, "new");
				}), executor);
			assertThat(freshLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(eviction).isNotDone();
			assertThat(freshLoad.get(5, TimeUnit.SECONDS).name()).isEqualTo("new");

			releaseInvalidation.countDown();
			eviction.get(5, TimeUnit.SECONDS);
			releaseOldLoad.countDown();
			assertThat(oldLoad.get(5, TimeUnit.SECONDS).name()).isEqualTo("old");
		} finally {
			releaseInvalidation.countDown();
			releaseOldLoad.countDown();
		}
	}

	@Test
	@DisplayName("무효화는 진행 중인 404 조회를 재시도시키지 않는다")
	void evictionDoesNotRetryInFlightNotFoundLoad() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch oldLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseOldLoad = new CountDownLatch(1);
		AtomicInteger oldLoads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> oldLoad = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				oldLoads.incrementAndGet();
				oldLoadStarted.countDown();
				await(releaseOldLoad);
				throw new AccommodationNotFoundException();
			}));
		assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		AccommodationDetailSnapshot fresh = cache.getOrLoad(1L, () -> snapshot(1L, "published"));
		releaseOldLoad.countDown();

		assertThat(fresh.name()).isEqualTo("published");
		assertThatThrownBy(() -> oldLoad.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(AccommodationNotFoundException.class);
		assertThat(oldLoads).hasValue(1);
	}

	@Test
	@DisplayName("Redis 무효화가 실패해도 기존 요청을 중단하지 않고 이후 요청을 분리한다")
	void failedEvictionStillDetachesInFlightLocalLoad() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		when(redisClient.execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY))))
			.thenThrow(new IllegalStateException("redis unavailable"));
		CountDownLatch oldLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseOldLoad = new CountDownLatch(1);
		AtomicInteger oldLoads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> oldLoad = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				oldLoads.incrementAndGet();
				oldLoadStarted.countDown();
				await(releaseOldLoad);
				return snapshot(1L, "old");
			}));
		assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> cache.evictOrThrow(
			1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("redis unavailable");
		AccommodationDetailSnapshot fresh = cache.getOrLoad(1L, () -> snapshot(1L, "new"));
		releaseOldLoad.countDown();

		assertThat(fresh.name()).isEqualTo("new");
		assertThat(oldLoad.get(5, TimeUnit.SECONDS).name()).isEqualTo("old");
		assertThat(oldLoads).hasValue(1);
	}

	@Test
	@DisplayName("락을 기다리는 동안 다른 요청이 채운 값은 DB를 다시 읽지 않는다")
	void hitAfterWaitingUsesDoubleCheckedValue() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "filled-by-other-request");
		when(redisClient.get(CACHE_KEY)).thenReturn(
			null,
			json(AccommodationDetailCacheValue.found(expected)));
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		AtomicInteger loads = new AtomicInteger();

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> {
			loads.incrementAndGet();
			return snapshot(1L, "database");
		});

		assertThat(actual).isEqualTo(expected);
		assertThat(loads).hasValue(0);
		verify(redisClient, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	@DisplayName("손상된 캐시 값은 삭제하고 DB 결과로 복구한다")
	void corruptValueIsDeletedAndReloaded() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn("not-json", (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(120_001L)).thenReturn(0L);

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verify(redisClient).delete(CACHE_KEY);
		verify(redisClient, atLeastOnce()).execute(
			any(RedisScript.class),
			eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)),
			any(String.class), eq("600000"), any(String.class));
	}

	@Test
	@DisplayName("상태가 빠진 JSON은 negative cache로 오인하지 않고 손상 값으로 삭제한다")
	void missingStatusIsDeletedAndReloaded() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn("{}", (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(120_001L)).thenReturn(0L);

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
		verify(redisClient).delete(CACHE_KEY);
	}

	@Test
	@DisplayName("Redis 저장 실패는 정상 DB 응답을 실패시키지 않는다")
	void writeFailureStillReturnsDatabaseValue() throws Exception {
		AccommodationDetailSnapshot expected = snapshot(1L, "database");
		when(redisClient.get(CACHE_KEY)).thenReturn(null, (String)null);
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(jitter.nextMillis(120_001L)).thenReturn(0L);
		when(redisClient.execute(any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY)),
			any(String.class), any(String.class))).thenReturn(1L);
		when(redisClient.execute(any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)),
			any(String.class), any(String.class), any(String.class)))
			.thenThrow(new IllegalStateException("redis write down"));

		AccommodationDetailSnapshot actual = cache.getOrLoad(1L, () -> expected);

		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("무효화는 진행 중인 쓰기 허가와 캐시 키를 원자적으로 삭제한다")
	void evictionDeletesLoadPermitAndCacheAtomically() {
		cache.evict(1L, AccommodationDetailCacheInvalidationReason.REVIEW);

		verify(redisClient).execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)));
		verify(metricRecorder).recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.AFTER_COMMIT,
			AccommodationDetailCacheInvalidationReason.REVIEW,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);
	}

	@Test
	@DisplayName("내구성 복구용 무효화는 Redis 실패를 소비자에게 전파한다")
	void durableEvictionPropagatesRedisFailure() {
		when(redisClient.execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY))))
			.thenThrow(new IllegalStateException("redis unavailable"));

		assertThatThrownBy(() -> cache.evictOrThrow(
			1L, AccommodationDetailCacheInvalidationReason.REVIEW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("redis unavailable");
		verify(metricRecorder).recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.OUTBOX,
			AccommodationDetailCacheInvalidationReason.REVIEW,
			AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);
	}

	private String json(AccommodationDetailCacheValue value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private AccommodationDetailSnapshot snapshot(long id, String name) {
		return new AccommodationDetailSnapshot(
			id, name, null, null, null, null, null, null, "Asia/Seoul",
			null, null, null, null, List.of(), List.of(), null);
	}

	private AccommodationDetailCache cacheWithLocalLoadWait(Duration localLoadWait) {
		return new AccommodationDetailCache(
			redisClient,
			redissonClient,
			objectMapper,
			metricRecorder,
			jitter,
			new AccommodationDetailCacheProperties(
				true,
				Duration.ofMinutes(10),
				Duration.ofMinutes(2),
				Duration.ofSeconds(45),
				Duration.ofSeconds(15),
				Duration.ofSeconds(2),
				localLoadWait,
				Duration.ofSeconds(30),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1))
		);
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("latch wait timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private void awaitWaiting(AtomicReference<Thread> threadReference) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Thread thread = threadReference.get();
			if (thread != null
				&& (thread.getState() == Thread.State.WAITING
				|| thread.getState() == Thread.State.TIMED_WAITING)) {
				return;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		}
		throw new IllegalStateException("local load follower가 대기 상태에 진입하지 않음");
	}
}

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
	@DisplayName("무효화 이후 요청은 진행 중이던 이전 JVM 조회 결과를 기다리지 않는다")
	void evictionFencesInFlightLocalLoad() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch staleLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseStaleLoad = new CountDownLatch(1);
		AtomicInteger leaderLoads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> staleLoad = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				if (leaderLoads.incrementAndGet() == 1) {
					staleLoadStarted.countDown();
					await(releaseStaleLoad);
					return snapshot(1L, "old");
				}
				return snapshot(1L, "new");
			}));
		assertThat(staleLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		AccommodationDetailSnapshot fresh = cache.getOrLoad(1L, () -> snapshot(1L, "new"));
		releaseStaleLoad.countDown();

		assertThat(fresh.name()).isEqualTo("new");
		assertThat(staleLoad.get(5, TimeUnit.SECONDS).name()).isEqualTo("new");
		assertThat(leaderLoads).hasValue(2);
	}

	@Test
	@DisplayName("무효화 대기자는 Redis 삭제가 끝날 때까지 깨어나거나 이전 캐시를 재조회하지 않는다")
	void evictionWakesFollowerAfterRedisInvalidationWithoutRedisReread() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch staleLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseStaleLoad = new CountDownLatch(1);
		CountDownLatch invalidationStarted = new CountDownLatch(1);
		CountDownLatch releaseInvalidation = new CountDownLatch(1);
		doAnswer(invocation -> {
			invalidationStarted.countDown();
			await(releaseInvalidation);
			return 1L;
		}).when(redisClient).execute(
			any(RedisScript.class), eq(List.of(LOAD_PERMIT_KEY, CACHE_KEY)));

		AtomicInteger leaderLoads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				if (leaderLoads.incrementAndGet() == 1) {
					staleLoadStarted.countDown();
					await(releaseStaleLoad);
					return snapshot(1L, "old");
				}
				return snapshot(1L, "new");
			}));
		assertThat(staleLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

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

		CompletableFuture<Void> eviction = CompletableFuture.runAsync(
			() -> cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION));
		assertThat(invalidationStarted.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(leader.isDone()).isFalse();
		assertThat(follower.isDone()).isFalse();
		verify(redisClient, times(1)).get(CACHE_KEY);

		releaseInvalidation.countDown();
		eviction.get(5, TimeUnit.SECONDS);
		assertThat(follower.get(5, TimeUnit.SECONDS).name()).isEqualTo("new");

		releaseStaleLoad.countDown();
		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("new");
		assertThat(leaderLoads).hasValue(2);
		assertThat(followerLoads).hasValue(1);
		verify(redisClient, times(1)).get(CACHE_KEY);
	}

	@Test
	@DisplayName("무효화가 404 조회보다 먼저 완료되면 leader도 DB를 다시 조회한다")
	void invalidatedNotFoundLeaderRetriesDatabaseLoad() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch firstLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstLoad = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				if (loads.incrementAndGet() == 1) {
					firstLoadStarted.countDown();
					await(releaseFirstLoad);
					throw new AccommodationNotFoundException();
				}
				return snapshot(1L, "published");
			}));
		assertThat(firstLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		releaseFirstLoad.countDown();

		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("published");
		assertThat(loads).hasValue(2);
		verify(metricRecorder).recordRequest(
			AccommodationDetailCacheMetricRecorder.RequestResult.LOADED);
	}

	@Test
	@DisplayName("무효화와 겹친 일반 DB 예외는 재시도하지 않는다")
	void invalidatedFailedLeaderDoesNotRetryDatabaseLoad() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch loadStarted = new CountDownLatch(1);
		CountDownLatch releaseLoad = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				loads.incrementAndGet();
				loadStarted.countDown();
				await(releaseLoad);
				throw new IllegalStateException("database failure");
			}));
		assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		releaseLoad.countDown();

		assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(IllegalStateException.class);
		assertThat(loads).hasValue(1);
	}

	@Test
	@DisplayName("무효화 경쟁으로 인한 leader 재조회는 한 번으로 제한한다")
	void invalidatedLeaderRetryIsBounded() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch firstLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstLoad = new CountDownLatch(1);
		CountDownLatch retryStarted = new CountDownLatch(1);
		CountDownLatch releaseRetry = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				int attempt = loads.incrementAndGet();
				if (attempt == 1) {
					firstLoadStarted.countDown();
					await(releaseFirstLoad);
					return snapshot(1L, "old");
				}
				retryStarted.countDown();
				await(releaseRetry);
				return snapshot(1L, "new");
			}));
		assertThat(firstLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		releaseFirstLoad.countDown();
		assertThat(retryStarted.await(5, TimeUnit.SECONDS)).isTrue();
		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		releaseRetry.countDown();

		assertThat(leader.get(5, TimeUnit.SECONDS).name()).isEqualTo("new");
		assertThat(loads).hasValue(2);
	}

	@Test
	@DisplayName("무효화된 조회의 재시도가 404여도 추가로 재조회하지 않는다")
	void invalidatedLeaderNotFoundRetryIsBounded() throws Exception {
		when(redisClient.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis down"));
		CountDownLatch firstLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstLoad = new CountDownLatch(1);
		AtomicInteger loads = new AtomicInteger();
		CompletableFuture<AccommodationDetailSnapshot> leader = CompletableFuture.supplyAsync(
			() -> cache.getOrLoad(1L, () -> {
				if (loads.incrementAndGet() == 1) {
					firstLoadStarted.countDown();
					await(releaseFirstLoad);
					return snapshot(1L, "old");
				}
				throw new AccommodationNotFoundException();
			}));
		assertThat(firstLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		cache.evict(1L, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
		releaseFirstLoad.countDown();

		assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(AccommodationNotFoundException.class);
		assertThat(loads).hasValue(2);
		verify(metricRecorder).recordRequest(
			AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_LOADED);
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

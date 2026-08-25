package kr.kro.airbob.domain.accommodation.cache;

import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.COALESCED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.LOADED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_COALESCED;
import static kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder.RequestResult.NEGATIVE_LOADED;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;

/**
 * Redis를 우회한 동일 JVM 요청의 DB 조회를 하나의 Future로 합침
 */
final class AccommodationDetailLocalLoadCoordinator {

	private final ConcurrentHashMap<Long, CompletableFuture<AccommodationDetailSnapshot>> localLoads =
		new ConcurrentHashMap<>();
	private final AccommodationDetailCacheMetricRecorder metricRecorder;
	private final Duration localLoadWait;

	AccommodationDetailLocalLoadCoordinator(
		AccommodationDetailCacheMetricRecorder metricRecorder,
		Duration localLoadWait
	) {
		this.metricRecorder = metricRecorder;
		this.localLoadWait = localLoadWait;
	}

	Optional<AccommodationDetailSnapshot> joinIfRunning(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		CompletableFuture<AccommodationDetailSnapshot> running = localLoads.get(accommodationId);
		if (running == null) {
			return Optional.empty();
		}
		return Optional.of(await(accommodationId, running, loader));
	}

	AccommodationDetailSnapshot loadOrJoin(
		Long accommodationId,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		CompletableFuture<AccommodationDetailSnapshot> newLoad = new CompletableFuture<>();
		CompletableFuture<AccommodationDetailSnapshot> existing =
			localLoads.putIfAbsent(accommodationId, newLoad);
		if (existing != null) {
			return await(accommodationId, existing, loader);
		}

		try {
			AccommodationDetailSnapshot snapshot = loader.get();
			newLoad.complete(snapshot);
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			newLoad.completeExceptionally(exception);
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		} catch (RuntimeException exception) {
			newLoad.completeExceptionally(exception);
			throw exception;
		} finally {
			localLoads.remove(accommodationId, newLoad);
		}
	}

	AccommodationDetailSnapshot loadDirect(Supplier<AccommodationDetailSnapshot> loader) {
		try {
			AccommodationDetailSnapshot snapshot = loader.get();
			metricRecorder.recordRequest(LOADED);
			return snapshot;
		} catch (AccommodationNotFoundException exception) {
			metricRecorder.recordRequest(NEGATIVE_LOADED);
			throw exception;
		}
	}

	void detachCurrent(Long accommodationId) {
		CompletableFuture<AccommodationDetailSnapshot> captured = localLoads.get(accommodationId);
		if (captured != null) {
			// 포착한 Future만 제거해 그 사이 등록된 새 조회를 잘못 분리하지 않음
			localLoads.remove(accommodationId, captured);
		}
	}

	private AccommodationDetailSnapshot await(
		Long accommodationId,
		CompletableFuture<AccommodationDetailSnapshot> load,
		Supplier<AccommodationDetailSnapshot> loader
	) {
		try {
			AccommodationDetailSnapshot snapshot = load.get(localLoadWait.toMillis(), TimeUnit.MILLISECONDS);
			metricRecorder.recordRequest(COALESCED);
			return snapshot;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("숙소 상세 단일 조회 대기 중 인터럽트됨", exception);
		} catch (TimeoutException exception) {
			// 느린 leader를 더 기다리지 않고 이 요청은 독립적으로 DB를 조회
			localLoads.remove(accommodationId, load);
			return loadDirect(loader);
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof AccommodationNotFoundException notFoundException) {
				metricRecorder.recordRequest(NEGATIVE_COALESCED);
				throw notFoundException;
			}
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("숙소 상세 단일 조회가 실패함", exception.getCause());
		}
	}
}

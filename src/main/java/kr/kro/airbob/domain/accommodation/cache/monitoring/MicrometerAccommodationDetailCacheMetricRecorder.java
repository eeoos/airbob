package kr.kro.airbob.domain.accommodation.cache.monitoring;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder;

/**
 * 고정된 enum 조합의 Meter를 시작 시 등록해 요청 중 동적 생성과 고카디널리티 증가를 막음
 */
@Component
public class MicrometerAccommodationDetailCacheMetricRecorder
	implements AccommodationDetailCacheMetricRecorder {

	public static final String REQUEST_TOTAL = "accommodation.detail.cache.request";
	public static final String LOCK_WAIT_DURATION = "accommodation.detail.cache.lock.wait.duration";
	public static final String LOAD_DURATION = "accommodation.detail.cache.load.duration";
	public static final String REDIS_OPERATION_TOTAL = "accommodation.detail.cache.redis.operation";
	public static final String EVICTION_TOTAL = "accommodation.detail.cache.eviction";

	private static final Duration[] LOCK_SLOS = durations(1, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_000);
	private static final Duration[] LOAD_SLOS = durations(5, 10, 25, 50, 100, 250, 500, 1_000, 2_000, 5_000);

	private final Map<RequestResult, Counter> requestCounters;
	private final Map<LockResult, Timer> lockTimers;
	private final Map<LoadResult, Timer> loadTimers;
	private final Map<RedisOperation, Map<OperationResult, Counter>> redisCounters;
	private final Map<EvictionSource,
		Map<AccommodationDetailCacheInvalidationReason, Map<OperationResult, Counter>>> evictionCounters;

	public MicrometerAccommodationDetailCacheMetricRecorder(MeterRegistry meterRegistry) {
		requestCounters = enumMap(RequestResult.class, result -> counter(
			meterRegistry, REQUEST_TOTAL, "Accommodation detail cache request outcomes",
			"result", result.tagValue()));
		lockTimers = enumMap(LockResult.class, result -> timer(
			meterRegistry, LOCK_WAIT_DURATION, "Accommodation detail cache lock wait duration",
			LOCK_SLOS, "result", result.tagValue()));
		loadTimers = enumMap(LoadResult.class, result -> timer(
			meterRegistry, LOAD_DURATION, "Accommodation detail database load duration",
			LOAD_SLOS, "result", result.tagValue()));
		redisCounters = enumMap(RedisOperation.class, operation ->
			enumMap(OperationResult.class, result -> counter(
				meterRegistry, REDIS_OPERATION_TOTAL, "Accommodation detail cache Redis operations",
				"operation", operation.tagValue(), "result", result.tagValue())));
		evictionCounters = evictionCounters(meterRegistry);
	}

	@Override
	public void recordRequest(RequestResult result) {
		requestCounters.get(result).increment();
	}

	@Override
	public void recordLock(LockResult result, long durationNanos) {
		lockTimers.get(result).record(durationNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void recordLoad(LoadResult result, long durationNanos) {
		loadTimers.get(result).record(durationNanos, TimeUnit.NANOSECONDS);
	}

	@Override
	public void recordRedis(RedisOperation operation, OperationResult result) {
		redisCounters.get(operation).get(result).increment();
	}

	@Override
	public void recordEviction(
		EvictionSource source,
		AccommodationDetailCacheInvalidationReason reason,
		OperationResult result
	) {
		evictionCounters.get(source).get(reason).get(result).increment();
	}

	private Map<EvictionSource,
		Map<AccommodationDetailCacheInvalidationReason, Map<OperationResult, Counter>>> evictionCounters(
		MeterRegistry meterRegistry
	) {
		return enumMap(EvictionSource.class, source ->
			enumMap(AccommodationDetailCacheInvalidationReason.class, reason ->
				enumMap(OperationResult.class, result -> counter(
						meterRegistry,
						EVICTION_TOTAL,
						"Accommodation detail cache eviction outcomes",
						"source", source.tagValue(),
						"reason", reason.tagValue(),
						"result", result.tagValue()
					))));
	}

	private Counter counter(MeterRegistry meterRegistry, String name, String description, String... tags) {
		return Counter.builder(name).description(description).tags(tags).register(meterRegistry);
	}

	private Timer timer(
		MeterRegistry meterRegistry,
		String name,
		String description,
		Duration[] slos,
		String... tags
	) {
		return Timer.builder(name)
			.description(description)
			.tags(tags)
			.publishPercentileHistogram()
			.serviceLevelObjectives(slos)
			.register(meterRegistry);
	}

	private <E extends Enum<E>, V> Map<E, V> enumMap(
		Class<E> enumType,
		Function<E, V> valueFactory
	) {
		Map<E, V> result = new EnumMap<>(enumType);
		for (E value : enumType.getEnumConstants()) {
			result.put(value, valueFactory.apply(value));
		}
		return result;
	}

	private static Duration[] durations(long... milliseconds) {
		Duration[] durations = new Duration[milliseconds.length];
		for (int index = 0; index < milliseconds.length; index++) {
			durations[index] = Duration.ofMillis(milliseconds[index]);
		}
		return durations;
	}
}

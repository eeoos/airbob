package kr.kro.airbob.domain.accommodation.cache.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheMetricRecorder;

@DisplayName("숙소 상세 캐시 Micrometer 지표 테스트")
class MicrometerAccommodationDetailCacheMetricRecorderTest {

	private SimpleMeterRegistry registry;
	private MicrometerAccommodationDetailCacheMetricRecorder recorder;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		recorder = new MicrometerAccommodationDetailCacheMetricRecorder(registry);
	}

	@Test
	@DisplayName("캐시 결과와 락·DB 지연 및 무효화 결과를 기록한다")
	void recordsCacheOutcomesAndDurations() {
		recorder.recordRequest(AccommodationDetailCacheMetricRecorder.RequestResult.HIT);
		recorder.recordLock(AccommodationDetailCacheMetricRecorder.LockResult.ACQUIRED,
			Duration.ofMillis(25).toNanos());
		recorder.recordLoad(AccommodationDetailCacheMetricRecorder.LoadResult.FOUND,
			Duration.ofMillis(80).toNanos());
		recorder.recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.AFTER_COMMIT,
			AccommodationDetailCacheInvalidationReason.REVIEW,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);

		Counter hit = registry.find(MicrometerAccommodationDetailCacheMetricRecorder.REQUEST_TOTAL)
			.tag("result", "hit").counter();
		Timer lock = registry.find(MicrometerAccommodationDetailCacheMetricRecorder.LOCK_WAIT_DURATION)
			.tag("result", "acquired").timer();
		Timer load = registry.find(MicrometerAccommodationDetailCacheMetricRecorder.LOAD_DURATION)
			.tag("result", "found").timer();
		Counter eviction = registry.find(MicrometerAccommodationDetailCacheMetricRecorder.EVICTION_TOTAL)
			.tags("source", "after_commit", "reason", "review", "result", "success").counter();

		assertThat(hit).isNotNull();
		assertThat(hit.count()).isOne();
		assertThat(lock.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);
		assertThat(load.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(80.0);
		assertThat(eviction.count()).isOne();
	}

	@Test
	@DisplayName("숙소 ID와 사용자 ID를 메트릭 태그로 사용하지 않는다")
	void avoidsHighCardinalityIdentifierTags() {
		recorder.recordRequest(AccommodationDetailCacheMetricRecorder.RequestResult.LOADED);
		recorder.recordRedis(
			AccommodationDetailCacheMetricRecorder.RedisOperation.GET,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);
		recorder.recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.OUTBOX,
			AccommodationDetailCacheInvalidationReason.IMAGE,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);

		Set<String> forbidden = Set.of("accommodation_id", "accommodationId", "viewer_id", "viewerId");
		assertThat(registry.getMeters().stream()
			.flatMap(meter -> meter.getId().getTags().stream())
			.map(tag -> tag.getKey())
			.toList())
			.doesNotContainAnyElementsOf(forbidden);
	}

	@Test
	@DisplayName("Grafana 대시보드가 사용하는 Prometheus 이름과 무효화 source 태그를 노출한다")
	void exportsDashboardMetricNamesAndEvictionSource() {
		PrometheusMeterRegistry prometheusRegistry =
			new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		MicrometerAccommodationDetailCacheMetricRecorder prometheusRecorder =
			new MicrometerAccommodationDetailCacheMetricRecorder(prometheusRegistry);

		prometheusRecorder.recordRequest(AccommodationDetailCacheMetricRecorder.RequestResult.HIT);
		prometheusRecorder.recordLock(
			AccommodationDetailCacheMetricRecorder.LockResult.ACQUIRED,
			Duration.ofMillis(25).toNanos());
		prometheusRecorder.recordLoad(
			AccommodationDetailCacheMetricRecorder.LoadResult.FOUND,
			Duration.ofMillis(80).toNanos());
		prometheusRecorder.recordRedis(
			AccommodationDetailCacheMetricRecorder.RedisOperation.GET,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);
		prometheusRecorder.recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.OUTBOX,
			AccommodationDetailCacheInvalidationReason.REVIEW,
			AccommodationDetailCacheMetricRecorder.OperationResult.SUCCESS);
		prometheusRecorder.recordEviction(
			AccommodationDetailCacheMetricRecorder.EvictionSource.AFTER_COMMIT,
			AccommodationDetailCacheInvalidationReason.IMAGE,
			AccommodationDetailCacheMetricRecorder.OperationResult.ERROR);

		assertThat(prometheusRegistry.scrape())
			.contains("accommodation_detail_cache_request_total")
			.contains("accommodation_detail_cache_lock_wait_duration_seconds_bucket")
			.contains("accommodation_detail_cache_load_duration_seconds_bucket")
			.contains("accommodation_detail_cache_redis_operation_total")
			.contains("accommodation_detail_cache_eviction_total")
			.contains("source=\"outbox\"")
			.contains("source=\"after_commit\"");
	}
}

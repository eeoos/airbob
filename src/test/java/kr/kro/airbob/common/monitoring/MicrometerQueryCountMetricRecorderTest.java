package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("Micrometer 쿼리 카운트 메트릭 기록 테스트")
class MicrometerQueryCountMetricRecorderTest {

	private SimpleMeterRegistry meterRegistry;
	private MicrometerQueryCountMetricRecorder recorder;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		recorder = new MicrometerQueryCountMetricRecorder(meterRegistry);
	}

	@Test
	@DisplayName("요청 스냅샷의 쿼리 타입별 카운트를 DistributionSummary에 기록한다")
	void recordsQueryCountsByType() {
		QueryCountSnapshot snapshot = new QueryCountSnapshot(
			"GET",
			"/api/v1/accommodations/{accommodationId}",
			queryCounts(Map.of(
				SqlQueryType.SELECT, 3,
				SqlQueryType.INSERT, 1,
				SqlQueryType.TOTAL, 4
			))
		);

		recorder.record(snapshot);

		assertSummary(SqlQueryType.SELECT, 1L, 3.0);
		assertSummary(SqlQueryType.INSERT, 1L, 1.0);
		assertSummary(SqlQueryType.UPDATE, 1L, 0.0);
		assertSummary(SqlQueryType.DELETE, 1L, 0.0);
		assertSummary(SqlQueryType.OTHER, 1L, 0.0);
		assertSummary(SqlQueryType.TOTAL, 1L, 4.0);
	}

	@Test
	@DisplayName("Prometheus에서 합산 가능한 histogram bucket을 내보내고 client-side percentile은 만들지 않는다")
	void publishesHistogramBucketsWithoutClientSidePercentiles() {
		QueryCountSnapshot snapshot = new QueryCountSnapshot(
			"GET",
			"/api/v1/accommodations/{accommodationId}",
			queryCounts(Map.of(SqlQueryType.SELECT, 12, SqlQueryType.TOTAL, 12))
		);

		recorder.record(snapshot);

		DistributionSummary summary = summary(SqlQueryType.SELECT);
		assertThat(summary.takeSnapshot().percentileValues()).isEmpty();
		assertThat(Arrays.stream(summary.takeSnapshot().histogramCounts())
			.map(CountAtBucket::bucket)
			.toList()
		).contains(1.0, 3.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0);
	}

	private void assertSummary(SqlQueryType queryType, long count, double totalAmount) {
		DistributionSummary summary = summary(queryType);
		assertThat(summary).isNotNull();
		assertThat(summary.count()).isEqualTo(count);
		assertThat(summary.totalAmount()).isEqualTo(totalAmount);
	}

	private DistributionSummary summary(SqlQueryType queryType) {
		return meterRegistry.find(MicrometerQueryCountMetricRecorder.METRIC_NAME)
			.tag("path", "/api/v1/accommodations/{accommodationId}")
			.tag("http_method", "GET")
			.tag("query_type", queryType.name())
			.summary();
	}

	private EnumMap<SqlQueryType, Integer> queryCounts(Map<SqlQueryType, Integer> values) {
		return new EnumMap<>(values);
	}
}

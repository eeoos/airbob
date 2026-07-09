package kr.kro.airbob.common.monitoring;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MicrometerQueryCountMetricRecorder implements QueryCountMetricRecorder {

	public static final String METRIC_NAME = "app.query.per_request";

	private static final String UNKNOWN_TAG_VALUE = "UNKNOWN";
	private static final double[] QUERY_COUNT_BUCKETS = {1, 3, 5, 10, 20, 50, 100, 200};

	private final MeterRegistry meterRegistry;

	@Override
	public void record(QueryCountSnapshot snapshot) {
		for (SqlQueryType queryType : SqlQueryType.values()) {
			record(snapshot, queryType);
		}
	}

	private void record(QueryCountSnapshot snapshot, SqlQueryType queryType) {
		DistributionSummary summary = DistributionSummary.builder(METRIC_NAME)
			.description("Number of SQL queries per HTTP request")
			.baseUnit("queries")
			.tag("path", tagValue(snapshot.bestMatchPath()))
			.tag("http_method", tagValue(snapshot.httpMethod()))
			.tag("query_type", queryType.name())
			.publishPercentileHistogram()
			.serviceLevelObjectives(QUERY_COUNT_BUCKETS)
			.minimumExpectedValue(1.0)
			.maximumExpectedValue(200.0)
			.register(meterRegistry);

		summary.record(snapshot.countOf(queryType));
	}

	private String tagValue(String value) {
		if (value == null || value.isBlank()) {
			return UNKNOWN_TAG_VALUE;
		}
		return value;
	}
}

package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("쿼리 카운트 모니터링 구성 요소 흐름 테스트")
class QueryCountMonitoringFlowTest {

	@AfterEach
	void tearDown() {
		QueryCountContextHolder.clear();
	}

	@Test
	@DisplayName("인터셉터와 SQL inspector를 조합하면 route template 단위 메트릭을 기록한다")
	void recordsInspectedSqlAsRouteTemplateMetric() throws Exception {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		QueryCountInterceptor interceptor = new QueryCountInterceptor(
			new MicrometerQueryCountMetricRecorder(meterRegistry)
		);
		SqlQueryStatementInspector inspector = new SqlQueryStatementInspector();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accommodations/10");
		request.setAttribute(
			HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
			"/api/v1/accommodations/{accommodationId}"
		);

		interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
		inspector.inspect("select * from accommodation where id = ?");
		inspector.inspect("/* Hibernate */ select * from review where accommodation_id = ?");
		inspector.inspect("update accommodation set view_count = view_count + 1 where id = ?");
		interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

		assertThat(summary(meterRegistry, SqlQueryType.SELECT).totalAmount()).isEqualTo(2.0);
		assertThat(summary(meterRegistry, SqlQueryType.UPDATE).totalAmount()).isEqualTo(1.0);
		assertThat(summary(meterRegistry, SqlQueryType.TOTAL).totalAmount()).isEqualTo(3.0);
		assertThat(meterRegistry.find(MicrometerQueryCountMetricRecorder.METRIC_NAME)
			.tag("path", "/api/v1/accommodations/10")
			.summary()
		).isNull();
		assertThat(QueryCountContextHolder.getContext()).isNull();
	}

	private DistributionSummary summary(SimpleMeterRegistry meterRegistry, SqlQueryType queryType) {
		DistributionSummary summary = meterRegistry.find(MicrometerQueryCountMetricRecorder.METRIC_NAME)
			.tag("path", "/api/v1/accommodations/{accommodationId}")
			.tag("http_method", "GET")
			.tag("query_type", queryType.name())
			.summary();

		assertThat(summary).isNotNull();
		return summary;
	}
}

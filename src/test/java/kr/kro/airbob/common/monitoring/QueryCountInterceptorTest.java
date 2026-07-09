package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿼리 카운트 인터셉터 테스트")
class QueryCountInterceptorTest {

	@Mock
	private QueryCountMetricRecorder metricRecorder;

	private QueryCountInterceptor interceptor;

	@BeforeEach
	void setUp() {
		interceptor = new QueryCountInterceptor(metricRecorder);
	}

	@AfterEach
	void tearDown() {
		QueryCountContextHolder.clear();
	}

	@Test
	@DisplayName("컨트롤러 실행 전 HTTP 메서드와 best matching path로 요청 컨텍스트를 만든다")
	void preHandleInitializesRequestContextWithBestMatchingPath() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accommodations/1");
		request.setAttribute(
			HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
			"/api/v1/accommodations/{accommodationId}"
		);

		boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

		assertThat(result).isTrue();
		QueryCountSnapshot snapshot = QueryCountContextHolder.getContext().snapshot();
		assertThat(snapshot.httpMethod()).isEqualTo("GET");
		assertThat(snapshot.bestMatchPath()).isEqualTo("/api/v1/accommodations/{accommodationId}");
	}

	@Test
	@DisplayName("best matching path를 찾지 못하면 UNKNOWN_PATH로 컨텍스트를 만든다")
	void preHandleUsesUnknownPathFallback() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/unmatched");

		interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

		QueryCountSnapshot snapshot = QueryCountContextHolder.getContext().snapshot();
		assertThat(snapshot.httpMethod()).isEqualTo("POST");
		assertThat(snapshot.bestMatchPath()).isEqualTo(QueryCountInterceptor.UNKNOWN_PATH);
	}

	@Test
	@DisplayName("요청 완료 시 누적된 쿼리 카운트 스냅샷을 기록하고 컨텍스트를 정리한다")
	void afterCompletionRecordsSnapshotAndClearsContext() throws Exception {
		QueryCountContext context = new QueryCountContext("GET", "/api/v1/accommodations");
		context.incrementQueryCount("select * from accommodation");
		QueryCountContextHolder.initContext(context);
		ArgumentCaptor<QueryCountSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QueryCountSnapshot.class);

		interceptor.afterCompletion(
			new MockHttpServletRequest(),
			new MockHttpServletResponse(),
			new Object(),
			null
		);

		then(metricRecorder).should().record(snapshotCaptor.capture());
		QueryCountSnapshot snapshot = snapshotCaptor.getValue();
		assertThat(snapshot.httpMethod()).isEqualTo("GET");
		assertThat(snapshot.bestMatchPath()).isEqualTo("/api/v1/accommodations");
		assertThat(snapshot.countOf(SqlQueryType.SELECT)).isEqualTo(1);
		assertThat(snapshot.countOf(SqlQueryType.TOTAL)).isEqualTo(1);
		assertThat(QueryCountContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("메트릭 기록 중 예외가 발생해도 컨텍스트를 정리한다")
	void afterCompletionClearsContextWhenMetricRecordingFails() {
		QueryCountContextHolder.initContext(new QueryCountContext("GET", "/api/v1/accommodations"));
		willThrow(new IllegalStateException("record failed")).given(metricRecorder).record(any(QueryCountSnapshot.class));

		assertThatThrownBy(() -> interceptor.afterCompletion(
			new MockHttpServletRequest(),
			new MockHttpServletResponse(),
			new Object(),
			null
		)).isInstanceOf(IllegalStateException.class);

		assertThat(QueryCountContextHolder.getContext()).isNull();
	}
}

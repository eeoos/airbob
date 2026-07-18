package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.util.Collections;

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
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;

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

	@Test
	@DisplayName("비동기 처리로 전환할 때 컨테이너 스레드의 컨텍스트만 정리하고 미완료 요청은 기록하지 않는다")
	void asyncHandoffClearsContextWithoutRecording() throws Exception {
		QueryCountContext context = new QueryCountContext("GET", "/api/v1/stream");
		context.incrementQueryCount("select * from accommodation");
		QueryCountContextHolder.initContext(context);
		AsyncHandlerInterceptor asyncInterceptor = interceptor;

		asyncInterceptor.afterConcurrentHandlingStarted(
			new MockHttpServletRequest(),
			new MockHttpServletResponse(),
			new Object()
		);

		assertThat(QueryCountContextHolder.getContext()).isNull();
		then(metricRecorder).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("비동기 재디스패치는 handoff 전 카운트를 복원해 종료 시 정확히 한 번 기록한다")
	void asyncRedispatchRestoresOriginalContextAndRecordsExactlyOnce() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stream");
		MockHttpServletResponse response = new MockHttpServletResponse();
		request.setAsyncSupported(true);
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/stream");

		interceptor.preHandle(request, response, new Object());
		QueryCountContext originalContext = QueryCountContextHolder.getContext();
		originalContext.incrementQueryCount("select * from accommodation");
		AsyncContext asyncContext = request.startAsync(request, response);

		interceptor.afterConcurrentHandlingStarted(request, response, new Object());

		assertThat(QueryCountContextHolder.getContext()).isNull();
		then(metricRecorder).shouldHaveNoInteractions();

		request.setDispatcherType(DispatcherType.ASYNC);
		interceptor.preHandle(request, response, new Object());

		assertThat(QueryCountContextHolder.getContext()).isSameAs(originalContext);
		QueryCountContextHolder.getContext().incrementQueryCount("update accommodation set name = ?");

		interceptor.afterCompletion(request, response, new Object(), null);
		interceptor.afterCompletion(request, response, new Object(), null);
		asyncContext.complete();

		ArgumentCaptor<QueryCountSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QueryCountSnapshot.class);
		then(metricRecorder).should(times(1)).record(snapshotCaptor.capture());
		assertThat(snapshotCaptor.getValue().countOf(SqlQueryType.SELECT)).isEqualTo(1);
		assertThat(snapshotCaptor.getValue().countOf(SqlQueryType.UPDATE)).isEqualTo(1);
		assertThat(snapshotCaptor.getValue().countOf(SqlQueryType.TOTAL)).isEqualTo(2);
		assertThat(QueryCountContextHolder.getContext()).isNull();
		assertThat(Collections.list(request.getAttributeNames()))
			.noneMatch(name -> name.startsWith(QueryCountInterceptor.class.getName()));
	}

	@Test
	@DisplayName("재디스패치 없이 async complete되면 listener가 분리된 카운트를 정확히 한 번 기록한다")
	void asyncCompletionWithoutRedispatchRecordsDetachedContextExactlyOnce() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stream");
		MockHttpServletResponse response = new MockHttpServletResponse();
		request.setAsyncSupported(true);
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/stream");

		interceptor.preHandle(request, response, new Object());
		QueryCountContextHolder.getContext().incrementQueryCount("select * from accommodation");
		AsyncContext asyncContext = request.startAsync(request, response);
		interceptor.afterConcurrentHandlingStarted(request, response, new Object());

		asyncContext.complete();
		interceptor.afterCompletion(request, response, new Object(), null);

		ArgumentCaptor<QueryCountSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QueryCountSnapshot.class);
		then(metricRecorder).should(times(1)).record(snapshotCaptor.capture());
		assertThat(snapshotCaptor.getValue().countOf(SqlQueryType.SELECT)).isEqualTo(1);
		assertThat(snapshotCaptor.getValue().countOf(SqlQueryType.TOTAL)).isEqualTo(1);
		assertThat(QueryCountContextHolder.getContext()).isNull();
		assertThat(Collections.list(request.getAttributeNames()))
			.noneMatch(name -> name.startsWith(QueryCountInterceptor.class.getName()));
	}
}

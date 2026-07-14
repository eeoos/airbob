package kr.kro.airbob.common.monitoring;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class QueryCountInterceptor implements AsyncHandlerInterceptor {

	public static final String UNKNOWN_PATH = "UNKNOWN_PATH";

	private final QueryCountMetricRecorder metricRecorder;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String bestMatchPath = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (bestMatchPath == null) {
			bestMatchPath = UNKNOWN_PATH;
		}

		QueryCountContext context = new QueryCountContext(request.getMethod(), bestMatchPath);
		QueryCountContextHolder.initContext(context);
		return true;
	}

	@Override
	public void afterCompletion(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler,
		Exception ex
	) {
		QueryCountContext context = QueryCountContextHolder.getContext();
		try {
			if (context != null) {
				metricRecorder.record(context.snapshot());
			}
		} finally {
			QueryCountContextHolder.clear();
		}
	}

	@Override
	public void afterConcurrentHandlingStarted(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler
	) {
		QueryCountContextHolder.clear();
	}
}

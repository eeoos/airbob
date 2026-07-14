package kr.kro.airbob.common.monitoring;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class QueryCountInterceptor implements AsyncHandlerInterceptor {

	public static final String UNKNOWN_PATH = "UNKNOWN_PATH";

	private static final String REQUEST_STATE_ATTRIBUTE = QueryCountInterceptor.class.getName() + ".STATE";

	private final QueryCountMetricRecorder metricRecorder;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		RequestState state = requestState(request);
		if (state == null || state.isCompleted()) {
			String bestMatchPath = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			if (bestMatchPath == null) {
				bestMatchPath = UNKNOWN_PATH;
			}

			state = new RequestState(new QueryCountContext(request.getMethod(), bestMatchPath));
			request.setAttribute(REQUEST_STATE_ATTRIBUTE, state);
		}

		QueryCountContextHolder.initContext(state.context());
		return true;
	}

	@Override
	public void afterCompletion(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler,
		Exception ex
	) {
		completeRequest(request, requestState(request), QueryCountContextHolder.getContext());
	}

	@Override
	public void afterConcurrentHandlingStarted(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler
	) {
		QueryCountContext context = QueryCountContextHolder.getContext();
		try {
			if (context == null) {
				return;
			}

			RequestState state = requestState(request);
			if (state == null || state.context() != context) {
				state = new RequestState(context);
				request.setAttribute(REQUEST_STATE_ATTRIBUTE, state);
			}
			registerCompletionListener(request, response, state);
		} finally {
			QueryCountContextHolder.clear();
		}
	}

	private void registerCompletionListener(
		HttpServletRequest request,
		HttpServletResponse response,
		RequestState state
	) {
		if (!request.isAsyncStarted() || !state.tryRegisterListener()) {
			return;
		}

		try {
			request.getAsyncContext().addListener(
				new QueryCountAsyncListener(request, response, state),
				request,
				response
			);
		} catch (IllegalStateException ex) {
			state.allowListenerRetry();
		}
	}

	private void completeRequest(
		HttpServletRequest request,
		RequestState state,
		QueryCountContext fallbackContext
	) {
		RequestState completionState = state;
		if (completionState == null && fallbackContext != null) {
			completionState = new RequestState(fallbackContext);
		}

		try {
			if (completionState != null && completionState.tryComplete()) {
				metricRecorder.record(completionState.context().snapshot());
			}
		} finally {
			if (request.getAttribute(REQUEST_STATE_ATTRIBUTE) == completionState) {
				request.removeAttribute(REQUEST_STATE_ATTRIBUTE);
			}
			QueryCountContextHolder.clear();
		}
	}

	private RequestState requestState(HttpServletRequest request) {
		Object attribute = request.getAttribute(REQUEST_STATE_ATTRIBUTE);
		return attribute instanceof RequestState state ? state : null;
	}

	private final class QueryCountAsyncListener implements AsyncListener {

		private final HttpServletRequest request;
		private final HttpServletResponse response;
		private final RequestState state;

		private QueryCountAsyncListener(
			HttpServletRequest request,
			HttpServletResponse response,
			RequestState state
		) {
			this.request = request;
			this.response = response;
			this.state = state;
		}

		@Override
		public void onComplete(AsyncEvent event) {
			completeRequest(request, state, null);
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			// Spring may still redispatch a timeout result; onComplete is the exact terminal fallback.
		}

		@Override
		public void onError(AsyncEvent event) {
			// Spring may still redispatch an error result; onComplete is the exact terminal fallback.
		}

		@Override
		public void onStartAsync(AsyncEvent event) throws IOException {
			event.getAsyncContext().addListener(this, request, response);
		}
	}

	private static final class RequestState {

		private final QueryCountContext context;
		private final AtomicBoolean completed = new AtomicBoolean();
		private final AtomicBoolean listenerRegistered = new AtomicBoolean();

		private RequestState(QueryCountContext context) {
			this.context = context;
		}

		private QueryCountContext context() {
			return context;
		}

		private boolean isCompleted() {
			return completed.get();
		}

		private boolean tryComplete() {
			return completed.compareAndSet(false, true);
		}

		private boolean tryRegisterListener() {
			return listenerRegistered.compareAndSet(false, true);
		}

		private void allowListenerRetry() {
			listenerRegistered.set(false);
		}
	}
}

package kr.kro.airbob.common.monitoring;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class QueryCountContextHolder {

	private static final ThreadLocal<QueryCountContext> CONTEXT = new ThreadLocal<>();

	public static void initContext(QueryCountContext context) {
		CONTEXT.remove();
		CONTEXT.set(context);
	}

	public static QueryCountContext getContext() {
		return CONTEXT.get();
	}

	public static void clear() {
		CONTEXT.remove();
	}
}

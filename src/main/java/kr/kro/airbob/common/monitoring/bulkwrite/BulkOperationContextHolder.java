package kr.kro.airbob.common.monitoring.bulkwrite;

import java.util.Objects;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BulkOperationContextHolder {

	private static final ThreadLocal<BulkOperationContext> CONTEXT = new ThreadLocal<>();

	public static void initContext(BulkOperationContext context) {
		Objects.requireNonNull(context, "context must not be null");
		if (CONTEXT.get() != null) {
			throw new IllegalStateException("A bulk operation context is already active on this thread");
		}
		CONTEXT.set(context);
	}

	public static BulkOperationContext getContext() {
		return CONTEXT.get();
	}

	public static void clear() {
		CONTEXT.remove();
	}
}

package kr.kro.airbob.core.domain.payment;

import java.util.UUID;
import java.util.function.Supplier;

public interface PaymentOperationExecutionFence {

	<T> T execute(UUID operationUid, Supplier<T> action);
}

package kr.kro.airbob.domain.payment.service;

import java.util.UUID;
import java.util.function.Supplier;

public interface PaymentOperationExecutionFence {

	<T> T execute(UUID operationUid, Supplier<T> action);
}

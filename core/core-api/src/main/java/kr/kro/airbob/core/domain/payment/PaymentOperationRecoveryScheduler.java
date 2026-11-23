package kr.kro.airbob.core.domain.payment;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.payment.monitoring.PaymentOperationRecoveryMetrics;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentOperationRecoveryScheduler {
	private final PaymentOperationRecoveryService recoveryService;
	private final PaymentOperationRecoveryMetrics metrics;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${payment.operation.scheduler-delay:10s}")
	public void recoverPaymentOperations() {
		try {
			recoveryService.recoverDue();
			metrics.recordSuccess(clock.instant());
		} catch (RuntimeException exception) {
			metrics.recordFailure();
			throw exception;
		}
	}
}

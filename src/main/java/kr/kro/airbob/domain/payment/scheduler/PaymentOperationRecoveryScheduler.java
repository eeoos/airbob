package kr.kro.airbob.domain.payment.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentOperationRecoveryScheduler {
	private final PaymentOperationRecoveryService recoveryService;

	@Scheduled(fixedDelayString = "${payment.operation.scheduler-delay:10s}")
	public void recoverPaymentOperations() {
		recoveryService.recoverDue();
	}
}

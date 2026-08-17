package kr.kro.airbob.domain.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;

@ExtendWith(MockitoExtension.class)
class PaymentOperationRecoverySchedulerTest {

	@Mock private PaymentOperationRecoveryService recoveryService;

	private PaymentOperationRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PaymentOperationRecoveryScheduler(recoveryService);
	}

	@Test
	void delegatesOneRecoveryBatch() {
		given(recoveryService.recoverDue()).willReturn(new RecoveryBatch(3));

		scheduler.recoverPaymentOperations();

		then(recoveryService).should().recoverDue();
	}

	@Test
	void usesTheConfiguredTenSecondFixedDelayContract() throws NoSuchMethodException {
		Method method = PaymentOperationRecoveryScheduler.class.getMethod("recoverPaymentOperations");

		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString())
			.isEqualTo("${payment.operation.scheduler-delay:10s}");
	}
}

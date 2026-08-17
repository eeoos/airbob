package kr.kro.airbob.domain.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;
import kr.kro.airbob.domain.payment.monitoring.PaymentOperationRecoveryMetrics;

@ExtendWith(MockitoExtension.class)
class PaymentOperationRecoverySchedulerTest {
	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

	@Mock private PaymentOperationRecoveryService recoveryService;
	@Mock private PaymentOperationRecoveryMetrics metrics;

	private PaymentOperationRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PaymentOperationRecoveryScheduler(
			recoveryService, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void delegatesOneRecoveryBatch() {
		given(recoveryService.recoverDue()).willReturn(new RecoveryBatch(3));

		scheduler.recoverPaymentOperations();

		InOrder ordered = inOrder(recoveryService, metrics);
		ordered.verify(recoveryService).recoverDue();
		ordered.verify(metrics).recordSuccess(NOW);
	}

	@Test
	void recordsFailureAndRethrowsTheOriginalRecoveryError() {
		IllegalStateException failure = new IllegalStateException("database unavailable");
		given(recoveryService.recoverDue()).willThrow(failure);

		assertThatThrownBy(scheduler::recoverPaymentOperations).isSameAs(failure);

		then(metrics).should().recordFailure();
		then(metrics).should(never()).recordSuccess(NOW);
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

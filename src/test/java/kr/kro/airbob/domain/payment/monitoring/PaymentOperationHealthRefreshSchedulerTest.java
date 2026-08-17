package kr.kro.airbob.domain.payment.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class PaymentOperationHealthRefreshSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
	private static final Duration STALE_AFTER = Duration.ofSeconds(30);

	@Mock private PaymentOperationHealthSnapshotRepository repository;
	@Mock private PaymentOperationHealthMetrics metrics;

	@Test
	void refreshesOneReadOnlyAggregateSnapshot() {
		PaymentOperationHealthSnapshot snapshot = PaymentOperationHealthSnapshot.empty(NOW);
		given(repository.readSnapshot(NOW, STALE_AFTER)).willReturn(snapshot);
		PaymentOperationHealthRefreshScheduler scheduler = scheduler();

		scheduler.refreshHealthSnapshot();

		then(repository).should().readSnapshot(NOW, STALE_AFTER);
		then(metrics).should().recordSuccess(snapshot);
	}

	@Test
	void recordsFailureAndRethrowsTheOriginalRefreshError() {
		IllegalStateException failure = new IllegalStateException("database unavailable");
		given(repository.readSnapshot(NOW, STALE_AFTER)).willThrow(failure);
		PaymentOperationHealthRefreshScheduler scheduler = scheduler();

		assertThatThrownBy(scheduler::refreshHealthSnapshot).isSameAs(failure);

		then(metrics).should().recordFailure();
		then(metrics).should(never()).recordSuccess(PaymentOperationHealthSnapshot.empty(NOW));
	}

	@Test
	void usesALightweightThirtySecondDefaultRefresh() throws NoSuchMethodException {
		Method method = PaymentOperationHealthRefreshScheduler.class
			.getMethod("refreshHealthSnapshot");

		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString())
			.isEqualTo("${payment.operation.monitoring.fixed-delay:30s}");
	}

	private PaymentOperationHealthRefreshScheduler scheduler() {
		return new PaymentOperationHealthRefreshScheduler(
			repository,
			metrics,
			Clock.fixed(NOW, ZoneOffset.UTC),
			STALE_AFTER
		);
	}
}

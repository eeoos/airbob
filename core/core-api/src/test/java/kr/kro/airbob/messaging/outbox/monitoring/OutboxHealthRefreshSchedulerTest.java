package kr.kro.airbob.messaging.outbox.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class OutboxHealthRefreshSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

	@Mock private OutboxHealthSnapshotRepository repository;
	@Mock private OutboxHealthMetrics metrics;

	@Test
	void refreshesHealthEvenWhenCleanupIsNotPartOfTheDependencyGraph() {
		OutboxHealthSnapshot snapshot = new OutboxHealthSnapshot(NOW, 0, Optional.empty());
		given(repository.readSnapshot(NOW)).willReturn(snapshot);
		OutboxHealthRefreshScheduler scheduler = scheduler();

		scheduler.refreshHealthSnapshot();

		then(repository).should().readSnapshot(NOW);
		then(metrics).should().recordSuccess(snapshot);
	}

	@Test
	void recordsFailureAndRethrowsTheOriginalRefreshError() {
		IllegalStateException failure = new IllegalStateException("database unavailable");
		given(repository.readSnapshot(NOW)).willThrow(failure);
		OutboxHealthRefreshScheduler scheduler = scheduler();

		assertThatThrownBy(scheduler::refreshHealthSnapshot).isSameAs(failure);

		then(metrics).should().recordFailure();
		then(metrics).should(never()).recordSuccess(
			new OutboxHealthSnapshot(NOW, 0, Optional.empty()));
	}

	@Test
	void usesALightweightThirtySecondDefaultRefresh() throws NoSuchMethodException {
		Method method = OutboxHealthRefreshScheduler.class.getMethod("refreshHealthSnapshot");

		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString())
			.isEqualTo("${messaging.outbox.health.fixed-delay:30s}");
	}

	private OutboxHealthRefreshScheduler scheduler() {
		return new OutboxHealthRefreshScheduler(
			repository,
			metrics,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}
}

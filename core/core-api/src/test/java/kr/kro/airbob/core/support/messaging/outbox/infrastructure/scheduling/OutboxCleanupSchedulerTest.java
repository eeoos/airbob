package kr.kro.airbob.core.support.messaging.outbox.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.support.messaging.outbox.application.OutboxCleanupResult;
import kr.kro.airbob.core.support.messaging.outbox.application.OutboxCleanupService;
import kr.kro.airbob.core.support.messaging.outbox.monitoring.OutboxCleanupMetrics;

class OutboxCleanupSchedulerTest {

	@Test
	@DisplayName("스케줄러는 한 배치 cleanup 결과를 메트릭에 전달한다")
	void delegatesOneBatchAndRecordsSuccess() {
		OutboxCleanupService service = mock(OutboxCleanupService.class);
		OutboxCleanupMetrics metrics = mock(OutboxCleanupMetrics.class);
		OutboxCleanupResult result = new OutboxCleanupResult(
			100, Instant.parse("2026-08-17T00:00:00Z"));
		given(service.cleanupOneBatch()).willReturn(result);
		OutboxCleanupScheduler scheduler = new OutboxCleanupScheduler(service, metrics);

		scheduler.cleanupExpiredMessages();

		then(service).should().cleanupOneBatch();
		then(metrics).should().recordSuccess(result);
		then(service).shouldHaveNoMoreInteractions();
		then(metrics).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("cleanup 실패는 성공 시각을 갱신하지 않고 실패 메트릭을 남긴 뒤 전파한다")
	void recordsAndPropagatesFailure() {
		OutboxCleanupService service = mock(OutboxCleanupService.class);
		OutboxCleanupMetrics metrics = mock(OutboxCleanupMetrics.class);
		IllegalStateException failure = new IllegalStateException("database unavailable");
		given(service.cleanupOneBatch()).willThrow(failure);
		OutboxCleanupScheduler scheduler = new OutboxCleanupScheduler(service, metrics);

		assertThatThrownBy(scheduler::cleanupExpiredMessages).isSameAs(failure);

		then(service).should().cleanupOneBatch();
		then(metrics).should().recordFailure();
		then(service).shouldHaveNoMoreInteractions();
		then(metrics).shouldHaveNoMoreInteractions();
	}
}

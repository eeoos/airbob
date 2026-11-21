package kr.kro.airbob.messaging.outbox.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.messaging.outbox.application.OutboxCleanupResult;
import kr.kro.airbob.messaging.outbox.application.OutboxCleanupService;
import kr.kro.airbob.messaging.outbox.monitoring.OutboxCleanupMetrics;

class OutboxCleanupSchedulerTest {

	@Test
	@DisplayName("스케줄러는 한 배치 cleanup 결과를 메트릭에 전달한다")
	void delegatesOneBatchAndRecordsSuccess() {
		OutboxCleanupService service = mock(OutboxCleanupService.class);
		OutboxCleanupMetrics metrics = mock(OutboxCleanupMetrics.class);
		OutboxCleanupResult result = new OutboxCleanupResult(
			100, Instant.parse("2026-08-17T00:00:00Z"));
		when(service.cleanupOneBatch()).thenReturn(result);
		OutboxCleanupScheduler scheduler = new OutboxCleanupScheduler(service, metrics);

		scheduler.cleanupExpiredMessages();

		verify(service).cleanupOneBatch();
		verify(metrics).recordSuccess(result);
		verifyNoMoreInteractions(service, metrics);
	}

	@Test
	@DisplayName("cleanup 실패는 성공 시각을 갱신하지 않고 실패 메트릭을 남긴 뒤 전파한다")
	void recordsAndPropagatesFailure() {
		OutboxCleanupService service = mock(OutboxCleanupService.class);
		OutboxCleanupMetrics metrics = mock(OutboxCleanupMetrics.class);
		IllegalStateException failure = new IllegalStateException("database unavailable");
		when(service.cleanupOneBatch()).thenThrow(failure);
		OutboxCleanupScheduler scheduler = new OutboxCleanupScheduler(service, metrics);

		assertThatThrownBy(scheduler::cleanupExpiredMessages).isSameAs(failure);

		verify(service).cleanupOneBatch();
		verify(metrics).recordFailure();
		verifyNoMoreInteractions(service, metrics);
	}
}

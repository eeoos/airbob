package kr.kro.airbob.messaging.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OutboxCleanupServiceTest {

	@Test
	@DisplayName("한 번 실행할 때 보관 기한 이전의 행을 정확히 한 배치만 삭제한다")
	void deletesExactlyOneBatchPerInvocation() {
		Instant startedAt = Instant.parse("2026-08-17T00:00:00Z");
		Instant completedAt = startedAt.plusSeconds(2);
		OutboxCleanupBatchDeleter batchDeleter = mock(OutboxCleanupBatchDeleter.class);
		Clock clock = mock(Clock.class);
		when(clock.instant()).thenReturn(startedAt, completedAt);
		when(batchDeleter.deleteOneBatch(startedAt.minus(Duration.ofDays(30)), 500)).thenReturn(500);
		OutboxCleanupService service = new OutboxCleanupService(
			batchDeleter, Duration.ofDays(30), 500, clock);

		OutboxCleanupResult result = service.cleanupOneBatch();

		assertThat(result.deletedCount()).isEqualTo(500);
		assertThat(result.observedAt()).isEqualTo(completedAt);
		verify(batchDeleter).deleteOneBatch(startedAt.minus(Duration.ofDays(30)), 500);
		verifyNoMoreInteractions(batchDeleter);
	}

	@Test
	@DisplayName("삭제 트랜잭션에는 한 배치 DELETE만 포함한다")
	void deletionBoundaryIsTransactional() throws NoSuchMethodException {
		Transactional transactional = OutboxCleanupBatchDeleter.class
			.getMethod("deleteOneBatch", Instant.class, int.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}
}

package kr.kro.airbob.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class OutboxCleanupServiceTest {

	@Test
	@DisplayName("한 번 실행할 때 보관 기한 이전의 행을 정확히 한 배치만 삭제하고 backlog를 관측한다")
	void deletesExactlyOneBatchPerInvocation() {
		Instant now = Instant.parse("2026-08-17T00:00:00Z");
		OutboxCleanupBatchDeleter batchDeleter = mock(OutboxCleanupBatchDeleter.class);
		OutboxCleanupRepository repository = mock(OutboxCleanupRepository.class);
		OutboxCleanupProperties properties = new OutboxCleanupProperties(
			Duration.ofDays(30), Duration.ofHours(1), 500);
		OutboxBacklogSnapshot snapshot = new OutboxBacklogSnapshot(
			42, Optional.of(now.minus(Duration.ofDays(2))));
		when(batchDeleter.deleteOneBatch(now.minus(Duration.ofDays(30)), 500)).thenReturn(500);
		when(repository.readBacklogSnapshot()).thenReturn(snapshot);
		OutboxCleanupService service = new OutboxCleanupService(
			batchDeleter, repository, properties, Clock.fixed(now, ZoneOffset.UTC));

		OutboxCleanupResult result = service.cleanupOneBatch();

		assertThat(result.deletedCount()).isEqualTo(500);
		assertThat(result.backlog()).isEqualTo(snapshot);
		assertThat(result.observedAt()).isEqualTo(now);
		InOrder order = inOrder(batchDeleter, repository);
		order.verify(batchDeleter).deleteOneBatch(now.minus(Duration.ofDays(30)), 500);
		order.verify(repository).readBacklogSnapshot();
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

package kr.kro.airbob.domain.reservation.inventory;

import static kr.kro.airbob.config.SchedulingConfig.ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("Accommodation inventory retention scheduler")
class AccommodationInventoryRetentionSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private AccommodationInventoryRetentionService retentionService;

	@Test
	@DisplayName("deletes bounded FREE-only batches using a stable cutoff for one run")
	void deletesBoundedBatches() {
		LocalDate cutoff = LocalDate.of(2026, 7, 26);
		given(retentionService.deleteNextPastFreeBatch(cutoff, 1000))
			.willReturn(1000, 1000, 37);
		AccommodationInventoryRetentionScheduler scheduler = scheduler(10);

		scheduler.deletePastFreeInventory();

		then(retentionService).should(org.mockito.Mockito.times(3))
			.deleteNextPastFreeBatch(cutoff, 1000);
	}

	@Test
	@DisplayName("never exceeds the configured transaction batch count")
	void stopsAtMaxBatchCount() {
		LocalDate cutoff = LocalDate.of(2026, 7, 26);
		given(retentionService.deleteNextPastFreeBatch(cutoff, 1000)).willReturn(1000);
		AccommodationInventoryRetentionScheduler scheduler = scheduler(2);

		scheduler.deletePastFreeInventory();

		then(retentionService).should(org.mockito.Mockito.times(2))
			.deleteNextPastFreeBatch(cutoff, 1000);
	}

	@Test
	@DisplayName("retention shares only the dedicated inventory maintenance scheduler")
	void usesDedicatedInventoryScheduler() throws NoSuchMethodException {
		Method method = AccommodationInventoryRetentionScheduler.class
			.getMethod("deletePastFreeInventory");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.scheduler())
			.isEqualTo(ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER);
	}

	private AccommodationInventoryRetentionScheduler scheduler(int maxBatches) {
		return new AccommodationInventoryRetentionScheduler(
			retentionService,
			Clock.fixed(NOW, ZoneOffset.UTC),
			30,
			1000,
			maxBatches
		);
	}
}

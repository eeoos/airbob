package kr.kro.airbob.domain.reservation.inventory;

import static kr.kro.airbob.config.SchedulingConfig.ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(
	name = "reservation.inventory.seed.enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class AccommodationInventorySeedScheduler {

	private final AccommodationInventorySeedService seedService;
	private final int batchSize;
	private final int maxBatchesPerRun;
	private long nextAccommodationId;

	public AccommodationInventorySeedScheduler(
		AccommodationInventorySeedService seedService,
		@Value("${reservation.inventory.seed.batch-size:1000}") int batchSize,
		@Value("${reservation.inventory.seed.max-batches-per-run:10}") int maxBatchesPerRun
	) {
		if (batchSize < 1 || batchSize > AccommodationInventoryDayRepository.MAX_SEED_TARGETS
			|| maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("inventory seed batch size must be 1..1000 and count positive");
		}
		this.seedService = seedService;
		this.batchSize = batchSize;
		this.maxBatchesPerRun = maxBatchesPerRun;
	}

	@Scheduled(
		fixedDelayString = "${reservation.inventory.seed.interval:5m}",
		scheduler = ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER
	)
	public void seedPublishedInventory() {
		long startedAt = System.nanoTime();
		long cursor = nextAccommodationId;
		long totalProcessed = 0;
		long expectedDays = 0;
		long missingDaysSubmitted = 0;
		long insertStatements = 0;
		for (int batchIndex = 0; batchIndex < maxBatchesPerRun; batchIndex++) {
			AccommodationInventorySeedService.SeedBatch batch =
				seedService.seedNextPublishedBatch(cursor, batchSize);
			totalProcessed += batch.processed();
			expectedDays += batch.expectedDays();
			missingDaysSubmitted += batch.missingDaysSubmitted();
			insertStatements += batch.insertStatements();
			if (batch.processed() == 0) {
				cursor = 0L;
				break;
			}
			if (batch.lastAccommodationId() <= cursor) {
				throw new IllegalStateException("inventory rolling seed cursor did not advance");
			}
			cursor = batch.lastAccommodationId();
			nextAccommodationId = cursor;
			if (batch.processed() < batchSize) {
				cursor = 0L;
				break;
			}
		}
		nextAccommodationId = cursor;
		if (totalProcessed > 0) {
			log.info("Inventory rolling seed complete: accommodations={} expectedDays={} "
					+ "missingDaysSubmitted={} insertStatements={} elapsedMs={} nextCursor={} cycleComplete={}",
				totalProcessed, expectedDays, missingDaysSubmitted, insertStatements,
				(System.nanoTime() - startedAt) / 1_000_000, cursor, cursor == 0);
		}
	}
}

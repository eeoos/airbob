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
		@Value("${reservation.inventory.seed.batch-size:100}") int batchSize,
		@Value("${reservation.inventory.seed.max-batches-per-run:10}") int maxBatchesPerRun
	) {
		if (batchSize < 1 || maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("inventory seed batch limits must be positive");
		}
		this.seedService = seedService;
		this.batchSize = batchSize;
		this.maxBatchesPerRun = maxBatchesPerRun;
	}

	@Scheduled(
		fixedDelayString = "${reservation.inventory.seed.interval:1h}",
		scheduler = ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER
	)
	public void seedPublishedInventory() {
		long cursor = nextAccommodationId;
		int totalProcessed = 0;
		for (int batchIndex = 0; batchIndex < maxBatchesPerRun; batchIndex++) {
			AccommodationInventorySeedService.SeedBatch batch =
				seedService.seedNextPublishedBatch(cursor, batchSize);
			totalProcessed += batch.processed();
			if (batch.processed() == 0) {
				cursor = 0L;
				break;
			}
			cursor = batch.lastAccommodationId();
			if (batch.processed() < batchSize) {
				cursor = 0L;
				break;
			}
		}
		nextAccommodationId = cursor;
		if (totalProcessed > 0) {
			log.debug("게시 숙소 {}건의 예약 inventory horizon 검증 완료", totalProcessed);
		}
	}
}

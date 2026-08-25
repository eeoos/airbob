package kr.kro.airbob.domain.reservation.inventory;

import static kr.kro.airbob.config.SchedulingConfig.ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(
	name = "reservation.inventory.retention.enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class AccommodationInventoryRetentionScheduler {

	private final AccommodationInventoryRetentionService retentionService;
	private final Clock clock;
	private final int pastFreeDaysToKeep;
	private final int batchSize;
	private final int maxBatchesPerRun;

	public AccommodationInventoryRetentionScheduler(
		AccommodationInventoryRetentionService retentionService,
		Clock clock,
		@Value("${reservation.inventory.retention.past-free-days-to-keep:30}")
		int pastFreeDaysToKeep,
		@Value("${reservation.inventory.retention.batch-size:1000}") int batchSize,
		@Value("${reservation.inventory.retention.max-batches-per-run:10}")
		int maxBatchesPerRun
	) {
		if (pastFreeDaysToKeep < 1) {
			throw new IllegalArgumentException(
				"inventory retention must keep at least one past FREE day");
		}
		if (batchSize < 1 || maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("inventory retention batch limits must be positive");
		}
		this.retentionService = retentionService;
		this.clock = clock;
		this.pastFreeDaysToKeep = pastFreeDaysToKeep;
		this.batchSize = batchSize;
		this.maxBatchesPerRun = maxBatchesPerRun;
	}

	@Scheduled(
		fixedDelayString = "${reservation.inventory.retention.interval:1h}",
		scheduler = ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER
	)
	public void deletePastFreeInventory() {
		LocalDate cutoffExclusive = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
			.minusDays(pastFreeDaysToKeep);
		int totalDeleted = 0;
		for (int batchIndex = 0; batchIndex < maxBatchesPerRun; batchIndex++) {
			int deleted = retentionService.deleteNextPastFreeBatch(
				cutoffExclusive, batchSize);
			totalDeleted += deleted;
			if (deleted < batchSize) {
				break;
			}
		}
		if (totalDeleted > 0) {
			log.info("과거 FREE 예약 inventory {}건 정리 완료", totalDeleted);
		}
	}
}

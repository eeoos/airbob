package kr.kro.airbob.domain.reservation.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 운영 트래픽을 받기 전에 모든 게시 숙소가 현재 예약 horizon을 정확히 갖게 한다.
 *
 * <p>여러 인스턴스가 동시에 시작해도 seed는 누락 날짜만 PK upsert하고 전체 coverage를
 * 다시 검증하므로 별도 분산 lease 없이 안전하게 수렴한다.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
	name = "reservation.inventory.startup.enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class AccommodationInventoryStartupBootstrap implements ApplicationRunner {

	private final AccommodationInventorySeedService seedService;
	private final AccommodationInventoryReadiness readiness;
	private final int batchSize;

	public AccommodationInventoryStartupBootstrap(
		AccommodationInventorySeedService seedService,
		AccommodationInventoryReadiness readiness,
		@Value("${reservation.inventory.startup.batch-size:1000}") int batchSize
	) {
		if (batchSize < 1 || batchSize > AccommodationInventoryDayRepository.MAX_SEED_TARGETS) {
			throw new IllegalArgumentException("inventory startup batch size must be between 1 and 1000");
		}
		this.seedService = seedService;
		this.readiness = readiness;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		readiness.markBootstrapping();
		long startedAt = System.nanoTime();
		long lastProgressAt = startedAt;
		long cursor = 0L;
		long totalSeeded = 0;
		long expectedDays = 0;
		long missingDaysSubmitted = 0;
		long insertStatements = 0;
		while (true) {
			AccommodationInventorySeedService.SeedBatch batch =
				seedService.seedNextPublishedBatch(cursor, batchSize);
			if (batch.processed() == 0) {
				break;
			}
			if (batch.lastAccommodationId() <= cursor) {
				throw new IllegalStateException(
					"inventory startup seed cursor did not advance");
			}
			totalSeeded += batch.processed();
			expectedDays += batch.expectedDays();
			missingDaysSubmitted += batch.missingDaysSubmitted();
			insertStatements += batch.insertStatements();
			cursor = batch.lastAccommodationId();
			long now = System.nanoTime();
			if (now - lastProgressAt >= 10_000_000_000L) {
				log.info("Inventory startup progress: accommodations={} expectedDays={} "
						+ "missingDaysSubmitted={} insertStatements={} elapsedMs={} cursor={}",
					totalSeeded, expectedDays, missingDaysSubmitted, insertStatements,
					(now - startedAt) / 1_000_000, cursor);
				lastProgressAt = now;
			}
			if (batch.processed() < batchSize) {
				break;
			}
		}
		readiness.markReady();
		log.info("예약 inventory startup bootstrap 완료: accommodations={} expectedDays={} "
				+ "missingDaysSubmitted={} insertStatements={} elapsedMs={}",
			totalSeeded, expectedDays, missingDaysSubmitted, insertStatements,
			(System.nanoTime() - startedAt) / 1_000_000);
	}
}

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
		@Value("${reservation.inventory.startup.batch-size:200}") int batchSize
	) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("inventory startup batch size must be positive");
		}
		this.seedService = seedService;
		this.readiness = readiness;
		this.batchSize = batchSize;
	}

	@Override
	public void run(ApplicationArguments args) {
		readiness.markBootstrapping();
		long cursor = 0L;
		int totalSeeded = 0;
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
			cursor = batch.lastAccommodationId();
			if (batch.processed() < batchSize) {
				break;
			}
		}
		readiness.markReady();
		log.info("예약 inventory startup bootstrap 완료: 게시 숙소 {}건 검증", totalSeeded);
	}
}

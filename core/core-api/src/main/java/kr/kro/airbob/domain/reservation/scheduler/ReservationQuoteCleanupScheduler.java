package kr.kro.airbob.domain.reservation.scheduler;

import static kr.kro.airbob.config.SchedulingConfig.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.service.ReservationQuoteCleanupService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ReservationQuoteCleanupScheduler {

	private final ReservationQuoteCleanupService cleanupService;
	private final int maxBatchesPerRun;

	public ReservationQuoteCleanupScheduler(
		ReservationQuoteCleanupService cleanupService,
		@Value("${reservation.quote.max-cleanup-batches-per-run:10}") int maxBatchesPerRun
	) {
		if (maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("quote max cleanup batches must be positive");
		}
		this.cleanupService = cleanupService;
		this.maxBatchesPerRun = maxBatchesPerRun;
	}

	@Scheduled(
		fixedDelayString = "${reservation.quote.cleanup-interval:10m}",
		scheduler = RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER
	)
	public void cleanupExpiredQuotes() {
		int totalDeleted = 0;
		for (int batch = 0; batch < maxBatchesPerRun; batch++) {
			int deleted = cleanupService.cleanupOneBatch();
			totalDeleted += deleted;
			if (deleted == 0) {
				break;
			}
		}
		if (totalDeleted > 0) {
			log.info("보존 기간이 지난 예약 견적 {}건 정리 완료", totalDeleted);
		}
	}
}

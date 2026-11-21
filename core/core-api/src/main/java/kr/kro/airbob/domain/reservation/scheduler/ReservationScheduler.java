package kr.kro.airbob.domain.reservation.scheduler;

import static kr.kro.airbob.config.SchedulingConfig.RESERVATION_CLEANUP_TASK_SCHEDULER;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import lombok.extern.slf4j.Slf4j;

// TODO: Spring Batch 적용 필요
@Slf4j
@Component
public class ReservationScheduler {

	private final ExpiredReservationCleanupService cleanupService;
	private final int maxBatchesPerRun;

	public ReservationScheduler(
		ExpiredReservationCleanupService cleanupService,
		@Value("${reservation.expiration.max-batches-per-run:10}") int maxBatchesPerRun
	) {
		if (maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("max batches per cleanup run must be positive");
		}
		this.cleanupService = cleanupService;
		this.maxBatchesPerRun = maxBatchesPerRun;
	}

	@Scheduled(
		fixedDelayString = "${reservation.expiration.cleanup-interval:30s}",
		scheduler = RESERVATION_CLEANUP_TASK_SCHEDULER
	)
	public void cleanupExpiredPendingReservation() {
		log.info("만료된 결제 대기 예약 정리 작업 시작");

		int totalCleaned = 0;
		for (int batch = 0; batch < maxBatchesPerRun; batch++) {
			int cleaned = cleanupService.cleanupExpiredPendingReservations();
			totalCleaned += cleaned;
			if (cleaned == 0) {
				break;
			}
		}
		if (totalCleaned == 0) {
			log.info("정리할 만료된 예약 없습니다.");
			return;
		}
		log.info("{}건의 만료된 예약 정리 완료", totalCleaned);
	}

}

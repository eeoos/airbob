package kr.kro.airbob.domain.reservation.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// TODO: Spring Batch 적용 필요
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

	private final ExpiredReservationCleanupService cleanupService;

	@Scheduled(fixedRate = 300000)
	public void cleanupExpiredPendingReservation() {
		log.info("만료된 결제 대기 예약 정리 작업 시작");

		int cleaned = cleanupService.cleanupExpiredPendingReservations();
		if (cleaned == 0) {
			log.info("정리할 만료된 예약 없습니다.");
			return;
		}
		log.info("{}건의 만료된 예약 정리 완료", cleaned);
	}

}

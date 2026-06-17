package kr.kro.airbob.domain.reservation.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.service.ReservationHoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// TODO: Spring Batch 적용 필요
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

	private final ReservationHoldService holdService;
	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;

	@Scheduled(fixedRate = 300000)
	@Transactional
	public void cleanupExpiredPendingReservation() {
		log.info("만료된 결제 대기 예약 정리 작업 시작");

		List<Reservation> expiredList = reservationRepository.findAllByStatusAndExpiresAtBefore(
			ReservationStatus.PAYMENT_PENDING,
			LocalDateTime.now()
		);

		if (expiredList.isEmpty()) {
			log.info("정리할 만료된 예약 없습니다.");
			return;
		}

		expiredList.forEach(reservation -> {
			log.warn("예약 ID {}가 결제 시간 초과로 만료 처리됩니다.", reservation.getId());
			reservation.expire();

			historyRepository.save(
				ReservationHistory.ofSystem(reservation, ChangeType.STATUS_CHANGE, "결제 시간 초과", "BATCH"));

			holdService.removeHold(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			);
		});
		log.info("{}건의 만료된 예약 정리 완료", expiredList.size());
	}

}


package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryBatchWriter;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExpiredReservationCleanupService {

	private static final String REASON = "결제 시간 초과";
	private static final String SOURCE_SYSTEM = "BATCH";

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryBatchWriter historyBatchWriter;
	private final ReservationHoldService holdService;
	private final Clock clock;

	@Transactional
	public int cleanupExpiredPendingReservations() {
		Instant cutoff = clock.instant();
		List<Reservation> expired = reservationRepository.findAllByStatusAndExpiresAtLessThanEqual(
			ReservationStatus.PAYMENT_PENDING,
			cutoff
		);
		if (expired.isEmpty()) {
			return 0;
		}

		List<ReservationHistory> histories = expired.stream()
			.map(reservation -> {
				reservation.expire();
				return ReservationHistory.ofSystem(
					reservation,
					ChangeType.STATUS_CHANGE,
					REASON,
					SOURCE_SYSTEM
				);
			})
			.toList();

		historyBatchWriter.writeAll(histories, cutoff);
		expired.forEach(reservation -> holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate()
		));
		return expired.size();
	}
}

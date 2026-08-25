package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryBatchWriter;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;

@Service
public class ExpiredReservationCleanupService {

	private static final String REASON = "결제 시간 초과";
	private static final String SOURCE_SYSTEM = "BATCH";

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryBatchWriter historyBatchWriter;
	private final CouponUsageService couponUsageService;
	private final ReservationInventoryService inventoryService;
	private final Clock clock;
	private final int cleanupBatchSize;

	public ExpiredReservationCleanupService(
		ReservationRepository reservationRepository,
		ReservationHistoryBatchWriter historyBatchWriter,
		CouponUsageService couponUsageService,
		ReservationInventoryService inventoryService,
		Clock clock,
		@Value("${reservation.expiration.cleanup-batch-size:100}") int cleanupBatchSize
	) {
		if (cleanupBatchSize < 1) {
			throw new IllegalArgumentException("cleanup batch size must be positive");
		}
		this.reservationRepository = reservationRepository;
		this.historyBatchWriter = historyBatchWriter;
		this.couponUsageService = couponUsageService;
		this.inventoryService = inventoryService;
		this.clock = clock;
		this.cleanupBatchSize = cleanupBatchSize;
	}

	@Transactional(
		isolation = Isolation.READ_COMMITTED,
		timeoutString = "${reservation.expiration.transaction-timeout-seconds:10}"
	)
	public int cleanupExpiredPendingReservations() {
		Instant cutoff = clock.instant();
		List<Reservation> expired = reservationRepository.findExpiredPendingBatchForCleanup(
			cutoff,
			cleanupBatchSize
		);
		if (expired.isEmpty()) {
			return 0;
		}

		List<ReservationHistory> histories = expired.stream()
			.map(reservation -> {
				inventoryService.releaseHeldIfOwned(
					reservation.getAccommodation().getId(),
					reservation.getCheckInDate(),
					reservation.getCheckOutDate(),
					reservation.getId()
				);
				reservation.expire();
				return ReservationHistory.ofSystem(
					reservation,
					ChangeType.STATUS_CHANGE,
					REASON,
					SOURCE_SYSTEM
				);
			})
			.toList();

		couponUsageService.restoreAll(expired.stream().map(Reservation::getId).toList());
		historyBatchWriter.writeAll(histories, cutoff);
		return expired.size();
	}
}

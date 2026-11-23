package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.core.domain.coupon.CouponUsageManager;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryManager;

@Service
public class ExpiredReservationCleanupService {

	private static final String REASON = "결제 시간 초과";
	private static final String SOURCE_SYSTEM = "BATCH";

	private final ReservationReader reservationReader;
	private final ReservationHistoryWriter reservationHistoryWriter;
	private final CouponUsageManager couponUsageManager;
	private final ReservationInventoryManager inventoryManager;
	private final Clock clock;
	private final int cleanupBatchSize;

	public ExpiredReservationCleanupService(
		ReservationReader reservationReader,
		ReservationHistoryWriter reservationHistoryWriter,
		CouponUsageManager couponUsageManager,
		ReservationInventoryManager inventoryManager,
		Clock clock,
		@Value("${reservation.expiration.cleanup-batch-size:100}") int cleanupBatchSize
	) {
		if (cleanupBatchSize < 1) {
			throw new IllegalArgumentException("cleanup batch size must be positive");
		}
		this.reservationReader = reservationReader;
		this.reservationHistoryWriter = reservationHistoryWriter;
		this.couponUsageManager = couponUsageManager;
		this.inventoryManager = inventoryManager;
		this.clock = clock;
		this.cleanupBatchSize = cleanupBatchSize;
	}

	@Transactional(
		isolation = Isolation.READ_COMMITTED,
		timeoutString = "${reservation.expiration.transaction-timeout-seconds:10}"
	)
	public int cleanupExpiredPendingReservations() {
		Instant cutoff = clock.instant();
		List<Reservation> expired = reservationReader.readExpiredPendingBatchForCleanup(
			cutoff,
			cleanupBatchSize
		);
		if (expired.isEmpty()) {
			return 0;
		}

		List<ReservationHistory> histories = expired.stream()
			.map(reservation -> {
				inventoryManager.releaseHeldIfOwned(
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

		couponUsageManager.restoreAll(expired.stream().map(Reservation::getId).toList());
		reservationHistoryWriter.recordAll(histories, cutoff);
		return expired.size();
	}
}

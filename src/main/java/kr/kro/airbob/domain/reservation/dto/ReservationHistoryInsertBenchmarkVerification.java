package kr.kro.airbob.domain.reservation.dto;

public record ReservationHistoryInsertBenchmarkVerification(
	long verifiedRows,
	boolean succeeded,
	boolean targetReservationsExpired,
	boolean targetHistoriesInserted,
	boolean futurePendingPreserved,
	boolean nonPendingExpiredPreserved,
	boolean historySnapshotsPreserved,
	boolean historyAuditContextPreserved
) {
}

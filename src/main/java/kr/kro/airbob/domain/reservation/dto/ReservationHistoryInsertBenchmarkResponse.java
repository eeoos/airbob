package kr.kro.airbob.domain.reservation.dto;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemovalSnapshot;

public record ReservationHistoryInsertBenchmarkResponse(
	Candidate candidate,
	Variant variant,
	int datasetSize,
	long expectedRows,
	long verifiedRows,
	boolean verificationSucceeded,
	boolean targetReservationsExpired,
	boolean targetHistoriesInserted,
	boolean futurePendingPreserved,
	boolean nonPendingExpiredPreserved,
	boolean historySnapshotsPreserved,
	boolean historyAuditContextPreserved,
	boolean holdRemovalsMatched,
	int holdRemovalCalls,
	boolean redisNetworkExcluded,
	BulkWriteBenchmarkResult operation
) {
	public static ReservationHistoryInsertBenchmarkResponse of(
		Variant variant,
		int datasetSize,
		ReservationHistoryInsertBenchmarkVerification verification,
		HoldRemovalSnapshot holdSnapshot,
		BulkOperationSnapshot operation
	) {
		return new ReservationHistoryInsertBenchmarkResponse(
			Candidate.RESERVATION_HISTORY_INSERT,
			variant,
			datasetSize,
			datasetSize,
			verification.verifiedRows(),
			verification.succeeded(),
			verification.targetReservationsExpired(),
			verification.targetHistoriesInserted(),
			verification.futurePendingPreserved(),
			verification.nonPendingExpiredPreserved(),
			verification.historySnapshotsPreserved(),
			verification.historyAuditContextPreserved(),
			verification.holdRemovalsMatched(),
			holdSnapshot.callCount(),
			true,
			BulkWriteBenchmarkResult.from(operation)
		);
	}

	public enum Candidate {
		RESERVATION_HISTORY_INSERT
	}
}

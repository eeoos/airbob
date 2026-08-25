package kr.kro.airbob.domain.reservation.dto;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;

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
	BulkWriteBenchmarkResult operation
) {
	public static ReservationHistoryInsertBenchmarkResponse of(
		Variant variant,
		int datasetSize,
		ReservationHistoryInsertBenchmarkVerification verification,
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
			BulkWriteBenchmarkResult.from(operation)
		);
	}

	public enum Candidate {
		RESERVATION_HISTORY_INSERT
	}
}

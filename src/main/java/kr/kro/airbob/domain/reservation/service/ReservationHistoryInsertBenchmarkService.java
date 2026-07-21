package kr.kro.airbob.domain.reservation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkResponse;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkVerification;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkFixtureService.Fixture;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemovalSnapshot;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class ReservationHistoryInsertBenchmarkService {

	public static final String BEFORE_OPERATION_NAME = "expired-reservation-cleanup-before";

	private final ReservationHistoryInsertBeforeBenchmarkService beforeService;
	private final ReservationHistoryInsertBenchmarkFixtureService fixtureService;
	private final ReservationHistoryInsertBenchmarkHoldService holdService;
	private final BulkOperationMonitor bulkOperationMonitor;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;

	public ReservationHistoryInsertBenchmarkService(
		ReservationHistoryInsertBeforeBenchmarkService beforeService,
		ReservationHistoryInsertBenchmarkFixtureService fixtureService,
		ReservationHistoryInsertBenchmarkHoldService holdService,
		BulkOperationMonitor bulkOperationMonitor,
		BulkWriteBenchmarkDatabaseGuard databaseGuard
	) {
		this.beforeService = beforeService;
		this.fixtureService = fixtureService;
		this.holdService = holdService;
		this.bulkOperationMonitor = bulkOperationMonitor;
		this.databaseGuard = databaseGuard;
	}

	public ReservationHistoryInsertBenchmarkResponse run(ReservationHistoryInsertBenchmarkRequest request) {
		int datasetSize = validateRequest(request);
		databaseGuard.verifyReady();
		Fixture fixture = fixtureService.createFixture(datasetSize);
		Throwable operationFailure = null;

		try {
			Measurement measurement = measureScheduler();
			ReservationHistoryInsertBenchmarkVerification verification = fixtureService.verify(
				fixture,
				measurement.holdSnapshot()
			);
			return ReservationHistoryInsertBenchmarkResponse.of(
				request.variant(),
				datasetSize,
				verification,
				measurement.holdSnapshot(),
				measurement.operation()
			);
		} catch (RuntimeException | Error failure) {
			operationFailure = failure;
			throw failure;
		} finally {
			try {
				fixtureService.cleanup(fixture);
			} catch (RuntimeException | Error cleanupFailure) {
				if (operationFailure != null) {
					operationFailure.addSuppressed(cleanupFailure);
				} else {
					throw cleanupFailure;
				}
			}
		}
	}

	private Measurement measureScheduler() {
		UserInfo previousUser = UserContext.get();
		boolean recording = false;
		try {
			holdService.startRecording();
			recording = true;
			UserContext.clear();
			BulkOperationSnapshot operation = bulkOperationMonitor.monitor(
				BEFORE_OPERATION_NAME,
				beforeService::cleanupExpiredPendingReservations
			);
			HoldRemovalSnapshot holdSnapshot = holdService.finishRecording();
			recording = false;
			return new Measurement(operation, holdSnapshot);
		} finally {
			if (recording) {
				holdService.clearRecording();
			}
			UserContext.clear();
			if (previousUser != null) {
				UserContext.set(previousUser);
			}
		}
	}

	private int validateRequest(ReservationHistoryInsertBenchmarkRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}
		if (request.variant() == null) {
			throw new IllegalArgumentException("variant must not be null");
		}
		if (request.datasetSize() == null
			|| request.datasetSize() < 0
			|| request.datasetSize() > ReservationHistoryInsertBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ ReservationHistoryInsertBenchmarkRequest.MAX_DATASET_SIZE);
		}
		return request.datasetSize();
	}

	private record Measurement(
		BulkOperationSnapshot operation,
		HoldRemovalSnapshot holdSnapshot
	) {
	}
}

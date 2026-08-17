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
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkResponse;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkVerification;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkFixtureService.Fixture;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class ReservationHistoryInsertBenchmarkService {

	public static final String BEFORE_OPERATION_NAME = "expired-reservation-cleanup-before";
	public static final String AFTER_OPERATION_NAME = "expired-reservation-cleanup-after";

	private final ReservationHistoryInsertBeforeBenchmarkService beforeService;
	private final ExpiredReservationCleanupService cleanupService;
	private final ReservationHistoryInsertBenchmarkFixtureService fixtureService;
	private final BulkOperationMonitor bulkOperationMonitor;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;

	public ReservationHistoryInsertBenchmarkService(
		ReservationHistoryInsertBeforeBenchmarkService beforeService,
		ExpiredReservationCleanupService cleanupService,
		ReservationHistoryInsertBenchmarkFixtureService fixtureService,
		BulkOperationMonitor bulkOperationMonitor,
		BulkWriteBenchmarkDatabaseGuard databaseGuard
	) {
		this.beforeService = beforeService;
		this.cleanupService = cleanupService;
		this.fixtureService = fixtureService;
		this.bulkOperationMonitor = bulkOperationMonitor;
		this.databaseGuard = databaseGuard;
	}

	public ReservationHistoryInsertBenchmarkResponse run(ReservationHistoryInsertBenchmarkRequest request) {
		int datasetSize = validateRequest(request);
		databaseGuard.verifyReady();
		Fixture fixture = fixtureService.createFixture(datasetSize);
		Throwable operationFailure = null;

		try {
			BulkOperationSnapshot operation = measureScheduler(request.variant());
			ReservationHistoryInsertBenchmarkVerification verification = fixtureService.verify(fixture);
			return ReservationHistoryInsertBenchmarkResponse.of(
				request.variant(),
				datasetSize,
				verification,
				operation
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

	private BulkOperationSnapshot measureScheduler(Variant variant) {
		UserInfo previousUser = UserContext.get();
		try {
			UserContext.clear();
			return bulkOperationMonitor.monitor(
				operationName(variant),
				operation(variant)
			);
		} finally {
			UserContext.clear();
			if (previousUser != null) {
				UserContext.set(previousUser);
			}
		}
	}

	private Runnable operation(Variant variant) {
		return switch (variant) {
			case BEFORE -> beforeService::cleanupExpiredPendingReservations;
			case AFTER -> cleanupService::cleanupExpiredPendingReservations;
		};
	}

	private String operationName(Variant variant) {
		return switch (variant) {
			case BEFORE -> BEFORE_OPERATION_NAME;
			case AFTER -> AFTER_OPERATION_NAME;
		};
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

}

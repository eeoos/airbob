package kr.kro.airbob.domain.accommodation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkFixtureService.Fixture;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class AccommodationAmenityDeleteBenchmarkService {

	public static final String FULL_REPLACEMENT_BEFORE_OPERATION_NAME =
		"accommodation-amenity-full-replacement-before";
	public static final String DELETE_ONLY_BEFORE_OPERATION_NAME =
		"accommodation-amenity-delete-only-before";

	private final AccommodationService accommodationService;
	private final AccommodationAmenityDeleteBeforeBenchmarkService beforeService;
	private final AccommodationAmenityDeleteBenchmarkFixtureService fixtureService;
	private final BulkOperationMonitor bulkOperationMonitor;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;

	public AccommodationAmenityDeleteBenchmarkService(
		AccommodationService accommodationService,
		AccommodationAmenityDeleteBeforeBenchmarkService beforeService,
		AccommodationAmenityDeleteBenchmarkFixtureService fixtureService,
		BulkOperationMonitor bulkOperationMonitor,
		BulkWriteBenchmarkDatabaseGuard databaseGuard
	) {
		this.accommodationService = accommodationService;
		this.beforeService = beforeService;
		this.fixtureService = fixtureService;
		this.bulkOperationMonitor = bulkOperationMonitor;
		this.databaseGuard = databaseGuard;
	}

	public AccommodationAmenityDeleteBenchmarkResponse run(
		long ownerId,
		AccommodationAmenityDeleteBenchmarkRequest request
	) {
		int datasetSize = validateRequest(request);
		databaseGuard.verifyReady();
		Fixture fixture = fixtureService.createFixture(ownerId, datasetSize);
		Throwable operationFailure = null;

		try {
			BulkOperationSnapshot snapshot = bulkOperationMonitor.monitor(
				operationName(request.measurement()),
				operation(request.measurement(), fixture, ownerId)
			);
			AccommodationAmenityDeleteBenchmarkVerification verification =
				fixtureService.verify(fixture, request.measurement());
			return AccommodationAmenityDeleteBenchmarkResponse.of(
				request.variant(),
				request.measurement(),
				datasetSize,
				fixture.activeAmenityCodeCount(),
				fixture.workloadClass(),
				fixture.replacementMap(),
				verification,
				BulkWriteBenchmarkResult.from(snapshot)
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

	private int validateRequest(AccommodationAmenityDeleteBenchmarkRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}
		if (request.variant() != Variant.BEFORE) {
			throw new IllegalArgumentException("variant must be BEFORE");
		}
		if (request.measurement() == null) {
			throw new IllegalArgumentException("measurement must not be null");
		}
		if (request.datasetSize() == null
			|| request.datasetSize() < 0
			|| request.datasetSize() > AccommodationAmenityDeleteBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ AccommodationAmenityDeleteBenchmarkRequest.MAX_DATASET_SIZE);
		}
		return request.datasetSize();
	}

	private String operationName(Measurement measurement) {
		return switch (measurement) {
			case FULL_REPLACEMENT -> FULL_REPLACEMENT_BEFORE_OPERATION_NAME;
			case DELETE_ONLY -> DELETE_ONLY_BEFORE_OPERATION_NAME;
		};
	}

	private Runnable operation(Measurement measurement, Fixture fixture, long ownerId) {
		return switch (measurement) {
			case FULL_REPLACEMENT -> () -> accommodationService.updateAccommodation(
				fixture.targetAccommodationId(),
				fixture.replacementRequest(),
				ownerId
			);
			case DELETE_ONLY -> () -> beforeService.deleteByAccommodationId(
				fixture.targetAccommodationId()
			);
		};
	}
}

package kr.kro.airbob.domain.wishlist.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkVerification;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkFixtureService.Fixture;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class WishlistDeleteBenchmarkService {

	static final String BEFORE_OPERATION_NAME = "wishlist-delete-before";
	static final String AFTER_OPERATION_NAME = "wishlist-delete-after";

	private final WishlistDeleteBeforeBenchmarkService beforeService;
	private final WishlistService wishlistService;
	private final WishlistDeleteBenchmarkFixtureService fixtureService;
	private final BulkOperationMonitor bulkOperationMonitor;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;

	public WishlistDeleteBenchmarkService(
		WishlistDeleteBeforeBenchmarkService beforeService,
		WishlistService wishlistService,
		WishlistDeleteBenchmarkFixtureService fixtureService,
		BulkOperationMonitor bulkOperationMonitor,
		BulkWriteBenchmarkDatabaseGuard databaseGuard
	) {
		this.beforeService = beforeService;
		this.wishlistService = wishlistService;
		this.fixtureService = fixtureService;
		this.bulkOperationMonitor = bulkOperationMonitor;
		this.databaseGuard = databaseGuard;
	}

	public WishlistDeleteBenchmarkResponse run(long ownerId, WishlistDeleteBenchmarkRequest request) {
		int datasetSize = validateRequest(request);
		String operationName = operationName(request.variant());
		databaseGuard.verifyReady();
		Fixture fixture = fixtureService.createFixture(ownerId, datasetSize);
		Runnable deleteOperation = deleteOperation(request.variant(), fixture.targetWishlistId(), ownerId);
		Throwable operationFailure = null;

		try {
			BulkOperationSnapshot snapshot = bulkOperationMonitor.monitor(
				operationName,
				deleteOperation
			);
			WishlistDeleteBenchmarkVerification verification = fixtureService.verify(fixture);

			return WishlistDeleteBenchmarkResponse.of(
				request.variant(),
				datasetSize,
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

	private int validateRequest(WishlistDeleteBenchmarkRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}
		if (request.variant() == null) {
			throw new IllegalArgumentException("variant must not be null");
		}
		if (request.datasetSize() == null
			|| request.datasetSize() < 0
			|| request.datasetSize() > WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE);
		}
		return request.datasetSize();
	}

	private String operationName(WishlistDeleteBenchmarkRequest.Variant variant) {
		return switch (variant) {
			case BEFORE -> BEFORE_OPERATION_NAME;
			case AFTER -> AFTER_OPERATION_NAME;
		};
	}

	private Runnable deleteOperation(
		WishlistDeleteBenchmarkRequest.Variant variant,
		long wishlistId,
		long ownerId
	) {
		return switch (variant) {
			case BEFORE -> () -> beforeService.deleteWishlist(wishlistId, ownerId);
			case AFTER -> () -> wishlistService.deleteWishlist(wishlistId, ownerId);
		};
	}
}

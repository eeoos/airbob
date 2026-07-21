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

	private final WishlistService wishlistService;
	private final WishlistDeleteBenchmarkFixtureService fixtureService;
	private final BulkOperationMonitor bulkOperationMonitor;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;

	public WishlistDeleteBenchmarkService(
		WishlistService wishlistService,
		WishlistDeleteBenchmarkFixtureService fixtureService,
		BulkOperationMonitor bulkOperationMonitor,
		BulkWriteBenchmarkDatabaseGuard databaseGuard
	) {
		this.wishlistService = wishlistService;
		this.fixtureService = fixtureService;
		this.bulkOperationMonitor = bulkOperationMonitor;
		this.databaseGuard = databaseGuard;
	}

	public WishlistDeleteBenchmarkResponse runBefore(long ownerId, WishlistDeleteBenchmarkRequest request) {
		int datasetSize = validateBeforeRequest(request);
		databaseGuard.verifyReady();
		Fixture fixture = fixtureService.createFixture(ownerId, datasetSize);
		Throwable operationFailure = null;

		try {
			BulkOperationSnapshot snapshot = bulkOperationMonitor.monitor(
				BEFORE_OPERATION_NAME,
				() -> wishlistService.deleteWishlist(fixture.targetWishlistId(), ownerId)
			);
			WishlistDeleteBenchmarkVerification verification = fixtureService.verify(fixture);

			return WishlistDeleteBenchmarkResponse.before(
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

	private int validateBeforeRequest(WishlistDeleteBenchmarkRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}
		if (request.variant() != WishlistDeleteBenchmarkRequest.Variant.BEFORE) {
			throw new IllegalArgumentException("U2 supports only the BEFORE variant");
		}
		if (request.datasetSize() == null
			|| request.datasetSize() < 0
			|| request.datasetSize() > WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE) {
			throw new IllegalArgumentException("datasetSize must be between 0 and "
				+ WishlistDeleteBenchmarkRequest.MAX_DATASET_SIZE);
		}
		return request.datasetSize();
	}
}

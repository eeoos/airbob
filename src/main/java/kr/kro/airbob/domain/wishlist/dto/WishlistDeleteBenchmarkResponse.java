package kr.kro.airbob.domain.wishlist.dto;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;

public record WishlistDeleteBenchmarkResponse(
	Candidate candidate,
	WishlistDeleteBenchmarkRequest.Variant variant,
	int datasetSize,
	long expectedRows,
	long verifiedRows,
	boolean verificationSucceeded,
	boolean targetWishlistDeleted,
	boolean targetMembershipsDeleted,
	boolean targetDenormalizedStatePreserved,
	boolean controlWishlistPreserved,
	boolean controlMembershipPreserved,
	boolean accommodationsPreserved,
	BulkWriteBenchmarkResult operation
) {
	public static WishlistDeleteBenchmarkResponse of(
		WishlistDeleteBenchmarkRequest.Variant variant,
		int datasetSize,
		WishlistDeleteBenchmarkVerification verification,
		BulkWriteBenchmarkResult operation
	) {
		return new WishlistDeleteBenchmarkResponse(
			Candidate.WISHLIST_DELETE,
			variant,
			datasetSize,
			datasetSize,
			verification.verifiedRows(),
			verification.succeeded(),
			verification.targetWishlistDeleted(),
			verification.targetMembershipsDeleted(),
			verification.targetDenormalizedStatePreserved(),
			verification.controlWishlistPreserved(),
			verification.controlMembershipPreserved(),
			verification.accommodationsPreserved(),
			operation
		);
	}

	public enum Candidate {
		WISHLIST_DELETE
	}
}

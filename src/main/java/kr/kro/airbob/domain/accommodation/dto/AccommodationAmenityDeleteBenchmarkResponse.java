package kr.kro.airbob.domain.accommodation.dto;

import java.util.Map;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkResult;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass;

public record AccommodationAmenityDeleteBenchmarkResponse(
	Candidate candidate,
	Variant variant,
	Measurement measurement,
	WorkloadClass workloadClass,
	int activeAmenityCodeCount,
	int datasetSize,
	long oldTargetRowsExpected,
	long oldTargetRowsDeleted,
	long oldTargetRowsVerified,
	long replacementRowsExpected,
	long replacementRowsVerified,
	Map<String, Integer> replacementMapExpected,
	Map<String, Integer> replacementMapVerified,
	boolean targetParentPreserved,
	boolean historyEffectMatched,
	boolean controlAccommodationPreserved,
	boolean controlAmenitiesPreserved,
	boolean verificationSucceeded,
	BulkWriteBenchmarkResult operation
) {
	public static AccommodationAmenityDeleteBenchmarkResponse of(
		Variant variant,
		Measurement measurement,
		int datasetSize,
		int activeAmenityCodeCount,
		WorkloadClass workloadClass,
		Map<String, Integer> fullReplacementMap,
		AccommodationAmenityDeleteBenchmarkVerification verification,
		BulkWriteBenchmarkResult operation
	) {
		Map<String, Integer> expectedMap = measurement == Measurement.FULL_REPLACEMENT
			? Map.copyOf(fullReplacementMap)
			: Map.of();
		return new AccommodationAmenityDeleteBenchmarkResponse(
			Candidate.ACCOMMODATION_AMENITY_DELETE,
			variant,
			measurement,
			workloadClass,
			activeAmenityCodeCount,
			datasetSize,
			datasetSize,
			verification.oldTargetRowsDeleted(),
			verification.oldTargetRowsVerified(),
			expectedMap.size(),
			verification.replacementRowsVerified(),
			expectedMap,
			verification.replacementMapVerified(),
			verification.targetParentPreserved(),
			verification.historyEffectMatched(),
			verification.controlAccommodationPreserved(),
			verification.controlAmenitiesPreserved(),
			verification.succeeded(),
			operation
		);
	}

	public enum Candidate {
		ACCOMMODATION_AMENITY_DELETE
	}
}

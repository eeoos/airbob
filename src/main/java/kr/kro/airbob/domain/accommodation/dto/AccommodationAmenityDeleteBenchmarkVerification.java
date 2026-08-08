package kr.kro.airbob.domain.accommodation.dto;

import java.util.Map;

public record AccommodationAmenityDeleteBenchmarkVerification(
	long oldTargetRowsDeleted,
	long oldTargetRowsVerified,
	long replacementRowsVerified,
	Map<String, Integer> replacementMapVerified,
	boolean targetParentPreserved,
	boolean historyEffectMatched,
	boolean controlAccommodationPreserved,
	boolean controlAmenitiesPreserved,
	boolean succeeded
) {
	public AccommodationAmenityDeleteBenchmarkVerification {
		replacementMapVerified = Map.copyOf(replacementMapVerified);
	}

	public enum WorkloadClass {
		REALISTIC,
		STRESS
	}
}

package kr.kro.airbob.domain.accommodation.dto;

import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyResponse {

	@Builder
	public record PolicyInfo(
		Integer maxOccupancy,
		Integer infantOccupancy,
		Integer petOccupancy
	) {
		public static PolicyResponse.PolicyInfo from(OccupancyPolicy policy) {
			if(policy == null) return PolicyResponse.PolicyInfo.builder().build();
			return PolicyResponse.PolicyInfo.builder()
				.maxOccupancy(policy.getMaxOccupancy())
				.infantOccupancy(policy.getInfantOccupancy())
				.petOccupancy(policy.getPetOccupancy())
				.build();
		}
	}
}

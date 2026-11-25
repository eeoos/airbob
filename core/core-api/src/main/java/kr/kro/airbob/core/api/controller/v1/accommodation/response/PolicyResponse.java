package kr.kro.airbob.core.api.controller.v1.accommodation.response;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.core.domain.accommodation.OccupancyPolicy;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyResponse {

	@Builder
	@Schema(name = "PolicyResponsePolicyInfo")
	public record PolicyInfo(
		@Schema(nullable = true) Integer maxOccupancy,
		@Schema(nullable = true) Integer infantOccupancy,
		@Schema(nullable = true) Integer petOccupancy) {
		public static PolicyResponse.PolicyInfo from(OccupancyPolicy policy) {
			if (policy == null)
				return PolicyResponse.PolicyInfo.builder().build();
			return PolicyResponse.PolicyInfo.builder()
				.maxOccupancy(policy.getMaxOccupancy())
				.infantOccupancy(policy.getInfantOccupancy())
				.petOccupancy(policy.getPetOccupancy())
				.build();
		}
	}
}

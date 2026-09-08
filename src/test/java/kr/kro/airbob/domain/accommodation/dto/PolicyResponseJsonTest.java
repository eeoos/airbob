package kr.kro.airbob.domain.accommodation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;

@JsonTest
@DisplayName("호스트 편집 정책 JSON 계약")
class PolicyResponseJsonTest {
	@Autowired private ObjectMapper objectMapper;

	@Test
	@DisplayName("정책 없음·부분 누락·완전한 정책의 필드와 null을 보존한다")
	void serializesHostPolicyVariants() throws Exception {
		Map<String, PolicyResponse.PolicyInfo> policies = Map.of(
			"absent", hostPolicy(null),
			"partial", hostPolicy(OccupancyPolicy.builder().maxOccupancy(4).petOccupancy(2).build()),
			"complete", hostPolicy(OccupancyPolicy.builder()
				.maxOccupancy(4).infantOccupancy(2).petOccupancy(3).build()));

		try (var fixture = new ClassPathResource("contracts/host-policy-contracts.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(policies)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertThat(hostPolicy(OccupancyPolicy.builder().build())).isEqualTo(hostPolicy(null));
	}

	private PolicyResponse.PolicyInfo hostPolicy(OccupancyPolicy policy) {
		return AccommodationResponse.HostDetail.from(
			Accommodation.builder().id(31L).occupancyPolicy(policy).build(), List.of(), List.of()).policy();
	}
}

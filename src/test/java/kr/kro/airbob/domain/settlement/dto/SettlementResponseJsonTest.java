package kr.kro.airbob.domain.settlement.dto;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.settlement.entity.Settlement;

@JsonTest
@DisplayName("정산 응답 JSON 테스트")
class SettlementResponseJsonTest {

	private static final Instant SETTLED_AT = Instant.parse("2026-08-12T05:30:00Z");

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("절대 지급 시각을 모든 응답에 UTC Instant 형식으로 반환한다")
	void serializesSettledAtAsUtcInstant() {
		Settlement settlement = Settlement.createPending(
			1L,
			LocalDate.of(2026, 7, 1),
			100_000L,
			0L,
			new BigDecimal("0.03")
		);
		settlement.markPaid(SETTLED_AT);

		SettlementResponse.HostSettlement host = SettlementResponse.HostSettlement.from(settlement);
		SettlementResponse.SettlementDetail detail = SettlementResponse.SettlementDetail.of(settlement, List.of());
		SettlementResponse.AdminSettlement admin = SettlementResponse.AdminSettlement.from(settlement);

		Instant hostSettledAt = host.settledAt();
		Instant detailSettledAt = detail.settledAt();
		Instant adminSettledAt = admin.settledAt();
		assertThat(hostSettledAt).isEqualTo(SETTLED_AT);
		assertThat(detailSettledAt).isEqualTo(SETTLED_AT);
		assertThat(adminSettledAt).isEqualTo(SETTLED_AT);

		JsonNode json = objectMapper.valueToTree(admin);
		assertThat(json.path("settled_at").asText()).isEqualTo("2026-08-12T05:30:00Z");
	}
}

package kr.kro.airbob.core.domain.settlement;

import java.util.List;

/** 호스트 본인 정산의 요약과 숙소별 내역(숙소 ID 오름차순). */
public record HostSettlementDetail(
	SettlementSummary settlement,
	List<SettlementLine> lines
) {

	public HostSettlementDetail {
		lines = List.copyOf(lines);
	}
}

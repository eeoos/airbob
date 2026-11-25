package kr.kro.airbob.core.domain.settlement;

/** 호스트의 저장된 정산 전체를 상태별로 합친 지급액과 정산 건수. 정산이 없으면 모두 0이다. */
public record HostSettlementTotals(
	long pendingPayoutTotal,
	long pendingCount,
	long paidPayoutTotal,
	long paidCount) {
}

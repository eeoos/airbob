package kr.kro.airbob.core.domain.settlement;

// 정산 상태. PENDING(정산 예정) -> PAID(지급 완료). 추후 FAILED/CANCELED 등 확장 가능.
public enum SettlementStatus {
	PENDING,
	PAID
}

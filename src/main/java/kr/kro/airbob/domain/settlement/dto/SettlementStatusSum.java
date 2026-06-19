package kr.kro.airbob.domain.settlement.dto;

// 호스트 요약: status별 payout 합/건수 집계 프로젝션. 컬럼 별칭을 getter 프로퍼티명과 일치시킨다.
public interface SettlementStatusSum {
	String getStatus();
	Long getPayoutSum();
	Long getCnt();
}

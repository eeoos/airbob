package kr.kro.airbob.domain.settlement.dto;

// 정산 상세: 숙소별 매출 기여 집계(원장 직접) 프로젝션. 컬럼 별칭을 getter 프로퍼티명과 일치시킨다.
public interface SettlementLineItem {
	Long getAccommodationId();
	String getAccommodationName();
	Long getGrossAmount();
	Long getRefundAmount();
	Long getNetAmount();
}

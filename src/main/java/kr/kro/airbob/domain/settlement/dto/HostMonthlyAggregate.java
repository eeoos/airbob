package kr.kro.airbob.domain.settlement.dto;

// 월별 호스트 매출 집계(원장 직접) 결과 매핑용 프로젝션. 컬럼 별칭을 getter 프로퍼티명과 일치시킨다.
public interface HostMonthlyAggregate {
	Long getHostId();
	Long getGrossAmount();
	Long getRefundAmount();
	Long getNetAmount();
}

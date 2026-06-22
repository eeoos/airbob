package kr.kro.airbob.domain.statistics.dto;

import java.time.LocalDate;

// 네이티브 집계 쿼리(stats 조회 / raw 집계 공통) 결과 매핑용 프로젝션.
// 컬럼 별칭을 getter 프로퍼티명(camelCase)과 일치시켜 사용한다.
public interface DailyRevenueRow {
	LocalDate getStatDate();
	Long getGrossAmount();
	Long getRefundAmount();
	Long getNetAmount();
	Long getPaymentCount();
	Long getRefundCount();
}

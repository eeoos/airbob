package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;

// review 테이블 직접 집계(naive, 성능 비교용 "before") 결과 매핑 프로젝션.
public interface ReviewSummaryRow {
	Long getTotalCount();
	BigDecimal getAverageRating();
}

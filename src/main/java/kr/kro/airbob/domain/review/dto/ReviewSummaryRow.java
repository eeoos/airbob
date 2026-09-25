package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;

/** 숙소별 게시 리뷰 원본 집계 결과. */
public interface ReviewSummaryRow {
	Long getAccommodationId();
	Long getTotalCount();
	BigDecimal getAverageRating();
}

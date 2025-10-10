package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import lombok.Builder;

public class ReviewResponse {

	private ReviewResponse() {
	}

	public record CreateResponse(
		long id
	) {
	}

	public record UpdateResponse(
		long id
	) {
	}

	public record ReviewInfos(
		List<ReviewInfo> reviews,
		CursorResponse.PageInfo pageInfo

	) {
	}

	public record ReviewInfo(
		long id,
		int rating,
		String content,
		LocalDateTime reviewedAt,
		MemberResponse.ReviewerInfo reviewer
	) {
	}

	@Builder
	public record ReviewSummary(
		int totalCount,
		BigDecimal averageRating
	) {
		public static ReviewSummary of(AccommodationReviewSummary summary) {
			if (summary == null) {
				return new ReviewSummary(0, BigDecimal.ZERO);
			}
			return new ReviewSummary(
				summary.getTotalReviewCount().intValue(),
				summary.getAverageRating()
			);
		}
	}
}

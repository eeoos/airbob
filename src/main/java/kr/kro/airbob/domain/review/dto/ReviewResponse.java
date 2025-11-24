package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewResponse {

	public record Create(
		long id
	) {
	}

	public record Update(
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
		MemberResponse.MemberInfo reviewer,
		List<ImageResponse.ImageInfo> images
	) {

		@QueryProjection
		public ReviewInfo(long id,
			int rating,
			String content,
			LocalDateTime reviewedAt,
			MemberResponse.MemberInfo reviewer) {

			this(
				id,
				rating,
				content,
				reviewedAt,
				reviewer,
				new ArrayList<>()
			);
		}
	}

	@Builder
	public record ReviewSummary(
		Integer totalCount,
		BigDecimal averageRating
	) {
		public static ReviewSummary of(AccommodationReviewSummary summary) {
			if (summary == null) {
				return new ReviewSummary(0, BigDecimal.ZERO);
			}
			return new ReviewSummary(
				summary.getTotalReviewCount(),
				summary.getAverageRating()
			);
		}
	}
}

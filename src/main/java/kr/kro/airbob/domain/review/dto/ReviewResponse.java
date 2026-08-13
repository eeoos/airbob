package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
		Instant reviewedAt,
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
				reviewedAt == null ? null : reviewedAt.toInstant(ZoneOffset.UTC),
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
		public static ReviewSummary of(Integer totalCount, BigDecimal averageRating) {
			return new ReviewSummary(
				totalCount == null ? 0 : totalCount,
				averageRating == null ? BigDecimal.ZERO : averageRating
			);
		}

		public static ReviewSummary of(AccommodationReviewSummary summary) {
			if (summary == null) {
				return of(null, null);
			}
			return of(
				summary.getTotalReviewCount(),
				summary.getAverageRating()
			);
		}

		// review 테이블 직접 집계(naive) 결과 매핑
		public static ReviewSummary of(ReviewSummaryRow row) {
			if (row == null) {
				return of(null, null);
			}
			Integer totalCount = row.getTotalCount() == null ? null : row.getTotalCount().intValue();
			return of(totalCount, row.getAverageRating());
		}
	}
}

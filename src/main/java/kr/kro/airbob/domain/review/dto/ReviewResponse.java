package kr.kro.airbob.domain.review.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewResponse {

	@Schema(name = "ReviewResponseCreate")
	public record Create(
		@Schema(description = "리뷰 ID. 수정·삭제·이미지 API의 reviewId", example = "201", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "ReviewResponseUpdate")
	public record Update(
		@Schema(description = "리뷰 ID. 수정·삭제·이미지 API의 reviewId", example = "201", requiredMode = Schema.RequiredMode.REQUIRED)
		long id
	) {
	}

	@Schema(name = "ReviewResponseReviewInfos")
	public record ReviewInfos(
		@Schema(description = "현재 페이지의 게시된 리뷰. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<ReviewInfo> reviews,
		@Schema(description = "다음 페이지 여부·커서·현재 항목 수", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo

	) {
	}

	@Schema(name = "ReviewResponseReviewInfo")
	public record ReviewInfo(
		@Schema(description = "리뷰 ID", example = "201", requiredMode = Schema.RequiredMode.REQUIRED)
		long id,
		@Schema(description = "현재 평점", example = "5", minimum = "1", maximum = "5", requiredMode = Schema.RequiredMode.REQUIRED)
		int rating,
		@Schema(description = "현재 리뷰 본문. 과거 데이터는 null일 수 있습니다.", example = "숙소가 깨끗하고 교통이 편리했어요.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String content,
		@Schema(description = "최초 작성 UTC 시각. 수정 시 바뀌지 않으며 과거 데이터는 null일 수 있습니다.", example = "2027-01-10T02:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant reviewedAt,
		@Schema(description = "작성자의 현재 ID·닉네임·프로필 이미지", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberResponse.MemberInfo reviewer,
		@Schema(description = "첨부 이미지. 없으면 빈 배열이며 표시 순서는 보장하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
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
	@Schema(name = "ReviewResponseReviewSummary")
	public record ReviewSummary(
		@Schema(description = "게시된 리뷰 수. 없으면 0", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer totalCount,
		@Schema(description = "게시된 리뷰의 평균 평점. 소수 둘째 자리까지 반올림하며 리뷰가 없으면 0", example = "4.50", minimum = "0", maximum = "5", requiredMode = Schema.RequiredMode.REQUIRED)
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

	}
}

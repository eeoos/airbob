package kr.kro.airbob.core.api.controller.v1.review.response;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.core.api.controller.v1.image.response.ImageResponse;
import kr.kro.airbob.core.api.controller.v1.member.response.MemberResponse;
import kr.kro.airbob.core.domain.review.ReviewItem;
import kr.kro.airbob.core.domain.review.ReviewPage;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewResponse {

	@Schema(name = "ReviewResponseCreate")
	public record Create(
		@Schema(description = "리뷰 ID. 수정·삭제·이미지 API의 reviewId", example = "201", requiredMode = Schema.RequiredMode.REQUIRED) long id) {
	}

	@Schema(name = "ReviewResponseUpdate")
	public record Update(
		@Schema(description = "리뷰 ID. 수정·삭제·이미지 API의 reviewId", example = "201", requiredMode = Schema.RequiredMode.REQUIRED) long id) {
	}

	@Schema(name = "ReviewResponseReviewInfos")
	public record ReviewInfos(
		@Schema(description = "현재 페이지의 게시된 리뷰. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewInfo> reviews,
		@Schema(description = "다음 페이지 여부·커서·현재 항목 수", requiredMode = Schema.RequiredMode.REQUIRED) CursorResponse.PageInfo pageInfo

	) {
		public static ReviewInfos from(ReviewPage page) {
			return new ReviewInfos(
				page.reviews().stream().map(ReviewInfo::from).toList(),
				page.pageInfo());
		}
	}

	@Schema(name = "ReviewResponseReviewInfo")
	public record ReviewInfo(
		@Schema(description = "리뷰 ID", example = "201", requiredMode = Schema.RequiredMode.REQUIRED) long id,
		@Schema(description = "현재 평점", example = "5", minimum = "1", maximum = "5", requiredMode = Schema.RequiredMode.REQUIRED) int rating,
		@Schema(description = "현재 리뷰 본문. 과거 데이터는 null일 수 있습니다.", example = "숙소가 깨끗하고 교통이 편리했어요.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String content,
		@Schema(description = "최초 작성 UTC 시각. 수정 시 바뀌지 않으며 과거 데이터는 null일 수 있습니다.", example = "2027-01-10T02:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Instant reviewedAt,
		@Schema(description = "작성자의 현재 ID·닉네임·프로필 이미지", requiredMode = Schema.RequiredMode.REQUIRED) MemberResponse.MemberInfo reviewer,
		@Schema(description = "첨부 이미지. 없으면 빈 배열이며 표시 순서는 보장하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED) List<ImageResponse.ImageInfo> images) {

		public static ReviewInfo from(ReviewItem review) {
			return new ReviewInfo(
				review.id(),
				review.rating(),
				review.content(),
				review.reviewedAt() == null ? null : review.reviewedAt().toInstant(ZoneOffset.UTC),
				new MemberResponse.MemberInfo(
					review.reviewerId(), review.reviewerNickname(), review.reviewerThumbnailImageUrl()),
				review.images().stream().map(ImageResponse.ImageInfo::from).toList());
		}
	}
}

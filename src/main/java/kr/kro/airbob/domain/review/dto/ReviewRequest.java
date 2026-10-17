package kr.kro.airbob.domain.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReviewRequest {

	@Schema(name = "ReviewRequestCreate")
	public record Create(

		@Schema(description = "1~5의 정수 평점", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "평점은 필수입니다.")
		@Positive(message = "평점은 양수여야 합니다.")
		@Min(1)
		@Max(5)
		Integer rating,

		@Schema(description = "리뷰 본문. 작성·수정 모두 필수이며 빈 값·공백만 있는 값 불가. 최대 1024자(이모지 등 일부 문자는 2자로 계산)", example = "숙소가 깨끗하고 교통이 편리했어요.", minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "리뷰 본문은 공백일 수 없습니다.")
		@Size(min = 1, max = 1024, message = "리뷰 내용은 1자 이상 1024자 이하여야 합니다.")
		String content
	) {
	}

	@Schema(name = "ReviewRequestUpdate", description = "본문은 필수, 평점은 생략·null이면 유지", example = "{\"content\":\"수정한 리뷰 내용입니다.\"}")
	public record Update(

		@Schema(description = "리뷰 본문. 작성·수정 모두 필수이며 빈 값·공백만 있는 값 불가. 최대 1024자(이모지 등 일부 문자는 2자로 계산)", example = "숙소가 깨끗하고 교통이 편리했어요.", minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "리뷰 본문은 공백일 수 없습니다.")
		@Size(min = 1, max = 1024, message = "리뷰 내용은 1자 이상 1024자 이하여야 합니다.")
		String content,

		@Schema(description = "변경할 평점. 생략·null이면 기존 평점 유지", example = "4", nullable = true)
		@Min(1)
		@Max(5)
		Integer rating
	) {
	}

}

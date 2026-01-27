package kr.kro.airbob.domain.review.dto;

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

	public record Create(

		@NotNull(message = "평점은 필수입니다.")
		@Positive(message = "평점은 양수여야 합니다.")
		@Min(1)
		@Max(5)
		Integer rating,

		@NotBlank(message = "리뷰 본문은 공백일 수 없습니다.")
		@Size(max = 1024, message = "리뷰 내용은 1024자를 초과할 수 없습니다.")
		String content
	) {
	}

	public record Update(

		@NotBlank(message = "리뷰 본문은 공백일 수 없습니다.")
		@Size(max = 1024, message = "리뷰 내용은 1024자를 초과할 수 없습니다.")
		String content
	) {
	}

}

package kr.kro.airbob.domain.wishlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistAccommodationRequest {

	public record Create(
		@Positive(message = "숙소 ID는 양수여야 합니다.")
		@NotNull(message = "숙소 ID는 필수입니다.")
		Long accommodationId
	) {
	}

	public record Update(
		@NotBlank(message = "메모는 공백일 수 없습니다.")
		@Size(max = 1024)
		String memo
	) {
	}
}

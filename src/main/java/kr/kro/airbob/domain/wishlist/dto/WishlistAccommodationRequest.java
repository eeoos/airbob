package kr.kro.airbob.domain.wishlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistAccommodationRequest {

	@Schema(name = "WishlistAccommodationRequestCreate")
	public record Create(
		@Positive(message = "숙소 ID는 양수여야 합니다.")
		@NotNull(message = "숙소 ID는 필수입니다.")
		@Schema(description = "추가할 공개 숙소 ID", example = "101")
		Long accommodationId
	) {
	}

	@Schema(name = "WishlistAccommodationRequestUpdate")
	public record Update(
		@NotBlank(message = "메모는 공백일 수 없습니다.")
		@Size(min = 1, max = 1024, message = "메모는 1자 이상 1,024자 이하여야 합니다.")
		@Schema(description = "저장한 숙소의 메모. 필수이며 null·빈 문자열·공백만으로 지울 수 없습니다. 길이는 UTF-16 코드 단위 기준입니다.", example = "역에서 가까운 숙소")
		String memo
	) {
	}
}

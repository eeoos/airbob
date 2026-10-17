package kr.kro.airbob.domain.wishlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistRequest {


	@Schema(name = "WishlistRequestCreate")
	public record Create(
		@NotBlank(message = "위시리스트 이름은 공백일 수 없습니다.")
		@Size(min = 1, max = 255, message = "위시리스트 이름은 1자 이상 255자 이하여야 합니다.")
		@Schema(description = "위시리스트 이름. 공백만 사용할 수 없으며 같은 이름의 목록도 허용합니다. 길이는 UTF-16 코드 단위 기준입니다.", example = "서울 여행")
		String name
	) {
	}

	@Schema(name = "WishlistRequestUpdate")
	public record Update(
		@NotBlank(message = "위시리스트 이름은 공백일 수 없습니다.")
		@Size(min = 1, max = 255, message = "위시리스트 이름은 1자 이상 255자 이하여야 합니다.")
		@Schema(description = "위시리스트 이름. 공백만 사용할 수 없으며 같은 이름의 목록도 허용합니다. 길이는 UTF-16 코드 단위 기준입니다.", example = "서울 여행")
		String name
	) {
	}


}

package kr.kro.airbob.domain.wishlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WishlistRequest {


	public record Create(
		@NotBlank(message = "위시리스트 이름은 공백일 수 없습니다.")
		@Size(max = 255)
		String name
	) {
	}

	public record Update(
		@NotBlank(message = "위시리스트 이름은 공백일 수 없습니다.")
		@Size(max = 255)
		String name
	) {
	}


}

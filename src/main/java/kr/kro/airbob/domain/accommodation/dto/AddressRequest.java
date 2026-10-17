package kr.kro.airbob.domain.accommodation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AddressRequest {

	@Builder
	@Schema(name = "AddressRequestAddressInfo")
	public record AddressInfo(
		@NotBlank(message = "우편번호는 필수입니다.")
		@Size(min = 1, max = 12, message = "우편번호는 최대 12자입니다.")
		@Schema(description = "우편번호", example = "03000")
		String postalCode,

		@NotBlank(message = "국가는 필수입니다.")
		@Size(min = 1, max = 255)
		@Schema(description = "국가", example = "대한민국")
		String country,

		@Size(max = 255)
		@Schema(description = "시·도 또는 주", example = "서울특별시", nullable = true)
		String state,
		@NotBlank(message = "도시는 필수입니다.")
		@Size(min = 1, max = 255)
		@Schema(description = "도시", example = "서울")
		String city,

		@Size(max = 255)
		@Schema(description = "구·군", example = "종로구", nullable = true)
		String district,

		@NotBlank(message = "도로명 주소는 필수입니다.")
		@Size(min = 1, max = 255)
		@Schema(description = "도로명 주소. 예시는 가상 주소이며 실행 시 실제 숙소 주소로 바꿉니다.", example = "가상로 123")
		String street,

		@Size(max = 255)
		@Schema(description = "상세주소", example = "101호", nullable = true)
		String detail

	){
	}
}

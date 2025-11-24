package kr.kro.airbob.domain.accommodation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AddressRequest {

	@Builder
	public record AddressInfo(
		@NotBlank(message = "우편번호는 필수입니다.")
		@Size(max = 12, message = "우편번호는 최대 12자입니다.")
		String postalCode,

		@NotBlank(message = "국가는 필수입니다.")
		String country,

		@NotBlank(message = "행정구역은 필수입니다.")
		String state,
		@NotBlank(message = "도시는 필수입니다.")
		String city,

		@NotBlank(message = "지역구는 필수입니다.")
		String district,

		@NotBlank(message = "도로명 주소는 필수입니다.")
		String street,

		@NotBlank(message = "상세 주소는 필수입니다.")
		String detail

	){
	}
}

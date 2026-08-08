package kr.kro.airbob.domain.commoncode.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonCodeRequest {

	// 신규 코드 생성. code 는 그룹 내 고유
	public record Create(
		@NotBlank String code,
		@NotBlank String name,
		String description,
		Integer sortOrder,
		Boolean isActive
	) {
	}

	// 표시 속성 부분 수정(PATCH)
	// null 필드는 변경 X
	public record Update(
		String name,
		String description,
		Integer sortOrder,
		Boolean isActive
	) {
	}
}

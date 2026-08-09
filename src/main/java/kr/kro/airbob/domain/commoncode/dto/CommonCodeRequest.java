package kr.kro.airbob.domain.commoncode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonCodeRequest {

	// 신규 코드 생성. code 는 그룹 내 고유
	public record Create(
		@NotBlank
		@Size(max = 50)
		@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$")
		String code,
		@NotBlank
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String name,
		@Size(max = 255)
		String description,
		Integer sortOrder,
		Boolean isActive
	) {
		public Create {
			if (code != null) {
				code = code.strip();
			}
		}
	}

	// 표시 속성 부분 수정(PATCH)
	// null 필드는 변경 X
	public record Update(
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String name,
		@Size(max = 255)
		String description,
		Integer sortOrder,
		Boolean isActive
	) {
	}
}

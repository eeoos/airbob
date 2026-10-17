package kr.kro.airbob.domain.commoncode.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonCodeRequest {

	// 신규 코드 생성. code 는 그룹 내 고유
	@Schema(name = "CommonCodeRequestCreate")
	public record Create(
		@Schema(description = "그룹 내 고유 코드. 앞뒤 공백 제거 후 영문자로 시작하는 영문·숫자·밑줄 1~50자. 대문자로 저장하며 생성 후 변경하지 않습니다.", example = "EXAMPLE_AMENITY")
		@NotBlank
		@Size(max = 50)
		@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$")
		String code,
		@Schema(description = "코드 표시명. 공백만 있는 값은 허용하지 않습니다.", example = "예시 편의시설")
		@NotBlank
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String name,
		@Schema(description = "설명. 생략·null이면 설명 없이 생성하며 빈 문자열도 허용합니다.", example = "시설 설명", nullable = true)
		@Size(max = 255)
		String description,
		@Schema(description = "32비트 정렬 순서. 생략·null이면 0이며 음수도 허용합니다. 작은 값부터 조회하고 같은 값이면 code 오름차순으로 정렬합니다.", example = "1", nullable = true)
		Integer sortOrder,
		@Schema(description = "코드 활성 여부. 생략·null이면 true. 공개 목록에는 그룹과 코드가 모두 활성인 항목만 표시하며 응답에서는 active라는 이름을 사용합니다.", example = "true", nullable = true)
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
	@Schema(name = "CommonCodeRequestUpdate")
	public record Update(
		@Schema(description = "변경할 표시명. 생략·null이면 유지하며 빈 문자열·공백만 있는 값은 허용하지 않습니다.", example = "예시 편의시설", nullable = true)
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String name,
		@Schema(description = "변경할 설명. 생략·null이면 유지, 빈 문자열이면 내용을 비웁니다.", example = "시설 설명", nullable = true)
		@Size(max = 255)
		String description,
		@Schema(description = "변경할 32비트 정렬 순서. 생략·null이면 유지하며 0과 음수도 허용합니다. 작은 값부터 조회하고 같은 값이면 code 오름차순으로 정렬합니다.", example = "1", nullable = true)
		Integer sortOrder,
		@Schema(description = "변경할 코드 활성 여부. 생략·null이면 유지하며 false도 실제 변경값으로 처리합니다.", example = "true", nullable = true)
		Boolean isActive
	) {
	}
}

package kr.kro.airbob.domain.commoncode.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonCodeGroupRequest {

	@Schema(name = "CommonCodeGroupRequestCreate")
	public record Create(
		@Schema(description = "그룹 코드. 앞뒤 공백 제거 후 영문자로 시작하는 영문·숫자·밑줄 1~50자. 대문자로 저장하며 생성 후 변경하지 않습니다.", example = "EXAMPLE_GROUP")
		@NotBlank
		@Size(max = 50)
		@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$")
		String groupCode,
		@Schema(description = "그룹 표시명. 공백만 있는 값은 허용하지 않습니다.", example = "예시 그룹")
		@NotBlank
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String groupName,
		@Schema(description = "그룹 설명. 생략·null이면 설명 없이 생성하며 빈 문자열도 허용합니다.", example = "선택 목록에 표시할 그룹", nullable = true)
		@Size(max = 255)
		String description,
		@Schema(description = "그룹 활성 여부. 생략·null이면 true. 응답에서는 active라는 이름을 사용합니다.", example = "true", nullable = true)
		Boolean isActive
	) {
		public Create {
			if (groupCode != null) {
				groupCode = groupCode.trim();
			}
		}
	}

	@Schema(name = "CommonCodeGroupRequestUpdate")
	public record Update(
		@Schema(description = "변경할 그룹 표시명. 생략·null이면 유지하며 빈 문자열·공백만 있는 값은 허용하지 않습니다.", example = "예시 그룹", nullable = true)
		@Size(max = 100)
		@Pattern(regexp = "(?sU).*\\S.*")
		String groupName,
		@Schema(description = "변경할 설명. 생략·null이면 유지, 빈 문자열이면 내용을 비웁니다.", example = "선택 목록에 표시할 그룹", nullable = true)
		@Size(max = 255)
		String description,
		@Schema(description = "변경할 그룹 활성 여부. 생략·null이면 유지. false이면 공개 목록에서 그룹 전체를 숨기며 하위 코드 자체의 active는 바꾸지 않습니다.", example = "true", nullable = true)
		Boolean isActive
	) {
	}
}

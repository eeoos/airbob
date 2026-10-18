package kr.kro.airbob.domain.commoncode.dto;

import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import lombok.Builder;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 화면용 공통 코드 응답
 */
@Builder
public record CommonCodeAdminResponse(
	@Schema(description = "그룹 내 고유 코드. 수정 경로의 code에 사용합니다.", example = "EXAMPLE_AMENITY", requiredMode = Schema.RequiredMode.REQUIRED)
	String code,
	@Schema(description = "코드 표시명", example = "예시 편의시설", requiredMode = Schema.RequiredMode.REQUIRED)
	String name,
	@Schema(description = "코드 설명. 없으면 null 또는 빈 문자열", example = "시설 설명", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
	String description,
	@Schema(description = "오름차순 정렬 값. 음수도 허용하며 같은 값이면 code 오름차순으로 정렬합니다.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
	int sortOrder,
	@Schema(description = "코드 자체의 활성 여부. 입력 필드명은 is_active이며 그룹의 활성 여부와 별개입니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	boolean active
) {
	public static CommonCodeAdminResponse from(CommonCode commonCode) {
		return CommonCodeAdminResponse.builder()
			.code(commonCode.getCode())
			.name(commonCode.getName())
			.description(commonCode.getDescription())
			.sortOrder(commonCode.getSortOrder())
			.active(commonCode.isActive())
			.build();
	}
}

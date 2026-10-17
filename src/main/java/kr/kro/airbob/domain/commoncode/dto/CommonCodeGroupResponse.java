package kr.kro.airbob.domain.commoncode.dto;

import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import lombok.Builder;
import io.swagger.v3.oas.annotations.media.Schema;

@Builder
public record CommonCodeGroupResponse(
	@Schema(description = "그룹 코드. 공통 코드 조회·생성·관리 경로의 그룹 식별자로 사용합니다.", example = "EXAMPLE_GROUP", requiredMode = Schema.RequiredMode.REQUIRED)
	String groupCode,
	@Schema(description = "그룹 표시명", example = "예시 그룹", requiredMode = Schema.RequiredMode.REQUIRED)
	String groupName,
	@Schema(description = "그룹 설명. 없으면 null 또는 빈 문자열", example = "선택 목록에 표시할 그룹", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
	String description,
	@Schema(description = "그룹 활성 여부. 입력 필드명은 is_active이며 false이면 공개 코드 목록은 비어 있습니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
	boolean active
) {
	public static CommonCodeGroupResponse from(CommonCodeGroup group) {
		return CommonCodeGroupResponse.builder()
			.groupCode(group.getGroupCode())
			.groupName(group.getGroupName())
			.description(group.getDescription())
			.active(group.isActive())
			.build();
	}
}

package kr.kro.airbob.domain.commoncode.dto;

import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import lombok.Builder;

/**
 * 관리자 화면용 공통 코드 응답
 */
@Builder
public record CommonCodeAdminResponse(
	String code,
	String name,
	String description,
	int sortOrder,
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

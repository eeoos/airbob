package kr.kro.airbob.common.code;

import lombok.Builder;

/**
 * 관리자 화면용 공통 코드 응답. 비활성 여부·설명까지 노출(운영 관리용).
 */
@Builder
public record CommonCodeAdminResponse(
	String code,
	String name,
	String description,
	int sortOrder,
	boolean active
) {
	public static CommonCodeAdminResponse from(CommonCodeDetail detail) {
		return CommonCodeAdminResponse.builder()
			.code(detail.getCode())
			.name(detail.getName())
			.description(detail.getDescription())
			.sortOrder(detail.getSortOrder())
			.active(detail.isActive())
			.build();
	}
}

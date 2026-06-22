package kr.kro.airbob.common.code;

import lombok.Builder;

/**
 * 셀렉트 박스/목록 노출용 공통 코드 응답.
 */
@Builder
public record CommonCodeResponse(
	String code,
	String name,
	int sortOrder
) {
	public static CommonCodeResponse from(CommonCodeDetail detail) {
		return CommonCodeResponse.builder()
			.code(detail.getCode())
			.name(detail.getName())
			.sortOrder(detail.getSortOrder())
			.build();
	}
}

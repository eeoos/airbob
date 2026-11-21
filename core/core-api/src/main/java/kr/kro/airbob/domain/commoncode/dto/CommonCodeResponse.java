package kr.kro.airbob.domain.commoncode.dto;

import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import lombok.Builder;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 셀렉트 박스/목록 노출용 공통 코드 응답
 */
@Builder
public record CommonCodeResponse(
	@Schema(description = "그룹 내 고유 코드. 숙소 유형·편의시설 입력에는 표시명이 아닌 이 값을 사용합니다.", example = "WIFI", requiredMode = Schema.RequiredMode.REQUIRED)
	String code,
	@Schema(description = "화면에 표시할 이름", example = "무선 인터넷", requiredMode = Schema.RequiredMode.REQUIRED)
	String name,
	@Schema(description = "오름차순 정렬 값. 음수도 허용하며 같은 값이면 code 오름차순으로 정렬합니다.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
	int sortOrder
) {
	public static CommonCodeResponse from(CommonCode commonCode) {
		return CommonCodeResponse.builder()
			.code(commonCode.getCode())
			.name(commonCode.getName())
			.sortOrder(commonCode.getSortOrder())
			.build();
	}
}

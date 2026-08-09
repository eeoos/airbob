package kr.kro.airbob.domain.commoncode.dto;

import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import lombok.Builder;

@Builder
public record CommonCodeGroupResponse(
	String groupCode,
	String groupName,
	String description,
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

package kr.kro.airbob.core.domain.commoncode;

/** A common code group's stored values. */
public record CommonCodeGroupDetail(
	String groupCode,
	String groupName,
	String description,
	boolean active
) {
	public static CommonCodeGroupDetail from(CommonCodeGroup group) {
		return new CommonCodeGroupDetail(
			group.getGroupCode(),
			group.getGroupName(),
			group.getDescription(),
			group.isActive());
	}
}

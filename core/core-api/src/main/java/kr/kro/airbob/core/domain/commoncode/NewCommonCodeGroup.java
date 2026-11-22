package kr.kro.airbob.core.domain.commoncode;

/**
 * A group to create. The group code is stored trimmed and upper-cased; a null active means active.
 */
public record NewCommonCodeGroup(
	String groupCode,
	String groupName,
	String description,
	Boolean active
) {
}

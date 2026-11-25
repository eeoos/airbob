package kr.kro.airbob.core.domain.commoncode;

/** A common code's stored values. Lists sort by sortOrder, then code. */
public record CommonCodeDetail(
	String code,
	String name,
	String description,
	int sortOrder,
	boolean active) {
	public static CommonCodeDetail from(CommonCode commonCode) {
		return new CommonCodeDetail(
			commonCode.getCode(),
			commonCode.getName(),
			commonCode.getDescription(),
			commonCode.getSortOrder(),
			commonCode.isActive());
	}
}

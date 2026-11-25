package kr.kro.airbob.core.domain.commoncode;

/**
 * A code to create in a group. The code is stored upper-cased; a null sortOrder means 0 and a null active
 * means active.
 */
public record NewCommonCode(
	String code,
	String name,
	String description,
	Integer sortOrder,
	Boolean active) {
}

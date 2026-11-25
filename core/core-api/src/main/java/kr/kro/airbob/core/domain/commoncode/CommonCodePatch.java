package kr.kro.airbob.core.domain.commoncode;

/** A partial change to a code's display fields. A null field keeps the stored value. */
public record CommonCodePatch(
	String name,
	String description,
	Integer sortOrder,
	Boolean active) {
}

package kr.kro.airbob.core.domain.commoncode;

/** A partial change to a group's display fields. A null field keeps the stored value. */
public record CommonCodeGroupPatch(
	String groupName,
	String description,
	Boolean active) {
}

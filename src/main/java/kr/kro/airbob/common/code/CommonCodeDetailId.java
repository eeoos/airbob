package kr.kro.airbob.common.code;

import java.io.Serializable;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * common_code_detail 복합키 (group_code, code).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CommonCodeDetailId implements Serializable {

	private String groupCode;
	private String code;

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CommonCodeDetailId that)) {
			return false;
		}
		return Objects.equals(groupCode, that.groupCode) && Objects.equals(code, that.code);
	}

	@Override
	public int hashCode() {
		return Objects.hash(groupCode, code);
	}
}

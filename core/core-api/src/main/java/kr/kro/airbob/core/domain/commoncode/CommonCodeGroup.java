package kr.kro.airbob.core.domain.commoncode;

import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.kro.airbob.common.domain.BaseEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 공통 코드 그룹 정의. PK = 자연키 group_code
 */
@Entity
@Table(name = "common_code_group")
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeGroup extends BaseEntity {

	@Id
	@Column(name = "group_code", length = 50)
	private String groupCode;

	@Column(name = "group_name", nullable = false, length = 100)
	private String groupName;

	@Column(length = 255)
	private String description;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	/** 그룹 코드는 앞뒤 공백을 지우고 JVM Locale과 무관하게 ASCII 대문자로 저장·조회한다. */
	public static String normalizeGroupCode(String groupCode) {
		return groupCode.trim().toUpperCase(Locale.ROOT);
	}

	public void updateDisplay(String groupName, String description, Boolean active) {
		if (groupName != null) {
			this.groupName = groupName;
		}
		if (description != null) {
			this.description = description;
		}
		if (active != null) {
			this.active = active;
		}
	}
}

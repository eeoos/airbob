package kr.kro.airbob.domain.commoncode.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import kr.kro.airbob.common.domain.BaseEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 공통 코드 상세. PK = 복합키 (group_code, code)
 */
@Entity
@Table(name = "common_code_detail")
@IdClass(CommonCodeDetailId.class)
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeDetail extends BaseEntity {

	@Id
	@Column(name = "group_code", length = 50)
	private String groupCode;

	@Id
	@Column(length = 50)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 255)
	private String description;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	// 관리자 부분 수정(PATCH)
	// null인 필드는 변경 X
	public void updateDisplay(String name, String description, Integer sortOrder, Boolean active) {
		if (name != null) {
			this.name = name;
		}
		if (description != null) {
			this.description = description;
		}
		if (sortOrder != null) {
			this.sortOrder = sortOrder;
		}
		if (active != null) {
			this.active = active;
		}
	}
}

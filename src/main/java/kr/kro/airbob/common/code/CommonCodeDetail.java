package kr.kro.airbob.common.code;

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
 * 공통 코드 상세. PK = 복합키 (group_code, code).
 * 편의시설/숙소유형의 단일 카탈로그 소스. 원본 테이블은 code 문자열만 느슨하게 참조한다.
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

	// 관리자 부분 수정(PATCH). null 인 필드는 변경하지 않는다. code(PK)는 불변.
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

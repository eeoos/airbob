package kr.kro.airbob.common.code;

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
 * 공통 코드 그룹 정의. PK = 자연키 group_code.
 * 그룹 단위 일괄 비활성화(is_active)를 지원한다.
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
}

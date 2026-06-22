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
 * code 는 애플리케이션 ENUM 상수명과 1:1 (하이브리드 전략의 동기화 기준).
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
}

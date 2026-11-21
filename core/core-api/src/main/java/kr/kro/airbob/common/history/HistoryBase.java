package kr.kro.airbob.common.history;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 모든 이력 테이블 공통 메타. 이력 행은 INSERT-only이므로 이 행이 찍힌 시점/주체를
// history_created_at/by(JPA Auditing)로 기록한다. (원본 레코드의 created_at/by는 각 이력 엔티티가 스냅샷으로 보유)
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class HistoryBase {

	@CreatedDate
	@Column(name = "history_created_at", updatable = false, nullable = false)
	private LocalDateTime historyCreatedAt;

	@CreatedBy
	@Column(name = "history_created_by", updatable = false)
	private Long historyCreatedBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ChangeType changeType;

	@Column(length = 255)
	private String changeReason;

	@Column(length = 30)
	private String sourceSystem;

	@Column(length = 45)
	private String clientIp;
}

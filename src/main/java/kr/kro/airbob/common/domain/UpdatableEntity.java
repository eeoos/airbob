package kr.kro.airbob.common.domain;

import java.time.LocalDateTime;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public class UpdatableEntity extends BaseEntity{

	@LastModifiedDate
	@Column(nullable = false)
	private LocalDateTime updatedAt;
}

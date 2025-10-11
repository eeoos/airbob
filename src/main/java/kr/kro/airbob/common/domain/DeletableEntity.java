package kr.kro.airbob.common.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass
@SQLDelete(sql = "UPDATE #{#entityName} SET deleted_at = NOW(6) WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class DeletableEntity extends UpdatableEntity{

	@Column
	private LocalDateTime deletedAt;
}

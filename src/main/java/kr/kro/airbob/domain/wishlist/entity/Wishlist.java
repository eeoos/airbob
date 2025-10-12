package kr.kro.airbob.domain.wishlist.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends UpdatableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WishlistStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@PrePersist
	public void onPrePersist() {
		if (this.status == null) {
			this.status = WishlistStatus.ACTIVE;
		}
	}

	public void updateName(String name) {
		this.name = name;
	}

	public void delete() {
		this.status = WishlistStatus.DELETED;
	}
}

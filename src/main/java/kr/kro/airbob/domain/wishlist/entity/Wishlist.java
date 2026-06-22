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
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WishlistStatus status;

	// 반정규화: 저장한 숙소 개수(멤버십 수). 추가/삭제 시 원자적 UPDATE로 유지.
	@Column(nullable = false)
	@Builder.Default
	private Integer accommodationCount = 0;

	// 반정규화: 대표(가장 최근에 추가된) 숙소 id. 썸네일은 PK 배치 조인으로 조회.
	@Column(name = "representative_accommodation_id")
	private Long representativeAccommodationId;

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

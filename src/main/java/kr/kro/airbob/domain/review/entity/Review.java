package kr.kro.airbob.domain.review.entity;

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
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
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
public class Review extends UpdatableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String content;

	private Integer rating;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReviewStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "accommodation_id")
	private Accommodation accommodation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member author;

	public void updateContent(String content) {
		this.content = content;
	}

	public void updateRating(Integer rating) {
		this.rating = rating;
	}

	@PrePersist
	public void onPrePersist() {
		if (this.status == null) {
			this.status = ReviewStatus.PUBLISHED;
		}
	}

	public void deleteByUser() {
		this.status = ReviewStatus.DELETED_BY_USER;
	}
}

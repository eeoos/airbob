package kr.kro.airbob.domain.review.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccommodationReviewSummary extends BaseEntity {

	@Id
	@Column(name = "accommodation_id")
	private Long accommodationId;

	@Version
	private Long version;

	@OneToOne(fetch = FetchType.LAZY)
	@MapsId
	@JoinColumn(name = "accommodation_id")
	private Accommodation accommodation;

	@Column(nullable = false)
	private Integer totalReviewCount = 0;

	@Column(nullable = false)
	private Long ratingSum = 0L;

	@Column(precision = 3, scale = 2)
	private BigDecimal averageRating = BigDecimal.ZERO;

	@Builder
	public AccommodationReviewSummary(Accommodation accommodation) {
		this.accommodation = accommodation;
		this.accommodationId = accommodation.getId();
	}

	public void addReview(int rating) {
		this.totalReviewCount++;
		this.ratingSum += rating;
		this.averageRating = calculateAverageRating();
	}

	public void removeReview(int rating) {
		this.totalReviewCount--;
		this.ratingSum -= rating;
		this.averageRating = calculateAverageRating();
	}

	public void updateReview(int oldRating, int newRating) {
		this.ratingSum = this.ratingSum - oldRating + newRating;
		this.averageRating = calculateAverageRating();
	}

	private BigDecimal calculateAverageRating() {
		if (totalReviewCount == 0) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(ratingSum)
			.divide(BigDecimal.valueOf(totalReviewCount), 2, RoundingMode.HALF_UP);
	}
}
